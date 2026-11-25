package app.fireplayer.vod.sources

import app.fireplayer.vod.Addon
import app.fireplayer.vod.AddonState
import app.fireplayer.vod.Attempt
import app.fireplayer.vod.Cancel
import app.fireplayer.vod.EpisodeRef
import app.fireplayer.vod.Failure
import app.fireplayer.vod.FailureKind
import app.fireplayer.vod.Hdr
import app.fireplayer.vod.LastRelease
import app.fireplayer.vod.LookupWhy
import app.fireplayer.vod.Offer
import app.fireplayer.vod.OpenWhy
import app.fireplayer.vod.Opened
import app.fireplayer.vod.PlayOrigin
import app.fireplayer.vod.PlayRequest
import app.fireplayer.vod.PlaybackSource
import app.fireplayer.vod.Readiness
import app.fireplayer.vod.ReleaseKey
import app.fireplayer.vod.SourceGroup
import app.fireplayer.vod.SourceList
import app.fireplayer.vod.SourceSession
import app.fireplayer.vod.SourceTarget
import app.fireplayer.vod.StallWatch
import app.fireplayer.vod.StartAt
import app.fireplayer.vod.TvFit
import app.fireplayer.vod.Step
import java.util.EnumMap
import java.util.EnumSet

/**
 * One play of a film or episode, stages 1-4 (design-brief §6.5). Picks the source (on a resume, the very file played
 * last, from its add-on first, whatever the ranking says; a row chosen in Sources; the same kind as the previous
 * episode; else "Best for this TV"), checks its link ([LinkCheck]), and on a failure follows the table
 * ([failed]): skip the file and its copies in every add-on, refresh an expired link once, decode the sound on the Stick,
 * use the other add-on, or stop and say why. Limits: 3 start tries and 2 automatic switches per film or episode, at
 * least 3 minutes apart; a source not cached is never opened without [fetch], asked once. Main thread.
 */
internal class DefaultSourceSession(
    override val request: PlayRequest,
    private val repo: DefaultSourcesRepository,
) : SourceSession {

    private val target: SourceTarget = request.target
    private val episode = target.episode != null

    override var list: SourceList? = request.list
        private set

    private val tries = ArrayList<Attempt>()
    override val attempts: List<Attempt> get() = tries.toList()

    private val watch = Stalls()
    override val stalls: StallWatch get() = watch

    private var listener: (() -> Unit)? = null
    private var listSub: Cancel? = null
    private var listOnly: Addon? = null
    /** Every add-on is asked a while after the copy started, when only one was ([widenLater]). */
    private var widening: Runnable? = null
    private var closed = false

    private var startedOnce = false
    private var firstPick = true
    private var startTries = 0
    private var switches = 0
    private var lastSwitchAt = Long.MIN_VALUE / 2
    private var wrongGuesses = 0
    private var accountProblem = false
    private var currentWhy = OpenWhy.START
    private var playing: PlaybackSource? = null
    private var playingOffer: Offer? = null
    /**
     * The copy handed to the player last ([Opened.Ready]): the one loading, or playing once its first frame came. The
     * Sources window marks it Playing from then on, so Next source goes on from it and never names it.
     */
    private var opened: PlaybackSource? = null
    /**
     * The file the copy playing started from, kept as the title's last release ([started]), and the release kept before
     * it: put back when it turns out not to be the whole film ([FailureKind.NOT_WHOLE]).
     */
    private var releaseFor: ReleaseKey? = null
    private var releaseBefore: LastRelease? = null
    /** Copies chosen by hand that turned out not to be the whole film ([notWhole]): never kept as the title's last release. */
    private val notWholeKeys = HashSet<String>()
    /**
     * Another episode with no copy of the kind the show followed ([PlayRequest.like]): at its first frame the show's
     * remembered copies are forgotten ([app.fireplayer.vod.PlaybackLibrary.forgetReleases]), so the ranking decides on.
     */
    private var kindLost = false

    private val skipped = HashSet<String>()
    private val skippedHashes = HashSet<String>()
    private val badFormats = HashSet<String>()
    private val downAddons: EnumSet<Addon> = EnumSet.noneOf(Addon::class.java)
    private val retried = HashSet<String>()
    private val refreshed = HashSet<String>()
    private val decoded = HashSet<String>()
    private val visited = LinkedHashSet<String>()
    /**
     * The files handed to the player or played in this session, by what they are ([FileSet]): their rows may have
     * merged or changed keys since. A copy left while it loaded counts: Next source never names it again.
     */
    private val triedFiles = FileSet(episode)
    /** The files left because they can't play here ([skip] with its file: no picture, not whole, slow, link dead). */
    private val skippedFiles = FileSet(episode)
    /** Why the resume didn't take the copy it left ([resumeMiss]); null when it did, or there was none. */
    private var resumeMissed: String? = null
    private val fetchAgreed = HashSet<String>()
    /** The offer last tried for each source (by key): its own file is the one skipped when it fails. */
    private val triedOffer = HashMap<String, Offer>()
    private val unknownBy = EnumMap<Addon, MutableSet<String>>(Addon::class.java)
    private val ops = ArrayList<Op>()

    /** One [open] or [fetch]: progress, then one final answer; nothing after [cancel]. */
    private inner class Op(private val onOpened: (Opened) -> Unit) : Cancel {
        var cancelled = false
        var done = false
        var waiting: Pair<(SourceList) -> Boolean, () -> Unit>? = null
        val pending = ArrayList<Cancel>()
        var timer: Runnable? = null
        var handle: CallHandle? = null

        val live: Boolean get() = !cancelled && !done && !closed

        fun progress(o: Opened) {
            if (live) onOpened(o)
        }

        fun final(o: Opened) {
            if (!live) return
            done = true
            cleanup()
            onOpened(o)
        }

        override fun cancel() {
            cancelled = true
            cleanup()
        }

        fun cleanup() {
            waiting = null
            pending.forEach { it.cancel() }
            pending.clear()
            timer?.let { repo.main.remove(it) }
            timer = null
            handle?.cancel()
            handle = null
            ops.remove(this)
        }
    }

    // ---- SourceSession ----

    override fun setListener(listener: (() -> Unit)?) {
        this.listener = listener
    }

    override fun open(source: PlaybackSource?, why: OpenWhy, onOpened: (Opened) -> Unit): Cancel {
        val op = Op(onOpened)
        ops += op
        // Next source or a row picked: every add-on's copies may be wanted now, not only the one asked first.
        if (why == OpenWhy.USER && startedOnce) widen()
        when {
            closed -> op.final(Opened.NoneLeft(FailureWords.NONE_READY))
            source != null -> check(op, source, why, near = startedOnce, countTry = true)
            why == OpenWhy.START && firstPick -> startFirst(op)
            else -> pick(op, why)
        }
        return op
    }

    override fun fetch(source: PlaybackSource, onOpened: (Opened) -> Unit): Cancel {
        val op = Op(onOpened)
        ops += op
        fetchAgreed += source.key
        repo.fetchAsked += target.key
        check(op, source, OpenWhy.USER, near = startedOnce, countTry = false)
        return op
    }

    override fun refresh(source: PlaybackSource, addon: Addon?, onOpened: (Opened) -> Unit): Cancel {
        val op = Op(onOpened)
        ops += op
        val from = addon ?: triedOffer[source.key]?.addon ?: source.offers.firstOrNull()?.addon
        when {
            closed -> op.final(Opened.NoneLeft(FailureWords.NONE_READY))
            from == null -> {
                skip(source, hash = false)
                pick(op, OpenWhy.FAILED)
            }
            else -> refresh(op, source, from, OpenWhy.FAILED, near = startedOnce)
        }
        return op
    }

    override fun started(source: PlaybackSource, offer: Offer) {
        startedOnce = true
        firstPick = false
        playing = source
        playingOffer = offer
        opened = source
        visited += source.key
        triedFiles.add(source)
        tries += Attempt(source, repo.wall(), currentWhy, null)
        if (kindLost) {
            kindLost = false
            runCatching { repo.library.forgetReleases(target.title) }
        }
        // The file that played: a merged row's offer can be another re-upload than the row's first.
        releaseOf(source, offer)?.takeIf { source.key !in notWholeKeys }?.let { release ->
            // What was kept before this file (not this file itself), put back if it isn't the whole film ([putBack]).
            if (release != releaseFor) {
                releaseFor = release
                releaseBefore = runCatching { repo.library.lastRelease(target.title, target.episode) }.getOrNull()
                    ?.takeIf { it.release != release }
            }
            runCatching { repo.library.recordRelease(target.title, target.episode, LastRelease(offer.addon, release, repo.wall())) }
        }
        repo.usedNow(target, source.key, releaseOf(source, offer))
        val now = repo.elapsed()
        watch.reset()
        watch.playing(now)
        watch.jumped(now)
        widenLater()
        listener?.invoke()
    }

    override fun failed(source: PlaybackSource, failure: Failure): Step {
        tries += Attempt(source, repo.wall(), currentWhy, failure)
        val step = stepFor(source, failure)
        listener?.invoke()
        return step
    }

    override fun notWhole(source: PlaybackSource) {
        notWholeKeys += source.key
        // Its first frame came first: what was kept before it is the title's again.
        if (keptAsLast(source)) putBack()
        repo.rerank(target)
    }

    override fun failureOf(errorCode: Int, httpStatus: Int?): Failure =
        PlayerErrors.failureOf(errorCode, httpStatus, repo.online(), playingOffer?.addon)

    override fun online(): Boolean = repo.online()

    override fun maySwitch(now: Long): Boolean =
        startedOnce && switches < MAX_SWITCHES && now - lastSwitchAt >= SWITCH_GAP_MS

    override fun lighter(maxMbps: Double): PlaybackSource? {
        val l = list ?: return null
        val current = currentKey()
        val same = currentFiles(l)
        // In "Best for this TV" order: the first that fits is the best that fits. One this TV likely can't play only
        // when nothing else does. Its rate as played here when it was measured ([SourceRank.asPlayedHere]), and never
        // the file playing under another row.
        val fits = l.sources.filter { s ->
            val avg = SourceRank.asPlayedHere(s.facts, s.measured).avgMbps
            s.automatic && usable(s) && !setAsideHere(s) && s.key != current && same?.holds(s) != true &&
                avg != null && avg > 0.0 && avg <= maxMbps
        }
        return fits.firstOrNull { it.fit != TvFit.LIKELY_NOT } ?: fits.firstOrNull()
    }

    override fun rowKey(source: PlaybackSource): String {
        val offer = if (source.key == playing?.key) playingOffer else triedOffer[source.key]
        val file = offer?.let { releaseOf(source, it) } ?: source.release
        file?.let { repo.rowKeyOf(target, it) }?.let { return it }
        // Its release is in no row now (the answer that had it is gone): the row holding the same file, if one does.
        val l = list ?: return source.key
        if (l.sources.any { it.key == source.key }) return source.key
        val same = FileSet.of(source, episode)
        return l.sources.firstOrNull { same.holds(it) }?.key ?: source.key
    }

    override fun nextPick(): PlaybackSource? = list?.let { if (closed) null else nextOf(it) }

    override fun resumeMiss(): String? = resumeMissed

    override fun lookUpNext(next: EpisodeRef, onList: (SourceList) -> Unit): Cancel =
        repo.lookUp(SourceTarget(target.title, next), LookupWhy.NEXT_EPISODE, null, onList)

    override fun widen() {
        if (closed || listOnly == null) return
        widening?.let { repo.main.remove(it) }
        widening = null
        watchList(null)
    }

    override fun close() {
        if (closed) return
        closed = true
        widening?.let { repo.main.remove(it) }
        widening = null
        listSub?.cancel()
        listSub = null
        ops.toList().forEach { it.cancel() }
        listener = null
    }

    // ---- The failure table ----

    private fun stepFor(source: PlaybackSource, f: Failure): Step = when (f.kind) {
        FailureKind.FILE -> {
            skip(source, hash = true)
            Step.TryNext
        }
        FailureKind.NOT_READY -> {
            skip(source, hash = true)
            if (source.readiness == Readiness.PROBABLY_READY) wrongGuesses++
            Step.TryNext
        }
        FailureKind.FORMAT -> {
            skip(source, hash = false)
            badFormats += formatKey(source)
            Step.TryNext
        }
        // This copy only (its file, in any row: the same bytes show no picture either): another of the same format may
        // well show its picture (a profile 8 Dolby Vision file where a profile 5 one showed none); every copy of its
        // format when no decoder here takes that format.
        FailureKind.NO_PICTURE -> {
            skip(source, hash = false, file = true)
            if (f.detail == Failure.NO_DECODER) badFormats += formatKey(source)
            Step.TryNext
        }
        // This copy only (its file, in any row): another file of its torrent may well be the film. Nor is it the copy
        // the title goes on from; the list in memory ranks again with the player's mark on it ("Shorter than the film
        // here", "Last used").
        FailureKind.NOT_WHOLE -> {
            skip(source, hash = false, file = true)
            if (keptAsLast(source)) putBack()
            repo.rerank(target)
            Step.TryNext
        }
        FailureKind.AUDIO -> if (decoded.add(source.key)) Step.DecodeOnDevice else {
            skip(source, hash = false)
            Step.TryNext
        }
        FailureKind.LINK_EXPIRED -> if (refreshed.add(source.key)) Step.Refresh else {
            skip(source, hash = false, file = true)
            Step.TryNext
        }
        FailureKind.ADDON_DOWN, FailureKind.CONFIG -> {
            downAddons += f.addon ?: source.offers.firstOrNull()?.addon ?: Addon.MEDIAFUSION
            if (source.offers.none { openable(source, it) }) skip(source, hash = false)
            Step.TryNext
        }
        FailureKind.JUST_ADDED -> if (retried.add("j|${source.key}")) Step.Retry(JUST_ADDED_WAIT_MS) else {
            skip(source, hash = true)
            Step.TryNext
        }
        // Slow to start: the same file in another row starts as slowly.
        FailureKind.SLOW -> {
            skip(source, hash = false, file = true)
            Step.TryNext
        }
        FailureKind.RATE_LIMITED -> if (retried.add("rate")) Step.Retry(RATE_WAIT_MS) else Step.Stop(FailureWords.stop(f.kind))
        FailureKind.ACCOUNT, FailureKind.LIMITS -> {
            accountProblem = true
            Step.Stop(FailureWords.stop(f.kind))
        }
        FailureKind.OUTAGE, FailureKind.NO_INTERNET -> Step.Stop(FailureWords.stop(f.kind))
        FailureKind.UNKNOWN -> if (retried.add("u|${source.key}")) Step.Retry(UNKNOWN_WAIT_MS) else {
            skip(source, hash = false)
            f.addon?.let { a ->
                val seen = unknownBy.getOrPut(a) { HashSet() }
                seen += source.key
                // Two different sources from one add-on failing this way: that add-on is down.
                if (seen.size >= 2) downAddons += a
            }
            Step.TryNext
        }
    }

    /** [source] is the copy playing, and its file is the one kept as the title's last release ([started]). */
    private fun keptAsLast(source: PlaybackSource): Boolean =
        releaseFor != null && source.key == playing?.key && playingOffer?.let { releaseOf(source, it) } == releaseFor

    /**
     * The copy that started last wasn't the whole film: the release kept before it is the title's again (none: it's
     * forgotten), so a resume goes on from the copy that was playing before.
     */
    private fun putBack() {
        val before = releaseBefore
        releaseFor = null
        releaseBefore = null
        runCatching {
            if (before != null) repo.library.recordRelease(target.title, target.episode, before)
            else repo.library.forgetRelease(target.title, target.episode)
        }
    }

    /** [source]'s row isn't tried again in this play; with [hash], nothing of its torrent; with [file], its file in no row. */
    private fun skip(source: PlaybackSource, hash: Boolean, file: Boolean = false) {
        skipped += source.key
        if (hash) (triedOffer[source.key]?.let { releaseOf(source, it) } ?: source.release)?.let { skippedHashes += it.infoHash }
        if (file) skippedFiles.add(source)
    }

    /** The file [o] opens: its own, else its row's. */
    private fun releaseOf(s: PlaybackSource, o: Offer) = o.release ?: s.release

    /** [o] may be opened: its add-on isn't down, and it's cached unless the user agreed to fetch [s] (§6.5). */
    private fun openable(s: PlaybackSource, o: Offer): Boolean =
        o.addon !in downAddons && (o.readiness != Readiness.NOT_READY || s.key in fetchAgreed)

    private fun formatKey(s: PlaybackSource) = "${s.facts.codec}|${Hdr.DOLBY_VISION in s.facts.hdr}"

    // ---- Stage 1: the list ----

    private fun startFirst(op: Op) {
        val preferred = request.preferred
        if (request.chosen != null) {
            watchList(null)
            pick(op, OpenWhy.START)
            return
        }
        if (request.origin == PlayOrigin.CONTINUE_CARD && preferred != null) {
            // Only the add-on that supplied the release, no wait for the other; when it's gone, the normal search.
            watchList(preferred.addon)
            waitFor(op, { l -> findPreferred(l) != null || l.states[preferred.addon]?.final == true }) {
                if (findPreferred(list) != null) {
                    pick(op, OpenWhy.START)
                } else {
                    watchList(null)
                    waitFor(op, { it.complete }) { pick(op, OpenWhy.START) }
                }
            }
            return
        }
        watchList(null)
        if (request.origin == PlayOrigin.TITLE_PAGE && request.list?.complete == false) {
            // The title page's wait ended before every add-on answered: OK was pressed again for the best so far (or
            // an answer was stuck past its limit). What it handed over is played from as it stands, as soon as something
            // in it plays by itself; the add-on still looking joins the list as it answers (Sources, Next source). A resume
            // first waits for the add-on of the copy left, as a Continue card does: that file plays once it's listed.
            waitFor(op, { l ->
                l.complete || findPreferred(l) != null ||
                    ((preferred == null || l.states[preferred.addon]?.final == true) && l.sources.any { it.automatic && usable(it) })
            }) { pick(op, OpenWhy.START) }
            return
        }
        waitFor(op, { it.complete }) { pick(op, OpenWhy.START) }
    }

    /** Keeps [list] current: late answers join it (Sources, Next source). */
    private fun watchList(only: Addon?) {
        if (listSub != null && listOnly == only) return
        listSub?.cancel()
        listOnly = only
        listSub = repo.lookUp(target, LookupWhy.PLAY, only) { l ->
            list = l
            listener?.invoke()
            for (op in ops.toList()) {
                val (ready, then) = op.waiting ?: continue
                if (!op.live) continue
                if (ready(l)) {
                    op.waiting = null
                    then()
                } else {
                    op.progress(Opened.Looking(l))
                }
            }
        }
    }

    /**
     * Only one add-on was asked (a Continue card's resume: a fast start): the others are asked [WIDEN_AFTER_MS] after the
     * copy started, once, not while its first load fills, so Sources, Next source and the fallbacks have every copy.
     * The answer already kept is used again, not asked for twice.
     */
    private fun widenLater() {
        if (listOnly == null || widening != null || closed) return
        val later = Runnable {
            widening = null
            if (!closed) watchList(null)
        }
        widening = later
        repo.main.postDelayed(later, WIDEN_AFTER_MS)
    }

    private fun waitFor(op: Op, ready: (SourceList) -> Boolean, then: () -> Unit) {
        val l = list
        if (l != null && ready(l)) {
            then()
            return
        }
        op.waiting = ready to then
        op.progress(Opened.Looking(l ?: blankList()))
    }

    private fun blankList() = SourceList(target, Addon.entries.associateWith { AddonState.NotAsked }, emptyList(), null)

    /**
     * The row holding the very file played last (same info hash and file index, any add-on), while that file plays
     * without asking (cached, not skipped, not one this Stick showed no picture for or found not the whole film); the
     * ranking doesn't matter.
     */
    private fun findPreferred(l: SourceList?): PlaybackSource? {
        val p = request.preferred ?: return null
        return l?.sources?.firstOrNull { s ->
            usable(s) && !setAsideHere(s) && s.offers.any { releaseOf(s, it) == p.release && openable(s, it) }
        }
    }

    // ---- Picking ----

    /**
     * [s] may be tried: not skipped in this play (its row, a torrent of any of its offers, or its file in another row:
     * [skippedFiles]), something in it may be opened, and its format didn't fail here.
     */
    private fun usable(s: PlaybackSource): Boolean =
        s.key !in skipped && !hashSkipped(s) && !skippedFiles.holds(s) &&
            s.offers.any { openable(s, it) } && formatKey(s) !in badFormats

    private fun hashSkipped(s: PlaybackSource): Boolean {
        if (skippedHashes.isEmpty()) return false
        if (s.release?.infoHash in skippedHashes) return true
        return s.offers.any { it.release?.infoHash in skippedHashes }
    }

    /**
     * This Stick showed no picture for [s] when it played here before, or found it wasn't the whole film
     * ([app.fireplayer.player.CopyHealth.setAside]): never picked by itself again (the viewer still may, in Sources).
     */
    private fun setAsideHere(s: PlaybackSource): Boolean = s.measured?.health?.setAside == true

    /**
     * The row Next source goes on from: the copy handed to the player last (loading or playing), else the one tried
     * last, under the key its file has in the list now ([rowKey]).
     */
    private fun currentKey(): String? = current()?.let { rowKey(it) }

    /** The copy handed to the player last ([opened]: loading or playing), else the one playing, else the one tried last. */
    private fun current(): PlaybackSource? = opened ?: playing ?: tries.lastOrNull()?.source

    /**
     * The [current] copy's file, by what it is ([FileSet]): its row as it was opened, and the row holding it in [l] now
     * (merged since, or measured: its size to the byte); null when there's none.
     */
    private fun currentFiles(l: SourceList?): FileSet? {
        val s = current() ?: return null
        val files = FileSet.of(s, episode)
        val key = rowKey(s)
        l?.sources?.firstOrNull { it.key == key }?.let { files.add(it) }
        return files
    }

    /**
     * Whether [s] was handed to the player or played in this session: its row then, or its file, in a row it has joined
     * or been keyed under since.
     */
    private fun visited(s: PlaybackSource): Boolean = s.key in visited || triedFiles.holds(s)

    /**
     * Next source and switches: the rest of the copy playing's own group in the Sources window (down from it, then the
     * ones above it), then the groups under it, then the groups above ([SourceRank.nextOrder], never a copy set aside
     * here), skipping what failed here. Never the file loading or playing, in any row, nor one played or tried in this
     * play. Only when a copy failed or kept stalling ([why]) and nothing new is left: back to the copy that played last
     * ([playedLast]), so a switch whose copy won't start doesn't end the play.
     */
    private fun nextOf(l: SourceList, why: OpenWhy = OpenWhy.USER): PlaybackSource? {
        val same = currentFiles(l)
        val order = SourceRank.nextOrder(l.sources, currentKey(), !episode, current()) { same?.holds(it) == true }
        order.firstOrNull { usable(it) && !visited(it) }?.let { return it }
        return if (why == OpenWhy.FAILED || why == OpenWhy.STALLS) playedLast(order) else null
    }

    /**
     * Of [order] (never the file just left: [nextOf]), the row holding the copy that showed its first frame last in this
     * play and may still be tried (not skipped or set aside here); null when there's none.
     */
    private fun playedLast(order: List<PlaybackSource>): PlaybackSource? {
        for (t in tries.asReversed()) {
            if (t.failure != null) continue
            val key = rowKey(t.source)
            val files = FileSet.of(t.source, episode)
            order.firstOrNull { (it.key == key || files.holds(it)) && usable(it) }?.let { return it }
        }
        return null
    }

    private fun pick(op: Op, why: OpenWhy) {
        if (!op.live) return
        val l = list ?: blankList()
        if (!startedOnce && why != OpenWhy.USER && startTries >= MAX_START_TRIES) {
            op.final(Opened.NoneLeft(FailureWords.TRIES_USED))
            return
        }
        if (startedOnce && why == OpenWhy.STALLS && !maySwitch(repo.elapsed())) {
            op.final(Opened.NoneLeft(FailureWords.SWITCHES_USED))
            return
        }
        if (startedOnce && why == OpenWhy.FAILED && switches >= MAX_SWITCHES) {
            op.final(Opened.NoneLeft(FailureWords.SWITCHES_USED))
            return
        }
        if (why != OpenWhy.USER && wrongGuesses >= MAX_WRONG_GUESSES) {
            op.final(Opened.NoneLeft(FailureWords.GUESSES_WRONG))
            return
        }
        val (choice, near) = choose(l, why)
        if (choice == null) {
            // Only one add-on was asked (a Continue card), or one is still looking (the best so far was played, and it
            // failed): the rest is waited for before giving up.
            if (listOnly != null || !l.complete) {
                watchList(null)
                waitFor(op, { it.complete }) { pick(op, why) }
                return
            }
            askFetchOrNone(op, l)
            return
        }
        firstPick = false
        check(op, choice, why, near, countTry = true)
    }

    private fun choose(l: SourceList, why: OpenWhy): Pair<PlaybackSource?, Boolean> {
        val current = currentKey()
        val same = currentFiles(l)
        val auto = l.sources.filter { it.automatic && usable(it) && !setAsideHere(it) && it.key != current && same?.holds(it) != true }
        if (firstPick && !startedOnce) {
            request.chosen?.let { c ->
                val fresh = l.sources.firstOrNull { it.key == c.key } ?: c
                if (usable(fresh)) return fresh to false
            }
            val p = request.preferred
            if (p != null) findPreferred(l)?.let { row ->
                resumeMissed = null
                // That very file first, from its add-on first: the timeline left.
                return row.copy(offers = row.offers.sortedWith(
                    compareBy({ if (releaseOf(row, it) == p.release) 0 else 1 }, { if (it.addon == p.addon) 0 else 1 }),
                )) to false
            }
            if (p != null) resumeMissed = preferredMiss(l, p)
            // A resume whose own file is gone, no longer cached or forgotten: the ranking's pick, a little before where it was.
            val near = request.start is StartAt.At
            request.like?.let { like ->
                val same = SourceRank.mostLike(auto, like)
                kindLost = same == null
                if (same != null) return same to near
            }
            return auto.firstOrNull() to near
        }
        return nextOf(l, why) to startedOnce
    }

    /**
     * Why [p], the copy a resume left, isn't the one played from [l]: in plain words for the log, never a link (the
     * add-on's name at most).
     */
    private fun preferredMiss(l: SourceList, p: LastRelease): String {
        val rows = l.sources.filter { s -> s.offers.any { releaseOf(s, it) == p.release } }
        val s = rows.firstOrNull() ?: return when {
            // A ×N row keeps one offer per add-on: another upload of the same release is offered for it.
            repo.rowKeyOf(target, p.release) != null -> "its row offers another upload of that release"
            l.states[p.addon]?.final != true -> "not listed yet (${p.addon.label} still looking)"
            else -> "not listed any more"
        }
        val o = s.offers.first { releaseOf(s, it) == p.release }
        return when {
            s.measured?.health?.noPicture?.let { it > 0 } == true -> "set aside here: it showed no picture"
            setAsideHere(s) -> "set aside here: it wasn't the whole ${if (episode) "episode" else "film"}"
            o.readiness == Readiness.NOT_READY && s.key !in fetchAgreed -> "not cached any more"
            o.addon in downAddons -> "${o.addon.label} is down"
            !usable(s) -> "skipped in this play"
            else -> "not openable"
        }
    }

    private fun preferAddon(s: PlaybackSource, addon: Addon): PlaybackSource =
        s.copy(offers = s.offers.sortedBy { if (it.addon == addon) 0 else 1 })

    /** No ready source left: ask once whether the debrid service should fetch one (most seeded, a single episode), else say so. */
    private fun askFetchOrNone(op: Op, l: SourceList) {
        if (!accountProblem && target.key !in repo.fetchAsked) {
            val candidates = l.sources.filter {
                it.readiness == Readiness.NOT_READY && it.group != SourceGroup.CINEMA && it.key !in skipped &&
                    it.offers.any { o -> o.addon !in downAddons }
            }
            val singles = if (target.episode != null) candidates.filter { !it.facts.pack } else candidates
            val pick = singles.maxByOrNull { it.facts.seeders ?: 0 }
            if (pick != null) {
                repo.fetchAsked += target.key
                op.final(Opened.AskFetch(pick))
                return
            }
        }
        val message = when {
            l.states.values.all { it == AddonState.NotSet } -> FailureWords.NO_ADDONS
            l.sources.isEmpty() -> FailureWords.NOTHING_FOUND
            startTries >= MAX_START_TRIES -> FailureWords.TRIES_USED
            else -> FailureWords.NONE_READY
        }
        op.final(Opened.NoneLeft(message))
    }

    // ---- Stage 2: the link ----

    private fun check(op: Op, source: PlaybackSource, why: OpenWhy, near: Boolean, countTry: Boolean) {
        if (!op.live) return
        if (source.readiness == Readiness.NOT_READY && source.key !in fetchAgreed) {
            op.final(Opened.AskFetch(source))
            return
        }
        if (countTry) {
            // A switch is another file; the same one again (a refreshed link) isn't.
            if (startedOnce && (why == OpenWhy.STALLS || why == OpenWhy.FAILED) && source.key != playing?.key) {
                switches++
                lastSwitchAt = repo.elapsed()
            }
            if (!startedOnce && why != OpenWhy.USER) startTries++
        }
        currentWhy = why
        // An offer not cached is never opened without asking, even in a row that is ready (§6.5).
        val offers = source.offers.filter { openable(source, it) }
        if (offers.isEmpty()) {
            skip(source, hash = false)
            pick(op, OpenWhy.FAILED)
            return
        }
        op.progress(Opened.Checking(source))
        tryOffer(op, source, offers, 0, why, near)
    }

    private fun tryOffer(op: Op, source: PlaybackSource, offers: List<Offer>, i: Int, why: OpenWhy, near: Boolean) {
        val offer = offers[i]
        triedOffer[source.key] = offer
        val handle = CallHandle()
        op.handle = handle
        val confirmed = source.measured?.spec != null
        val justAdded = repo.elapsed() - repo.lastAddAt < JUST_ADDED_WINDOW_MS
        repo.io.execute {
            val result = runCatching { repo.linkCheck.check(offer.addon, offer.link, confirmed, handle) }
                .getOrElse { LinkCheck.Result.Failed(Failure(FailureKind.UNKNOWN, offer.addon, it.javaClass.simpleName)) }
            repo.main.post {
                // The add-on's status learns from it, whether this play still waits for it or not (not from a check
                // stopped half way: that says nothing of the add-on).
                if (!handle.cancelled) {
                    val ready = result as? LinkCheck.Result.Ready
                    repo.noted(offer.addon, AddonCheck.fromLinkCheck(ready?.link?.host, (result as? LinkCheck.Result.Failed)?.failure))
                }
                if (!op.live) return@post
                op.handle = null
                repo.lastAddAt = repo.elapsed()
                when (result) {
                    is LinkCheck.Result.Ready -> {
                        // Handed to the player: Next source goes on from it now, and never names it again.
                        opened = source
                        visited += source.key
                        triedFiles.add(source)
                        op.final(Opened.Ready(source, offer, result.link, startMs(near), near))
                    }
                    is LinkCheck.Result.Failed -> {
                        var f = result.failure
                        if (f.kind == FailureKind.FILE && Notices.isCopyright(f.detail) && justAdded) {
                            f = f.copy(kind = FailureKind.JUST_ADDED)
                        }
                        if (source.key in fetchAgreed && f.kind == FailureKind.NOT_READY) {
                            tries += Attempt(source, repo.wall(), currentWhy, f)
                            skip(source, hash = true)
                            listener?.invoke()
                            op.final(Opened.Stopped(f, FailureWords.FETCHING))
                            return@post
                        }
                        // Not about the file: the other add-on's link for the same file, once.
                        if (f.kind in OTHER_OFFER && i + 1 < offers.size) {
                            if (f.kind == FailureKind.ADDON_DOWN || f.kind == FailureKind.CONFIG) downAddons += offer.addon
                            tryOffer(op, source, offers, i + 1, why, near)
                            return@post
                        }
                        follow(op, source, why, near, failed(source, f), f)
                    }
                }
            }
        }
    }

    private fun follow(op: Op, source: PlaybackSource, why: OpenWhy, near: Boolean, step: Step, f: Failure) {
        when (step) {
            Step.TryNext, Step.DecodeOnDevice -> pick(op, OpenWhy.FAILED)
            is Step.Retry -> {
                val again = Runnable {
                    op.timer = null
                    check(op, source, why, near, countTry = false)
                }
                op.timer = again
                repo.main.postDelayed(again, step.afterMs)
            }
            Step.Refresh -> refresh(op, source, f.addon ?: source.offers.first().addon, why, near)
            is Step.Stop -> op.final(Opened.Stopped(f, step.message))
        }
    }

    /** An expired link: ask [addon] again for the title and check the same release once. */
    private fun refresh(op: Op, source: PlaybackSource, addon: Addon, why: OpenWhy, near: Boolean) {
        repo.dropAnswer(target, addon)
        var handled = false
        var sub: Cancel? = null
        val made = repo.lookUp(target, LookupWhy.PLAY, addon) { l ->
            if (handled || !op.live || l.states[addon]?.final != true) return@lookUp
            handled = true
            sub?.cancel()
            val again = l.sources.firstOrNull { s -> s.key == source.key && s.offers.any { it.addon == addon } }
            if (again != null) {
                check(op, preferAddon(again, addon), why, near, countTry = false)
            } else {
                skip(source, hash = false)
                pick(op, OpenWhy.FAILED)
            }
        }
        sub = made
        if (handled) made.cancel() else op.pending += made
    }

    /** Where to start: the request's position on the first start (5 s early when it isn't the same file); else here. */
    private fun startMs(near: Boolean): Long? {
        if (startedOnce) return null
        return when (val s = request.start) {
            StartAt.Beginning -> 0L
            is StartAt.At -> if (near) (s.positionMs - BACK_MS).coerceAtLeast(0L) else s.positionMs
        }
    }

    companion object {
        const val MAX_START_TRIES = 3
        const val MAX_SWITCHES = 2
        const val SWITCH_GAP_MS = 3 * 60_000L
        const val MAX_WRONG_GUESSES = 2
        const val JUST_ADDED_WAIT_MS = 30_000L
        /** A "copyright" refusal this soon after FirePlayer opened another link may be the debrid service's add pause. */
        const val JUST_ADDED_WINDOW_MS = 5 * 60_000L
        const val RATE_WAIT_MS = 30_000L
        const val UNKNOWN_WAIT_MS = 2_000L
        /** A switch, or a resume on another file, starts this much earlier ("Resumed near …"). */
        const val BACK_MS = 5_000L
        /** A Continue card's resume asks the other add-ons this long after its copy started ([widenLater]). */
        const val WIDEN_AFTER_MS = 15_000L

        private val OTHER_OFFER = setOf(
            FailureKind.ADDON_DOWN, FailureKind.CONFIG, FailureKind.LINK_EXPIRED, FailureKind.UNKNOWN, FailureKind.SLOW,
        )
    }
}
