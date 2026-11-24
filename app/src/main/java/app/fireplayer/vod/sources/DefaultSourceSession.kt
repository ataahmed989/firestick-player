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
 * One play of a film or episode, stages 1-4 (design-brief §6.5). Picks the source (the release played last, then the
 * same release from the other add-on, then the closest match "Resumed near"; a row chosen in Sources; the same kind as
 * the previous episode; else "Best for this TV"), checks its link ([LinkCheck]), and on a failure follows the table
 * ([failed]): skip the file and its copies in every add-on, refresh an expired link once, decode the sound on the Stick,
 * use the other add-on, or stop and say why. Limits: 3 start tries and 2 automatic switches per film or episode, at
 * least 3 minutes apart; a source not cached is never opened without [fetch], asked once. Main thread.
 */
internal class DefaultSourceSession(
    override val request: PlayRequest,
    private val repo: DefaultSourcesRepository,
) : SourceSession {

    private val target: SourceTarget = request.target

    override var list: SourceList? = request.list
        private set

    private val tries = ArrayList<Attempt>()
    override val attempts: List<Attempt> get() = tries.toList()

    private val watch = Stalls()
    override val stalls: StallWatch get() = watch

    private var listener: (() -> Unit)? = null
    private var listSub: Cancel? = null
    private var listOnly: Addon? = null
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

    private val skipped = HashSet<String>()
    private val skippedHashes = HashSet<String>()
    private val badFormats = HashSet<String>()
    private val downAddons: EnumSet<Addon> = EnumSet.noneOf(Addon::class.java)
    private val retried = HashSet<String>()
    private val refreshed = HashSet<String>()
    private val decoded = HashSet<String>()
    private val visited = LinkedHashSet<String>()
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
        visited += source.key
        tries += Attempt(source, repo.wall(), currentWhy, null)
        // The file that played: a merged row's offer can be another re-upload than the row's first.
        releaseOf(source, offer)?.let { release ->
            runCatching { repo.library.recordRelease(target.title, target.episode, LastRelease(offer.addon, release, repo.wall())) }
        }
        val now = repo.elapsed()
        watch.reset()
        watch.playing(now)
        watch.jumped(now)
        listener?.invoke()
    }

    override fun failed(source: PlaybackSource, failure: Failure): Step {
        tries += Attempt(source, repo.wall(), currentWhy, failure)
        val step = stepFor(source, failure)
        listener?.invoke()
        return step
    }

    override fun failureOf(errorCode: Int, httpStatus: Int?): Failure =
        PlayerErrors.failureOf(errorCode, httpStatus, repo.online(), playingOffer?.addon)

    override fun online(): Boolean = repo.online()

    override fun maySwitch(now: Long): Boolean =
        startedOnce && switches < MAX_SWITCHES && now - lastSwitchAt >= SWITCH_GAP_MS

    override fun lighter(maxMbps: Double): PlaybackSource? {
        val current = playing?.key
        // In "Best for this TV" order: the first that fits is the best that fits. One this TV likely can't play only
        // when nothing else does.
        val fits = (list ?: return null).sources.filter { s ->
            val avg = s.facts.avgMbps
            s.automatic && usable(s) && s.key != current && avg != null && avg > 0.0 && avg <= maxMbps
        }
        return fits.firstOrNull { it.fit != TvFit.LIKELY_NOT } ?: fits.firstOrNull()
    }

    override fun lookUpNext(next: EpisodeRef, onList: (SourceList) -> Unit): Cancel =
        repo.lookUp(SourceTarget(target.title, next), LookupWhy.NEXT_EPISODE, null, onList)

    override fun close() {
        if (closed) return
        closed = true
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
        FailureKind.AUDIO -> if (decoded.add(source.key)) Step.DecodeOnDevice else {
            skip(source, hash = false)
            Step.TryNext
        }
        FailureKind.LINK_EXPIRED -> if (refreshed.add(source.key)) Step.Refresh else {
            skip(source, hash = false)
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
        FailureKind.SLOW -> {
            skip(source, hash = false)
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

    private fun skip(source: PlaybackSource, hash: Boolean) {
        skipped += source.key
        if (hash) (triedOffer[source.key]?.let { releaseOf(source, it) } ?: source.release)?.let { skippedHashes += it.infoHash }
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
            // in it plays by itself; the add-on still looking joins the list as it answers (Sources, Next source).
            waitFor(op, { l -> l.complete || l.sources.any { it.automatic && usable(it) } }) { pick(op, OpenWhy.START) }
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

    /** The row holding the release played last, while it plays without asking (cached, not skipped). */
    private fun findPreferred(l: SourceList?): PlaybackSource? {
        val p = request.preferred ?: return null
        val row = repo.rowKeyOf(target, p.release)
        return l?.sources?.firstOrNull { (it.release == p.release || it.key == row) && it.automatic && usable(it) }
    }

    // ---- Picking ----

    private fun usable(s: PlaybackSource): Boolean =
        s.key !in skipped && (s.release?.infoHash?.let { it !in skippedHashes } ?: true) &&
            s.offers.any { openable(s, it) } && formatKey(s) !in badFormats

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
        val current = playing?.key
        val auto = l.sources.filter { it.automatic && usable(it) && it.key != current }
        if (firstPick && !startedOnce) {
            request.chosen?.let { c ->
                val fresh = l.sources.firstOrNull { it.key == c.key } ?: c
                if (usable(fresh)) return fresh to false
            }
            request.preferred?.let { p ->
                val near = request.start is StartAt.At
                findPreferred(l)?.let { row ->
                    // The same file first, then its add-on; another re-upload in the row is only "near".
                    val ordered = row.copy(offers = row.offers.sortedWith(
                        compareBy({ if (releaseOf(row, it) == p.release) 0 else 1 }, { if (it.addon == p.addon) 0 else 1 }),
                    ))
                    val same = ordered.offers.firstOrNull { openable(ordered, it) }?.let { releaseOf(ordered, it) == p.release } == true
                    return ordered to (near && !same)
                }
                // Gone, or no longer cached: the closest ready match, "Resumed near …".
                val sameAddon = auto.firstOrNull { p.addon in it.addons }
                return (sameAddon ?: auto.firstOrNull()) to near
            }
            request.like?.let { like -> SourceRank.mostLike(auto, like)?.let { return it to false } }
            return auto.firstOrNull() to false
        }
        // Next source and switches go down "Best for this TV" from the copy playing, its own group first
        // ([SourceRank.nextOrder]), skipping what failed here: first what hasn't played in this session.
        val next = SourceRank.nextOrder(l.sources, current, target.episode == null).filter { usable(it) }
        val fresh = next.firstOrNull { it.key !in visited } ?: next.firstOrNull()
        return fresh to startedOnce
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
                    is LinkCheck.Result.Ready ->
                        op.final(Opened.Ready(source, offer, result.link, startMs(near), near))
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

        private val OTHER_OFFER = setOf(
            FailureKind.ADDON_DOWN, FailureKind.CONFIG, FailureKind.LINK_EXPIRED, FailureKind.UNKNOWN, FailureKind.SLOW,
        )
    }
}
