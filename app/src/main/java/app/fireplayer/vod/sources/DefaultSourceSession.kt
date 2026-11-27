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
import java.util.IdentityHashMap

/**
 * One play of a film or episode, stages 1-4 (design-brief §6.5). Picks the source (on a resume, the very file played
 * last, from its add-on first, whatever the ranking says: by its release, or a copy of no release known by its key; a
 * row chosen in Sources; the same kind as the previous episode; else "Best for this TV"), checks its link
 * ([LinkCheck]), and on a failure follows the table
 * ([failed]): skip the file and its copies in every add-on, refresh an expired link once, decode the sound on the Stick,
 * use the other add-on, or stop and say why. Limits: 3 start tries and 2 automatic switches per film or episode, at
 * least 3 minutes apart; a source not cached is never opened without [fetch], asked once. Main thread.
 */
internal class DefaultSourceSession(
    override val request: PlayRequest,
    private val repo: DefaultSourcesRepository,
    /**
     * Only picks, for a link checked ahead ([LinkPrep]: the viewer rests on what OK plays): the copy and its first offer
     * its play would check are handed here in place of checking (nothing else follows; its look-ups are
     * [LookupWhy.AHEAD]'s, and nothing is kept in the engine). Null for a play.
     */
    private val pickOnly: ((PlaybackSource, Offer) -> Unit)? = null,
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
     * The copy playing as it was kept as the title's last ([started]: [LastRelease.key], its release or, of no release
     * known, its copy key), and the one kept before it: put back when it turns out not to be the whole film
     * ([FailureKind.NOT_WHOLE]).
     */
    private var keptFor: String? = null
    private var releaseBefore: LastRelease? = null
    /** Copies chosen by hand that turned out not to be the whole film ([notWhole]): never kept as the title's last release. */
    private val notWholeKeys = HashSet<String>()
    /**
     * Another episode with no copy of the kind the show followed ([PlayRequest.like]): at its first frame the show's
     * remembered copies are forgotten ([app.fireplayer.vod.PlaybackLibrary.forgetReleases]), so the ranking decides on.
     */
    private var kindLost = false

    /**
     * The rows not tried again in this play, by key, each with the file its try opened (null: none known). A row under
     * that key that doesn't hold that file any more (a play measured it apart from its row's other add-on since) isn't
     * this one ([skippedRow]).
     */
    private val skipped = HashMap<String, ReleaseKey?>()
    private val skippedHashes = HashSet<String>()
    private val badFormats = HashSet<String>()
    private val downAddons: EnumSet<Addon> = EnumSet.noneOf(Addon::class.java)
    private val retried = HashSet<String>()
    private val refreshed = HashSet<String>()
    private val decoded = HashSet<String>()
    /** The rows handed to the player or played whose file isn't known ([triedFiles] knows the others). */
    private val visitedBare = HashSet<String>()
    /**
     * The files handed to the player or played in this session, by what they are ([FileSet]: the offer's own file, not
     * its row's others): their rows may have merged, parted or changed keys since. A copy left while it loaded counts:
     * Next source never names it again.
     */
    private val triedFiles = FileSet(episode, ::sizeNow)
    /** The files left because they can't play here ([skip] with its file: no picture, not whole, slow, link dead). */
    private val skippedFiles = FileSet(episode, ::sizeNow)
    /** Why the resume didn't take the copy it left ([resumeMiss]); null when it did, or there was none. */
    private var resumeMissed: String? = null
    private val fetchAgreed = HashSet<String>()
    /** The offer last tried for each source (by key): its own file is the one skipped when it fails. */
    private val triedOffer = HashMap<String, Offer>()
    /**
     * The offer each copy handed out was last tried or played with, by the very object ([Opened.Ready]: the player hands
     * it back). A row's key can hold another file since (it parted from another add-on's file, or was keyed anew), so a
     * key alone can't say which file a copy opened ([offerOf]). A few entries a play.
     */
    private val handedOffer = IdentityHashMap<PlaybackSource, Offer>()
    /** Each copy that showed its first frame in this play, with the offer it played from ([playedLast]). */
    private val starts = ArrayList<Pair<PlaybackSource, Offer>>()
    private val unknownBy = EnumMap<Addon, MutableSet<String>>(Addon::class.java)
    private val ops = ArrayList<Op>()

    /** This play's first link check asked for a link checked ahead ([LinkPrep.claim]); only that one ever does. */
    private var aheadAsked = false
    /** The first link came checked ahead ([LinkPrep]), in plain words for the start's line ([aheadNote]); null: it didn't. */
    private var aheadWords: String? = null

    /** A copy handed out with a link checked ahead and kept a while ([LinkPrep]), and where it was to start ([near]). */
    private class Ahead(val source: PlaybackSource, val offer: Offer, val near: Boolean)

    /** The copy whose link was kept ahead, until its first frame or a failure ([failed]). */
    private var keptAhead: Ahead? = null
    /** That link turned out refused: the player's refresh checks the same copy afresh ([refresh]). */
    private var aheadAgain: Ahead? = null

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
            source != null -> {
                check(op, source, why, near = startedOnce, countTry = true)
                watchFromNow()
            }
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
        if (!closed) watchFromNow()
        return op
    }

    /**
     * A row handed in to open (chosen in a Sources window: the title page's, started at OK by [app.fireplayer.vod.EarlyOpen],
     * or the player's): the list it came from is kept current from now on, as for a pick of the session's own
     * ([startFirst]). Before, such a play kept the list handed over at OK all through: what the play measured was ranked
     * into the engine's list, never into the player's Sources window, its Playing row or Next source. Called after its
     * link check is sent: io runs in order, so that check never waits behind the add-ons' look-ups the watch may send.
     */
    private fun watchFromNow() {
        if (listSub == null) watchList(null)
    }

    /**
     * Every add-on's copies, asked again where an answer was dropped since the watch began: a look-up past an answer's
     * time drops it ([DefaultSourcesRepository.lookUp]), and a refresh's look-up asks only its own add-on, so the
     * others' rows would stay out of [list] (and it never complete) for the rest of the play. Nothing in [list] or being
     * asked is asked again.
     */
    private fun watchAll() {
        listSub?.cancel()
        listSub = null
        watchList(null)
    }

    override fun refresh(source: PlaybackSource, addon: Addon?, onOpened: (Opened) -> Unit): Cancel {
        val op = Op(onOpened)
        ops += op
        val from = addon ?: offerOf(source)?.addon ?: source.offers.firstOrNull()?.addon
        val again = aheadAgain?.takeIf { it.source.key == source.key }
        aheadAgain = null
        when {
            closed -> op.final(Opened.NoneLeft(FailureWords.NONE_READY))
            // The link kept ahead was refused: the same copy, its offers in the same order, checked now as if none had
            // been kept (no new look-up, and not the table's one refresh).
            again != null -> check(op, again.source, OpenWhy.START, again.near, countTry = false)
            from == null -> {
                skip(source, hash = false)
                pick(op, OpenWhy.FAILED)
            }
            else -> refresh(op, source, from, OpenWhy.FAILED, near = startedOnce)
        }
        return op
    }

    override fun started(source: PlaybackSource, offer: Offer) {
        // Its film has begun: rests are no longer held back for this play's start ([LinkPrep.handOver]).
        if (!startedOnce) repo.prep.ended(this)
        startedOnce = true
        firstPick = false
        keptAhead = null
        playing = source
        playingOffer = offer
        opened = source
        handedOffer[source] = offer
        tried(source, offer)
        tries += Attempt(source, repo.wall(), currentWhy, null)
        starts += source to offer
        if (kindLost) {
            kindLost = false
            runCatching { repo.library.forgetReleases(target.title) }
        }
        // The copy that played, kept as the one the title was left on in place of what was kept before: its file (a
        // merged row's offer can be another re-upload than the row's first), or a copy of no release known by its key
        // ([lastOf]), so Resume and a Continue card play it again, also after a restart.
        lastOf(source, offer)?.takeIf { source.key !in notWholeKeys }?.let { last ->
            // What was kept before this copy (not this copy itself), put back if it isn't the whole film ([putBack]).
            if (last.key != keptFor) {
                keptFor = last.key
                releaseBefore = runCatching { repo.library.lastRelease(target.title, target.episode) }.getOrNull()
                    ?.takeIf { it.key != last.key }
            }
            runCatching { repo.library.recordRelease(target.title, target.episode, last) }
        }
        // A copy found not to be the whole film is no Last used, as it isn't kept above ([notWhole]).
        repo.usedNow(target, source.key, releaseOf(source, offer), copyOf(source, offer)?.takeIf { source.key !in notWholeKeys })
        val now = repo.elapsed()
        watch.reset()
        watch.playing(now)
        watch.jumped(now)
        widenLater()
        listener?.invoke()
    }

    override fun failed(source: PlaybackSource, failure: Failure): Step {
        // A link checked ahead and kept a while ([LinkPrep]) that the player couldn't open before its first frame: it
        // may have gone stale. Nothing is counted against the copy: its link is checked afresh, once, as if none had been
        // kept (the player's refresh: [refresh]); anything after that follows the table as ever.
        keptAhead?.let { a ->
            keptAhead = null
            if (!startedOnce && failure.kind in AHEAD_STALE && a.source.key == source.key && offerOf(source) === a.offer) {
                aheadAgain = a
                aheadWords = "$aheadWords, refused by then: checked again"
                return Step.Refresh
            }
        }
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

    override fun rowKey(source: PlaybackSource): String = rowKey(source, offerOf(source))

    /** [rowKey] of [source] as [offer] opened it (null: its row's own file). */
    private fun rowKey(source: PlaybackSource, offer: Offer?): String {
        val file = offer?.let { releaseOf(source, it) } ?: source.release
        file?.let { repo.rowKeyOf(target, it) }?.let { return it }
        // A copy of no release known: the row holding it by its key ([Offer.copyKey]; it may have joined another
        // add-on's row of its size to the byte since a play measured it).
        if (file == null) offer?.let { copyOf(source, it) }?.let { repo.rowKeyOf(target, it) }?.let { return it }
        // Its release is in no row now (the answer that had it is gone): the row holding the same file, if one does (by
        // what the offer tried says of it: [FileSet]).
        val l = list ?: return source.key
        if (l.sources.any { it.key == source.key }) return source.key
        val same = filesOf(source, offer)
        return l.sources.firstOrNull { same.holds(it) }?.key ?: source.key
    }

    override fun nextPick(): PlaybackSource? = list?.let { if (closed) null else nextOf(it) }

    override fun resumeMiss(): String? = resumeMissed

    override fun aheadNote(): String? = aheadWords

    override fun opensLeft(s: PlaybackSource, o: Offer): Boolean {
        val p = request.preferred ?: return false
        if (p.openedBy(s, o)) return true
        // A copy of no release known: the same file from another add-on, by its size to the byte ([CopyLeft]).
        return p.copyKey != null && CopyLeft.sameFile(p, s, o, leftBytes ?: return false)
    }

    /**
     * The size to the byte of the copy a resume left, a copy of no release known, as a play here measured it
     * ([CopyLeft.bytes]): read once, and only when its own offer isn't the one played; null for a release.
     */
    private val leftBytes: Long? by lazy(LazyThreadSafetyMode.NONE) {
        CopyLeft.bytes(request.preferred, target.streamId, repo::measuredSize)
    }

    override fun lookUpNext(next: EpisodeRef, onList: (SourceList) -> Unit): Cancel =
        repo.lookUp(SourceTarget(target.title, next), LookupWhy.NEXT_EPISODE, null, onList)

    override fun widen() {
        if (closed) return
        // Every add-on watched and none dropped since ([watchAll]): nothing to ask.
        if (listOnly == null && list?.states?.values?.none { it is AddonState.NotAsked } != false) return
        widening?.let { repo.main.remove(it) }
        widening = null
        watchAll()
    }

    override fun close() {
        if (closed) return
        closed = true
        repo.prep.ended(this)
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
        FailureKind.AUDIO -> if (decoded.add(fileKey(source))) Step.DecodeOnDevice else {
            skip(source, hash = false)
            Step.TryNext
        }
        FailureKind.LINK_EXPIRED -> if (refreshed.add(fileKey(source))) Step.Refresh else {
            skip(source, hash = false, file = true)
            Step.TryNext
        }
        FailureKind.ADDON_DOWN, FailureKind.CONFIG -> {
            downAddons += f.addon ?: source.offers.firstOrNull()?.addon ?: Addon.MEDIAFUSION
            if (source.offers.none { openable(source, it) }) skip(source, hash = false)
            Step.TryNext
        }
        FailureKind.JUST_ADDED -> if (retried.add("j|${fileKey(source)}")) Step.Retry(JUST_ADDED_WAIT_MS) else {
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
        FailureKind.UNKNOWN -> if (retried.add("u|${fileKey(source)}")) Step.Retry(UNKNOWN_WAIT_MS) else {
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

    /**
     * [source] is the copy playing, and it is the one kept as the title's last ([started]): its file, or a copy of no
     * release known by its key.
     */
    private fun keptAsLast(source: PlaybackSource): Boolean =
        keptFor != null && source.key == playing?.key && playingOffer?.let { lastOf(source, it) }?.key == keptFor

    /**
     * The copy that started last wasn't the whole film: the one kept before it is the title's again (none: it's
     * forgotten), so a resume goes on from the copy that was playing before, and the list's Last used is that one once it
     * ranks again ([DefaultSourcesRepository.rerank]).
     */
    private fun putBack() {
        val before = releaseBefore
        keptFor = null
        releaseBefore = null
        runCatching {
            if (before != null) repo.library.recordRelease(target.title, target.episode, before)
            else repo.library.forgetRelease(target.title, target.episode)
        }
    }

    /**
     * [source]'s row isn't tried again in this play; with [hash], nothing of its torrent; with [file], its file in no
     * row (the file of the offer tried: another add-on's file in its row may be told apart from it since, [FileSet]).
     */
    private fun skip(source: PlaybackSource, hash: Boolean, file: Boolean = false) {
        val offer = offerOf(source)
        val tried = offer?.let { releaseOf(source, it) } ?: source.release
        skipped[source.key] = tried
        if (hash) tried?.let { skippedHashes += it.infoHash }
        if (file) skippedFiles.add(source, offer)
    }

    /** [s]'s row was skipped in this play ([skip]): its key, while it still holds the file that try opened. */
    private fun skippedRow(s: PlaybackSource): Boolean {
        if (!skipped.containsKey(s.key)) return false
        val file = skipped[s.key] ?: return true
        return s.release == file || s.offers.any { releaseOf(s, it) == file }
    }

    /** [source] was handed to the player from [offer] ([visited]): its file, by what it is. */
    private fun tried(source: PlaybackSource, offer: Offer) {
        if (releaseOf(source, offer) == null) visitedBare += source.key
        triedFiles.add(source, offer)
    }

    /** The file [o] opens: its own, else its row's. */
    private fun releaseOf(s: PlaybackSource, o: Offer) = o.release ?: s.release

    /** [o]'s copy's key when no release of it is known ([Offer.copyKey]); null otherwise. */
    private fun copyOf(s: PlaybackSource, o: Offer): String? = if (releaseOf(s, o) == null) o.copyKey else null

    /**
     * [o] of [s] as the copy a title is left on ([app.fireplayer.vod.PlaybackLibrary.recordRelease]): its own release;
     * else, an offer whose add-on gave no info hash, by its own key ([Offer.copyKey]): a resume then finds it in its
     * add-on's answer alone, as a Continue card's fast start asks, and its file from another add-on by its size to the
     * byte as a play here measured it ([CopyLeft]); also in a row it joined since (another add-on's file of that size).
     * In a release's row while no play here measured it ([measuredHere]: it joined by what the add-ons say), that release,
     * as before: the same file then still resumes from another add-on while its own fails. Null when none.
     */
    private fun lastOf(s: PlaybackSource, o: Offer): LastRelease? {
        o.release?.let { return LastRelease(o.addon, it, repo.wall()) }
        val r = s.release
        o.copyKey?.takeIf { r == null || measuredHere(it) }?.let { return LastRelease.ofCopy(o.addon, it, repo.wall()) }
        return r?.let { LastRelease(o.addon, it, repo.wall()) }
    }

    /**
     * Whether a play here measured the file of the copy of [copyKey] to the byte ("quality_seen"), read once a play: it
     * may be measured while it plays, and [lastOf] says the same of it all through ([keptAsLast]). Only for a copy of no
     * release known in a release's row.
     */
    private fun measuredHere(copyKey: String): Boolean = measuredCopies.getOrPut(copyKey) { repo.measuredSize(copyKey) != null }

    private val measuredCopies = HashMap<String, Boolean>(2)

    /**
     * The offer [s] was handed out with (tried last, or played: [handedOffer]); for another object of its row, the one
     * the copy playing plays from, else the one tried last under its key, while [s] still offers it (a row under that key
     * may hold another file since: it parted from another add-on's file, or was keyed anew). Null when none was.
     */
    private fun offerOf(s: PlaybackSource): Offer? {
        handedOffer[s]?.let { return it }
        if (s.key == playing?.key) playingOffer?.takeIf { offers(s, it) }?.let { return it }
        return triedOffer[s.key]?.takeIf { offers(s, it) }
    }

    /** [s] offers [o]'s file from [o]'s add-on (a copy of no release known: that very copy, [Offer.copyKey]). */
    private fun offers(s: PlaybackSource, o: Offer): Boolean =
        s.offers.any { it.addon == o.addon && it.release == o.release && it.copyKey == o.copyKey }

    /**
     * What the once-only steps of the failure table count by ([decoded], [refreshed], [retried]): the file [s] opened, not
     * its row's key (another file may be under it since); its key when the file isn't known.
     */
    private fun fileKey(s: PlaybackSource): String = (offerOf(s)?.let { releaseOf(s, it) } ?: s.release)?.storeKey ?: s.key

    /** [source] is the file playing (its row and the offer it plays from), not another under its key. */
    private fun isPlaying(source: PlaybackSource): Boolean =
        source.key == playing?.key && playingOffer?.let { offers(source, it) } != false

    /** [s]'s file as [o] opens it ([FileSet.add]), sizes as the list has them now. */
    private fun filesOf(s: PlaybackSource, o: Offer?): FileSet = FileSet(episode, ::sizeNow).add(s, o)

    /**
     * The size to the byte of the file [r] as the list has it now ([FileSet.sizeOf]: measured here since, perhaps); null
     * when it isn't listed or isn't known. Only for rows of one torrent ([FileSet.holds]).
     */
    private fun sizeNow(r: ReleaseKey): Long? {
        for (s in list?.sources ?: return null) {
            val o = s.offers.firstOrNull { releaseOf(s, it) == r } ?: continue
            return FileSet.sizeOf(s, o)
        }
        return null
    }

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
            // first waits for the add-on of the copy left, as a Continue card does: that file plays once it's listed (a
            // copy of no release known: or its file from another add-on, [findPreferred]).
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
        listSub = repo.lookUp(target, if (pickOnly != null) LookupWhy.AHEAD else LookupWhy.PLAY, only) { l ->
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
     * The row holding the very file played last (same info hash and file index, any add-on; a copy of no release known:
     * the offer of that very copy key, in its own row or the row it joined since: [LastRelease.openedBy]), while that file
     * plays without asking (cached, not skipped, not one this Stick showed no picture for or found not the whole film);
     * the ranking doesn't matter. A copy of no release known whose own offer doesn't (its add-on failing, slow, or listing
     * it not cached): the row offering its file from another add-on, the same to the byte ([opensLeft]), under the same
     * rules.
     */
    private fun findPreferred(l: SourceList?): PlaybackSource? {
        val p = request.preferred ?: return null
        val rows = l?.sources ?: return null
        fun row(opens: (PlaybackSource, Offer) -> Boolean) = rows.firstOrNull { s ->
            usable(s) && !setAsideHere(s) && s.offers.any { opens(s, it) && openable(s, it) }
        }
        return row(p::openedBy) ?: if (p.copyKey != null) row(::opensLeft) else null
    }

    // ---- Picking ----

    /**
     * [s] may be tried: not skipped in this play (its row, a torrent of any of its offers, or its file in another row:
     * [skippedFiles]), something in it may be opened, and its format didn't fail here.
     */
    private fun usable(s: PlaybackSource): Boolean =
        !skippedRow(s) && !hashSkipped(s) && !skippedFiles.holds(s) &&
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
     * The [current] copy's file, by what it is ([FileSet]): the file of the offer it opened, and the row holding it in
     * [l] now (merged since, or measured: its size to the byte); null when there's none. Not its row's other offers as
     * they were: a play may have told them apart since.
     */
    private fun currentFiles(l: SourceList?): FileSet? {
        val s = current() ?: return null
        val files = filesOf(s, offerOf(s))
        val key = rowKey(s)
        l?.sources?.firstOrNull { it.key == key }?.let { files.add(it) }
        return files
    }

    /**
     * Whether [s] was handed to the player or played in this session: its file ([triedFiles]: the offer's own, in a row
     * it has joined or been keyed under since, never another add-on's file a play has told apart from it); a row whose
     * file isn't known, by its key.
     */
    private fun visited(s: PlaybackSource): Boolean = triedFiles.holds(s) || s.key in visitedBare

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
        for ((s, offer) in starts.asReversed()) {
            val key = rowKey(s, offer)
            val files = filesOf(s, offer)
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
            // failed), or its answer was dropped since (a refresh past its time): the rest is waited for before giving up.
            if (listOnly != null || !l.complete) {
                watchAll()
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
                // That very file first, from its add-on first: the timeline left. A copy of no release known: the offer of
                // its key, then its file from another add-on ([opensLeft]), never another file of a row it joined first.
                return row.copy(offers = row.offers.sortedWith(
                    compareBy({ if (p.openedBy(row, it)) 0 else if (opensLeft(row, it)) 1 else 2 }, { if (it.addon == p.addon) 0 else 1 }),
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
        val rows = l.sources.filter { s -> s.offers.any { opensLeft(s, it) } }
        val s = rows.firstOrNull() ?: return when {
            // A ×N row keeps one offer per add-on: another upload of the same release is offered for it.
            p.release?.let { repo.rowKeyOf(target, it) } != null -> "its row offers another upload of that release"
            p.copyKey?.let { repo.rowKeyOf(target, it) } != null -> "its row offers another of ${p.addon.label}'s copies"
            l.states[p.addon]?.final != true -> "not listed yet (${p.addon.label} still looking)"
            else -> "not listed any more"
        }
        // Of the offers of that file, one cached says what else kept it from playing.
        val left = s.offers.filter { opensLeft(s, it) }
        val o = left.firstOrNull { it.readiness != Readiness.NOT_READY } ?: left.first()
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
        // Picking only (a link checked ahead): nothing to play by itself, and nothing is asked or kept for the play.
        if (pickOnly != null) {
            op.final(Opened.NoneLeft(FailureWords.NONE_READY))
            return
        }
        if (!accountProblem && target.key !in repo.fetchAsked) {
            val candidates = l.sources.filter {
                it.readiness == Readiness.NOT_READY && it.group != SourceGroup.CINEMA && !skippedRow(it) &&
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
            if (startedOnce && (why == OpenWhy.STALLS || why == OpenWhy.FAILED) && !isPlaying(source)) {
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
        // Picking only: the copy and the offer its play would check first, for a link checked ahead ([LinkPrep]).
        pickOnly?.let { found ->
            op.cancel()
            found(source, offers[0])
            return
        }
        op.progress(Opened.Checking(source))
        tryOffer(op, source, offers, 0, why, near)
    }

    private fun tryOffer(op: Op, source: PlaybackSource, offers: List<Offer>, i: Int, why: OpenWhy, near: Boolean) {
        val offer = offers[i]
        triedOffer[source.key] = offer
        handedOffer[source] = offer
        val confirmed = source.measured?.spec != null
        val justAdded = repo.elapsed() - repo.lastAddAt < JUST_ADDED_WINDOW_MS
        // This play's first check: that very offer's link may have been checked ahead as the viewer rested on what OK
        // plays ([LinkPrep]), kept (taken now), or still being checked (joined: never sent twice).
        if (!aheadAsked) {
            aheadAsked = true
            val joined = repo.prep.claim(target, offer) { result, began, readyAt ->
                if (!op.live) return@claim
                op.handle = null
                aheadWords = aheadWords(began, readyAt)
                if (readyAt != null && result is LinkCheck.Result.Ready) keptAhead = Ahead(source, offer, near)
                checked(op, source, offers, i, why, near, justAdded, result)
            }
            if (joined != null) {
                op.handle = joined
                return
            }
        }
        val handle = CallHandle()
        op.handle = handle
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
                checked(op, source, offers, i, why, near, justAdded, result)
            }
        }
    }

    /** [offers]'s [i]th link was checked ([result]): handed to the player, or the failure table. */
    private fun checked(
        op: Op, source: PlaybackSource, offers: List<Offer>, i: Int, why: OpenWhy, near: Boolean, justAdded: Boolean,
        result: LinkCheck.Result,
    ) {
        val offer = offers[i]
        when (result) {
            is LinkCheck.Result.Ready -> {
                // Handed to the player: Next source goes on from it now, and never names it again.
                opened = source
                tried(source, offer)
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
                    return
                }
                // Not about the file: the other add-on's link for the same file, once.
                if (f.kind in OTHER_OFFER && i + 1 < offers.size) {
                    if (f.kind == FailureKind.ADDON_DOWN || f.kind == FailureKind.CONFIG) downAddons += offer.addon
                    tryOffer(op, source, offers, i + 1, why, near)
                    return
                }
                follow(op, source, why, near, failed(source, f), f)
            }
        }
    }

    /**
     * The start line's words for a first link checked ahead ([LinkPrep]) whose check began at [began] and, when it was
     * kept, answered at [readyAt] (elapsedRealtime ms; null: it was joined while being checked), against OK's press.
     */
    private fun aheadWords(began: Long, readyAt: Long?): String {
        val ok = request.pressedAt
        return when {
            ok <= 0L -> if (readyAt != null) "link checked ahead" else "link check ahead joined"
            readyAt != null -> "link checked ahead: ready ${(ok - readyAt).coerceAtLeast(0L)} ms before OK"
            else -> "link check ahead joined: begun ${(ok - began).coerceAtLeast(0L)} ms before OK"
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

    /**
     * An expired link: ask [addon] again for the title and check the same release once: the row offering that very file
     * from [addon] now, by its file (its answer joins the list again last, so a merged row may be keyed by another add-on's
     * file now, or that file's row may have parted from it), not by the key it had.
     */
    private fun refresh(op: Op, source: PlaybackSource, addon: Addon, why: OpenWhy, near: Boolean) {
        val file = source.offers.firstOrNull { it.addon == addon }?.let { releaseOf(source, it) } ?: source.release
        val copy = if (file == null) source.offers.firstOrNull { it.addon == addon }?.copyKey else null
        repo.dropAnswer(target, addon)
        var handled = false
        var sub: Cancel? = null
        val made = repo.lookUp(target, LookupWhy.PLAY, addon) { l ->
            if (handled || !op.live || l.states[addon]?.final != true) return@lookUp
            handled = true
            sub?.cancel()
            val again = if (file == null) {
                // A copy of no release known: by its key ([Offer.copyKey]), else its row's.
                copy?.let { c -> l.sources.firstOrNull { s -> s.offers.any { it.addon == addon && it.copyKey == c } } }
                    ?: l.sources.firstOrNull { s -> s.key == source.key && s.offers.any { it.addon == addon } }
            } else {
                l.sources.firstOrNull { s -> s.offers.any { it.addon == addon && releaseOf(s, it) == file } }
                    // A ×N row keeps one offer per add-on: another upload of the same file from it.
                    ?: repo.rowKeyOf(target, file)?.let { k -> l.sources.firstOrNull { s -> s.key == k && s.offers.any { it.addon == addon } } }
            }
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

        /**
         * What the player says of a link kept ahead that may only have gone stale ([failed]): refused or gone (HTTP 401,
         * 403, 404, 410), a page or nothing readable where the film was, or an error of no known kind.
         */
        private val AHEAD_STALE = setOf(FailureKind.LINK_EXPIRED, FailureKind.FILE, FailureKind.UNKNOWN)
    }
}
