package app.fireplayer.vod.play

import app.fireplayer.vod.Addon
import app.fireplayer.vod.Cancel
import app.fireplayer.vod.EpisodeRef
import app.fireplayer.vod.Failure
import app.fireplayer.vod.FailureKind
import app.fireplayer.vod.Offer
import app.fireplayer.vod.OpenWhy
import app.fireplayer.vod.Opened
import app.fireplayer.vod.PlayOrigin
import app.fireplayer.vod.PlayRequest
import app.fireplayer.vod.PlaybackLibrary
import app.fireplayer.vod.PlaybackSource
import app.fireplayer.vod.Readiness
import app.fireplayer.vod.SourceList
import app.fireplayer.vod.SourceSession
import app.fireplayer.vod.SourceTarget
import app.fireplayer.vod.StartAt
import app.fireplayer.vod.Step
import app.fireplayer.vod.sources.SourceRank
import java.util.concurrent.ConcurrentHashMap

/**
 * The player's side of a play from the add-ons (design-brief.md §6.5, stages 2-4), so FirePlayer's player keeps its own
 * design and only calls in here: the loading screen's words while the link is checked, the first-load limit with Keep
 * waiting / Try next source, the stall watch and automatic switches, Next source and the Sources panel's picks, the
 * failure table's steps, the next episode, and what the library records. The [SourceSession] decides which source;
 * this says what the player does about it ([Host]).
 *
 * Links stay in [Opened.Ready.link] (memory only): handed to [Host.startSource], never kept, logged or shown here.
 * Main thread.
 */
class SourcePlayback(
    val request: PlayRequest,
    val session: SourceSession,
    private val library: PlaybackLibrary,
    private val ticker: Ticker,
    private val host: Host,
    /** The first open, begun when OK was pressed ([app.fireplayer.vod.PlayHandoff.startEarly]); null: [start] opens it. */
    private val early: app.fireplayer.vod.EarlyOpen? = null,
) {

    interface Host {
        /** The loading screen's line (stages 1 and 2): "Checking the link · 4K · DV · from Torrentio". */
        fun showProgress(text: String)

        /**
         * Play [ready]'s link from [startMs]: the first source ([switching] false: the player is built for it), or
         * another in place of the one playing ([switching] true).
         */
        fun startSource(ready: Opened.Ready, startMs: Long, switching: Boolean)

        /** A question with its answers ("Keep waiting" / "Try next source"); [onDismiss] when Back closes it unanswered. */
        fun ask(question: String, choices: List<Pair<String, () -> Unit>>, onDismiss: () -> Unit)

        /** Nothing more to try: say [message], and Back returns to the page (on its Sources button). */
        fun stopWith(message: String)

        /** A short note over the film ("Resumed near 1:02:03"). */
        fun notice(text: String)

        /** Where the film is now, ms (0 before it plays). */
        fun positionMs(): Long

        /** The same link again after [ms] (the failure table's retry). */
        fun retryAfter(ms: Long)

        /** The receiver refused the sound: decode it on the Stick for this play, same source. */
        fun decodeOnDevice()

        /** The next episode, from its own sources: a new play in place of this one. */
        fun playNext(next: PlayRequest)

        /**
         * The same link opened again where the film stands, on a new connection ([StallAid]). False when the player
         * can't just now (it's already retrying, or holding the film for something else).
         */
        fun reopen(): Boolean = false

        /** One line for the log about a recovery ("stall 9 s, nothing arriving: same source reopened"): no links, no titles. */
        fun log(text: String) = Unit

        /** Settings › Playback › When a film keeps stalling. */
        fun whenBuffering(): WhenBuffering = WhenBuffering.ASK

        /** What the player measured of the film's download ([FilmSupply.Reading]); null when it measured nothing. */
        fun supply(): FilmSupply.Reading? = null

        /** What the viewer answered for this film when it kept buffering before, as kept ([StallChoice.encode]); null when never asked. */
        fun stallChoice(): String? = null

        /** Keeps [choice] for this film ([StallChoice.encode]'s text). */
        fun rememberStallChoice(choice: String) = Unit

        /**
         * A film kept stalling while the download sustained [mbps], about what the film needs: kept, so "Best for this
         * TV" stops picking files this connection can't keep up with ([app.fireplayer.vod.sources.LineRate]).
         */
        fun lineShort(mbps: Double) = Unit

        /**
         * The copy playing isn't the whole film or episode ([reachedEnd], [lengthKnown]): kept with how it played
         * ([app.fireplayer.player.CopyHealth.notWhole]), so it isn't picked by itself again.
         */
        fun notWhole() = Unit

        /** The film's runtime in minutes, as the title's details give it; 0 when not known (the title's own is used). */
        fun runtimeMinutes(): Int = 0
    }

    /** The source playing (or being opened), and the add-on offer it came from. */
    var current: PlaybackSource? = null
        private set
    private var offer: Offer? = null
    /** The current source has shown its first frame (stage 4 from here; before it, stage 3). */
    var playing = false
        private set

    /** The current source plays, with no stall under way and no switch for stalls being opened (the Sources window's "smoothly"). */
    val smooth: Boolean get() = playing && !stalled && !stallSwitching
    private var everPlayed = false
    private var opening: Cancel? = null
    private var loadTimer: Cancel? = null
    private var nextLookup: Cancel? = null
    private var nextList: SourceList? = null
    private var nextLooked = false
    /** The switch limit's note was shown once. */
    private var limitNoted = false
    /** The next open plays from the beginning (a Sources row held: Play from beginning). */
    private var fromBeginning = false
    private var closed = false
    /** The play stopped with a message (nothing more is tried until a new source is ready). */
    private var stopped = false
    /** The player is loading while the source plays (a stall under way). */
    private var stalled = false
    /** A switch for stalls is being opened: the stall watch waits for its answer. */
    private var stallSwitching = false
    /** When the last switch for stalls was tried (it may have found nothing to switch to). */
    private var stallTryAt = Long.MIN_VALUE / 2
    /** A stall's first aid: the same source opened again before any switch ([StallAid]). */
    private val aid = StallAid()
    private var stalledAt = 0L
    /** A question about the stalls is on screen: the stall watch waits for its answer. */
    private var asking = false
    /** The source playing was found too heavy for the connection, and that was dealt with (asked, switched or noted). */
    private var supplyDone = false
    /**
     * The copies (by key) asked about their stalls in this play, by either question ([shortSupply], [askToLeave]): each
     * is asked once.
     */
    private val askedAbout = HashSet<String>()
    private var keepLogged = false
    /** Why a resume didn't take the copy it left was logged ([SourceSession.resumeMiss]). */
    private var resumeLogged = false
    /** The network is waited for (it dropped while the film played): the same link again once it's back ([Recovery]). */
    private var netWait: Cancel? = null
    /** Said when the next source starts, in place of "Resumed near …": why the one before was left ([noPicture]). */
    private var switchNote: String? = null
    /** Where the copy playing was started (ms): the film's place then, where the next copy starts if it isn't the whole film. */
    private var copyStartMs = 0L
    /** When the copy playing showed its first frame ([ticker] ms); null before it did. */
    private var copyFrameAt: Long? = null
    /** The next copy starts here: the one before wasn't the whole film ([giveWay]). */
    private var resumeAt: Long? = null
    /** The copy playing (or last opened) isn't the whole film: nothing of where it stands is recorded. */
    private var notWholeNow = false
    /** The copy playing's length was judged against the film's ([lengthKnown]). */
    private var lengthJudged = false
    /** The viewer jumped in the copy playing since its first frame: an end they jumped to is theirs ([PlayRules.copyEnd]). */
    private var copyJumped = false
    /** The copy the viewer chose in a Sources window: it may play though it's shorter than the film ([lengthKnown]). */
    private var chosenKey: String? = request.chosen?.key?.takeIf { request.origin == PlayOrigin.SOURCES_PANEL }

    /** The episode after this one (the page's, or found by the player), for the next episode's play; null for a film. */
    var next: EpisodeRef? = request.next

    /**
     * Whether [next] is settled: the page said which episode is next, or the show's episodes have been read (null then
     * means this is the last one aired). Until then the player shows no "Exit" for an episode.
     */
    var nextKnown: Boolean = request.next != null

    /** Whether [request] is for an episode. */
    val isEpisode: Boolean get() = request.target.episode != null

    /** The first source: the row chosen in Sources (asked about already when not on Real-Debrid), else the session's pick. */
    fun start() {
        val chosen = request.chosen
        val agreed = chosen != null && chosen.readiness == Readiness.NOT_READY && request.origin == PlayOrigin.SOURCES_PANEL
        if (agreed && chosen != null) {
            opening = session.fetch(chosen) { onOpened(it, OpenWhy.USER) }
        } else if (early != null && early.session === session) {
            // Begun at OK: what it has answered so far comes now, the rest as it comes.
            opening = early.cancel
            early.attach { onOpened(it, OpenWhy.START) }
        } else {
            open(chosen, OpenWhy.START)
        }
    }

    /** Next source (Picture & sound): another file, at the current position. Always allowed. */
    fun nextSource() = open(null, OpenWhy.USER)

    /** A row picked in the player's Sources panel; [beginning] from its start (held OK › Play from beginning). */
    fun pick(source: PlaybackSource, beginning: Boolean) {
        chosenKey = source.key
        if (source.readiness == Readiness.NOT_READY) {
            fromBeginning = beginning
            opening?.cancel()
            opening = session.fetch(source) { onOpened(it, OpenWhy.USER) }
            return
        }
        fromBeginning = beginning
        open(source, OpenWhy.USER)
    }

    private fun open(source: PlaybackSource?, why: OpenWhy) {
        if (closed) return
        opening?.cancel()
        opening = session.open(source, why) { onOpened(it, why) }
    }

    private fun refresh(s: PlaybackSource) {
        if (closed) return
        opening?.cancel()
        opening = session.refresh(s, offer?.addon) { onOpened(it, OpenWhy.FAILED) }
    }

    private fun onOpened(o: Opened, why: OpenWhy) {
        if (closed) return
        if (o !is Opened.Looking && o !is Opened.Checking) stallSwitching = false
        when (o) {
            is Opened.Looking -> if (!playing) host.showProgress(lookingText(o.list))
            is Opened.Checking -> if (!playing) host.showProgress(checkingText(o.source))
            is Opened.Ready -> ready(o)
            is Opened.AskFetch -> askFetch(o.source)
            is Opened.Stopped -> end(o.message)
            is Opened.NoneLeft -> end(o.message)
        }
    }

    private fun ready(o: Opened.Ready) {
        // A source was handed to the player before (it's built): this one takes its place.
        val switching = current != null
        val back = resumeAt
        val start = when {
            fromBeginning -> 0L
            // The copy before wasn't the whole film: where the film was when it started, not where it ended.
            back != null -> back
            o.startMs != null -> o.startMs
            // Once a source played, a new one starts a little before where it was (§6.5).
            everPlayed -> PlayRules.switchStart(host.positionMs())
            else -> when (val s = request.start) {
                StartAt.Beginning -> 0L
                is StartAt.At -> s.positionMs
            }
        }
        fromBeginning = false
        stopped = false
        // Another file: whether the connection keeps up with it is judged afresh.
        if (current?.key != o.source.key) supplyDone = false
        current = o.source
        offer = o.offer
        playing = false
        stalled = false
        aid.reset()
        loadTimer?.cancel()
        netWait?.cancel()
        netWait = null
        if (switching) host.log("another source starts at ${PlayRules.clock(start)}")
        host.startSource(o, start, switching)
        // The new copy's own from here (the player kept where the one before stood as it changed over).
        resumeAt = null
        copyStartMs = start
        copyFrameAt = null
        notWholeNow = false
        lengthJudged = false
        copyJumped = false
        val why = switchNote
        switchNote = null
        if (why != null) host.notice(why)
        // A resume whose own copy isn't in the full list: the ranking's pick plays instead.
        else if (o.resumedNear && !everPlayed && copyGone()) host.notice(COPY_GONE_NOTE)
        else if ((o.resumedNear || everPlayed) && start > 0) host.notice("Resumed near ${PlayRules.clock(start)}")
        // A resume that didn't take the copy it left: why, once, in the log (plain words, never a link).
        if (!everPlayed && !resumeLogged) session.resumeMiss()?.let {
            resumeLogged = true
            host.log("resume: not the copy left ($it)")
        }
        armLoad(o.source)
    }

    /**
     * The copy a resume left ([PlayRequest.preferred]) is in no row of the full list as a cached file this Stick showed a
     * picture for. Listed late (after the pick), or the list not full yet: "Resumed near" instead. One pass, once.
     */
    private fun copyGone(): Boolean {
        // Its release, or a copy of no release known by its key or as its file from another add-on
        // ([app.fireplayer.vod.SourceSession.opensLeft]).
        request.preferred?.takeIf { it.key != null } ?: return false
        val l = session.list?.takeIf { it.complete } ?: return false
        return l.sources.none { s ->
            s.measured?.health?.setAside != true &&
                s.offers.any { session.opensLeft(s, it) && it.readiness != Readiness.NOT_READY }
        }
    }

    private fun askFetch(s: PlaybackSource) {
        host.ask(
            "None of the ready sources played. Ask your debrid service to fetch ${PlayRules.badges(s).ifEmpty { "this source" }}? It can take a while and uses a download slot.",
            listOf(
                "Ask your debrid service to fetch it" to {
                    opening?.cancel()
                    opening = session.fetch(s) { onOpened(it, OpenWhy.USER) }
                },
                "Not now" to { end(NOT_FETCHED) },
            ),
            // Back is "Not now": the question isn't left hanging over a spinner.
            onDismiss = { end(NOT_FETCHED) },
        )
    }

    /**
     * Over: while a source plays, a note (it plays on); before any did, the play stops with [message] (after a copy that
     * wasn't the whole film, saying so first).
     */
    private fun end(message: String) {
        switchNote = null
        if (playing) {
            host.notice(message)
            return
        }
        loadTimer?.cancel()
        stopped = true
        val said = if (notWholeNow) "${notWholeWords(NOT_WHOLE_STOP, !isEpisode)} $message" else message
        PlayOutcomes.put(request.target, said)
        host.stopWith(said)
    }

    // ---- Stage 3: the first load ------------------------------------------------------------------------------------

    /** The player left the screen: stage 3's limit waits (nothing is asked over a screen that isn't there). */
    fun pause() {
        loadTimer?.cancel()
        loadTimer = null
        // The player is taken down with its screen and built again on return, which opens the film afresh anyway.
        netWait?.cancel()
        netWait = null
    }

    /** Back on screen: a source still loading gets its limit afresh (the player loads it again from here). */
    fun resume() {
        val s = current ?: return
        // The player loads again from here: its first wait is the resume's, not a stall.
        if (playing) session.stalls.jumped(ticker.now())
        if (!playing && !closed && !stopped && loadTimer == null) armLoad(s)
    }

    private fun armLoad(s: PlaybackSource) {
        loadTimer?.cancel()
        loadTimer = ticker.after(session.firstLoadLimitMs(s)) {
            if (closed || playing || current?.key != s.key) return@after
            val keepWaiting = { if (!closed && !stopped && !playing && current?.key == s.key) armLoad(s) }
            host.ask(
                "This source is slow to start.",
                listOf(
                    "Keep waiting" to keepWaiting,
                    "Try next source" to { if (!playing && current?.key == s.key) act(session.failed(s, Failure(FailureKind.SLOW, offer?.addon))) },
                ),
                // Back is Keep waiting: asked again at the limit.
                onDismiss = keepWaiting,
            )
        }
    }

    /** The current source's first frame: recorded as the last release used; the stall watch starts. */
    fun firstFrame() {
        val s = current ?: return
        // A copy that gave way stays loaded (held, or at its end) till the next one starts: a picture of it now starts
        // nothing (it isn't the copy the title goes on from again).
        if (playing || notWholeNow) return
        playing = true
        everPlayed = true
        stalled = false
        stallTryAt = Long.MIN_VALUE / 2
        loadTimer?.cancel()
        loadTimer = null
        PlayOutcomes.clear(request.target)
        // The same copy again (a retry) goes on counting from its first frame.
        if (copyFrameAt == null) copyFrameAt = ticker.now()
        offer?.let { session.started(s, it) }
        val now = ticker.now()
        session.stalls.jumped(now)
        session.stalls.playing(now)
    }

    // ---- Stage 4: while playing -------------------------------------------------------------------------------------

    /**
     * The player started or stopped loading while playing. [excused]: the skip preview was loading beside the film (or
     * just had), so this wait isn't counted toward a switch.
     */
    fun buffering(on: Boolean, excused: Boolean = false) {
        if (!playing) return
        val now = ticker.now()
        if (on && !stalled) stalledAt = now
        if (!on && stalled && now - stalledAt >= LOGGED_STALL_MS) host.log("playing again after a ${(now - stalledAt) / 1000} s stall")
        stalled = on
        aid.stalled(now, on)
        session.stalls.buffering(now, on)
        if (on && excused) session.stalls.excuse(now)
        if (!on) session.stalls.playing(now)
        checkStalls(now)
    }

    /**
     * Called as it plays (a few times a second), with how much of the film is loaded ahead ([aheadMs]): a stall that
     * goes on is judged while it lasts, not only once it ends. First the same source is opened again when nothing is
     * arriving ([StallAid]); a stall that still goes on is the switching rules'.
     */
    fun tick(aheadMs: Long = 0L) {
        if (!playing || !stalled || closed) return
        val now = ticker.now()
        if (!stallSwitching && aid.shouldReopen(now, aheadMs)) {
            if (host.reopen()) {
                host.log("stall ${(now - stalledAt) / 1000} s with nothing arriving: same source reopened at ${PlayRules.clock(host.positionMs())}")
            }
        }
        checkStalls(now)
    }

    /** A seek, a resume or a rebuilt player: stalls right after it don't count. */
    fun jumped() {
        session.stalls.jumped(ticker.now())
        aid.reset()
        if (copyFrameAt != null) copyJumped = true
    }

    /**
     * The stall rules call for a switch. What happens goes by Settings › Playback › When a film keeps stalling
     * ([WhenBuffering]) and by why it stalls:
     * - Keep trying, or "Keep this source" answered for this film before: nothing is switched or asked. When the
     *   connection brings the film no faster than it plays, that is said once, plainly, with the copy that would fit.
     * - The connection brings the film no faster than it plays ([FilmSupply.short]): the next source in the list is
     *   most often the same film at the same size, and stalls the same. The best source this connection keeps up with
     *   is offered instead, once (or switched to with a note, by the setting or the film's remembered answer).
     * - Else, with Ask first, the copy is only left when the viewer says so (asked once for it), whoever picked it: the
     *   viewer, or FirePlayer at the start, a resume, Next source or a switch. Switch to the next copy moves on to the
     *   next source without asking, as before.
     * Copies that can't play at all ([noPicture], [giveWay], [failed]) are left by their own paths, never through here.
     */
    private fun checkStalls(now: Long) {
        if (stallSwitching || asking || !session.stalls.shouldSwitch(now)) return
        val s = current ?: return
        val mode = host.whenBuffering()
        val choice = StallChoice.decode(host.stallChoice(), s.key)
        if (mode == WhenBuffering.KEEP || choice == StallChoice.KEEP) {
            if (!keepLogged) {
                keepLogged = true
                host.log("stalls past the limit: staying on this source (" +
                    (if (mode == WhenBuffering.KEEP) "the setting says keep trying" else "kept when asked for this film") + ")")
                // Not a question (it was answered, or the setting says so): what's happening, once.
                val kept = host.supply()
                val need = rateOf(s)
                if (FilmSupply.short(need, kept)) {
                    val fits = FilmSupply.ceilingMbps(kept)?.let { session.lighter(it) }
                    host.notice(FilmSupply.keptNote(need, kept, fits))
                }
            }
            return
        }
        val reading = host.supply()
        if (FilmSupply.short(rateOf(s), reading)) {
            shortSupply(s, reading, mode == WhenBuffering.LIGHTER || choice == StallChoice.LIGHTER)
            return
        }
        if (mode == WhenBuffering.ASK) {
            askToLeave(s)
            return
        }
        switchForStalls(now)
    }

    /** [s] needs more than the connection brings: the best source it keeps up with, asked for or switched to. */
    private fun shortSupply(s: PlaybackSource, reading: FilmSupply.Reading?, unasked: Boolean) {
        if (supplyDone) return
        supplyDone = true
        reading?.lineMbps?.let { host.lineShort(it) }
        val lighter = FilmSupply.ceilingMbps(reading)?.let { session.lighter(it) }
        val need = rateOf(s)
        host.log("stalls past the limit and the connection is short: the source needs " + mbps(need) +
            ", the download sustains " + mbps(reading?.lineMbps) + ", arriving at " +
            (reading?.fillRate?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "?") + "x; " +
            (lighter?.let { "a lighter source is ready at " + mbps(rateOf(it)) } ?: "no lighter source is ready"))
        if (lighter == null) {
            host.notice(FilmSupply.noneNote(reading))
            return
        }
        if (unasked) {
            host.log("switching to the lighter source without asking")
            host.notice(FilmSupply.switchedNote(reading))
            stallSwitching = true
            open(lighter, OpenWhy.STALLS)
            return
        }
        // Asked about already (the plain question, answered): not asked again.
        if (!askedAbout.add(s.key)) return
        asking = true
        host.ask(
            FilmSupply.question(need, reading, lighter),
            listOf(
                FilmSupply.SWITCH to {
                    asking = false
                    host.rememberStallChoice(StallChoice.encode(StallChoice.LIGHTER, s.key))
                    // Not if s was left while the question was up (it failed): the copy playing now stays.
                    if (current?.key == s.key) {
                        host.log("asked: switching to the lighter source")
                        open(lighter, OpenWhy.USER)
                    }
                },
                FilmSupply.KEEP to {
                    asking = false
                    host.rememberStallChoice(StallChoice.encode(StallChoice.KEEP, s.key))
                    host.log("asked: keeping this source")
                },
            ),
            // Back: not now. Nothing is remembered, and it isn't asked again in this play.
            onDismiss = { asking = false },
        )
    }

    /**
     * [s] keeps pausing, though the connection is fast enough for it (Ask first): asked once for it in this play (by
     * either question). Keep, or Back: it plays on and isn't asked about again. Try next source does nothing once [s]
     * was left while the question was up (it failed): the copy playing now isn't the one asked about.
     */
    private fun askToLeave(s: PlaybackSource) {
        if (!askedAbout.add(s.key)) return
        asking = true
        host.log("stalls past the limit: asking before any switch")
        host.ask(
            FilmSupply.PLAIN_QUESTION,
            listOf(
                FilmSupply.TRY_NEXT to {
                    asking = false
                    if (current?.key == s.key) nextSource()
                },
                FilmSupply.KEEP to { asking = false },
            ),
            onDismiss = { asking = false },
        )
    }

    private fun mbps(v: Double?): String = v?.let { "" + Math.round(it) + " Mb/s" } ?: "an unknown rate"

    /**
     * [s]'s average rate, Mb/s: as a play here measured it when it did ([SourceRank.asPlayedHere]; its row in the list
     * now, which the play playing measures as it goes), else the add-ons'; null when neither knows.
     */
    /**
     * The average rate of the source playing or being opened, Mb/s ([rateOf]); null when it isn't known, or there is
     * none. For the size the film's buffer starts with on a TV with little memory free; main thread.
     */
    fun rateMbps(): Double? = current?.let { rateOf(it) }?.takeIf { it > 0.0 }

    private fun rateOf(s: PlaybackSource): Double? {
        val key = session.rowKey(s)
        val row = session.list?.sources?.firstOrNull { it.key == key } ?: s
        return SourceRank.asPlayedHere(row.facts, row.measured).avgMbps
    }

    private fun switchForStalls(now: Long) {
        if (session.maySwitch(now)) {
            // A try that found nothing to switch to is not repeated at once.
            if (now - stallTryAt < STALL_RETRY_MS) return
            stallTryAt = now
            stallSwitching = true
            host.log("stalls past the limit: trying the next source")
            open(null, OpenWhy.STALLS)
        } else if (!limitNoted) {
            limitNoted = true
            host.log("stalls past the limit, but no switch is left")
            host.notice(LIMIT_NOTE)
        }
    }

    // ---- Failures ---------------------------------------------------------------------------------------------------

    /** A player error the player's own quiet retries didn't get past (PlaybackException code, HTTP status). */
    fun failed(errorCode: Int, httpStatus: Int?) {
        val s = current ?: return end(GENERIC_STOP)
        val failure = session.failureOf(errorCode, httpStatus)
        playing = false
        // The network dropped under a film that was playing: not the source's doing. Nothing is counted against it,
        // and it plays on from the same link, where it stood, once the network is back.
        if (failure.kind == FailureKind.NO_INTERNET && everPlayed) {
            awaitNetwork()
            return
        }
        val step = session.failed(s, failure)
        host.log("error $errorCode" + (httpStatus?.let { " (HTTP $it)" } ?: "") + " counted as ${failure.kind}: " + stepWords(step))
        act(step)
    }

    /**
     * The Stick showed no picture for the source playing: its video decoder failed, none of this TV's decoders takes its
     * picture, or no frame came while its sound played ([app.fireplayer.player.PictureWatch]). A failure, not a stall:
     * Settings › Playback › When a film keeps stalling has no say, and a source picked by hand is left too. The next
     * source down the list plays (the session skips this one, and the copies that showed none here before, within its
     * limits on switches), and says why as it starts ([NO_PICTURE_NOTE], when [said]: none of its picture showed).
     * [formatWide]: none of this TV's decoders takes its format, so the session passes over that format's copies too
     * ([Failure.NO_DECODER]). Nothing left: the play stops and says so.
     */
    fun noPicture(formatWide: Boolean = false, said: Boolean = true) {
        val s = current ?: return
        if (closed || stopped) return
        playing = false
        loadTimer?.cancel()
        loadTimer = null
        val step = session.failed(s, Failure(FailureKind.NO_PICTURE, offer?.addon, if (formatWide) Failure.NO_DECODER else "no picture here"))
        host.log("no picture from this source: " + stepWords(step))
        switchNote = if (said) NO_PICTURE_NOTE else null
        act(step)
    }

    /**
     * The copy playing came to its end ([durationMs] long; the player's STATE_ENDED). True: the film's or episode's end
     * ([PlayRules.copyEnd]), which the player records and acts on as before. False: it isn't the whole film (it ended
     * without really playing, e.g. a switch started it past its end, or a film's copy is far shorter than the film):
     * nothing of it is recorded, it's kept as such when it's plainly another video or stops short ([Host.notWhole],
     * [PlayRules.marksNotWhole]), and the next copy plays from where the film was when this one started, saying why
     * ([NOT_WHOLE_NOTE]); nothing left, the play stops and the resume point stays. Also asked by the player as a sleep
     * timer set for the end of the video pauses there: false, and the sleep waits for the film's own end.
     */
    fun reachedEnd(durationMs: Long): Boolean {
        val s = current ?: return true
        // Given way already (its end, and a sleep timer's pause at its end, both ask): still not the film's end.
        if (notWholeNow) return false
        val end = copyEnd(durationMs)
        if (end == PlayRules.CopyEnd.END) return true
        val why = if (end == PlayRules.CopyEnd.SHORT) "its ${PlayRules.clock(durationMs)} is far shorter than the film's ${runtimeMin()} min"
        else "it ended ${copyPlayedMs() / 1000} s after its first frame"
        giveWay(s, why, PlayRules.marksNotWhole(end, copyStartMs, durationMs))
        return false
    }

    /**
     * The copy playing's length is known ([durationMs]; the player's first READY). A film's copy far shorter than the
     * film ([PlayRules.shortCopy]) FirePlayer picked (a start, a resume, Next source, a switch) isn't played to its end:
     * it gives way at once, as at its end ([reachedEnd]), and true is returned (the player holds the picture meanwhile).
     * One the viewer chose in a Sources window plays on (kept as such all the same, and not the copy the title goes on
     * from): its end is never the film's. Judged once, when the film's runtime is known (the title's details can come
     * after the first READY: a later one). A copy that gave way already is held again (true), never judged again.
     */
    fun lengthKnown(durationMs: Long): Boolean {
        val s = current ?: return false
        if (notWholeNow) return !closed
        if (lengthJudged || durationMs <= 0 || closed || stopped) return false
        val runtime = runtimeMin()
        if (runtime == null && !isEpisode) return false
        lengthJudged = true
        if (!PlayRules.shortCopy(durationMs, runtime, isEpisode)) return false
        if (s.key == chosenKey) {
            host.log("this copy is far shorter than the film (${PlayRules.clock(durationMs)}): chosen by hand, it plays on")
            host.notWhole()
            session.notWhole(s)
            return false
        }
        giveWay(s, "its ${PlayRules.clock(durationMs)} is far shorter than the film's ${runtimeMin()} min", mark = true)
        return true
    }

    /**
     * [s] isn't the whole film: kept as such when [mark] ([PlayRules.marksNotWhole]; else passed over for this play only),
     * then the next copy, from where the film was when [s] started. Once for a copy.
     */
    private fun giveWay(s: PlaybackSource, why: String, mark: Boolean) {
        if (notWholeNow) return
        notWholeNow = true
        // Kept first: the session's list ranks again with it at once.
        if (mark) host.notWhole()
        if (closed || stopped) return
        playing = false
        loadTimer?.cancel()
        loadTimer = null
        val step = session.failed(s, Failure(FailureKind.NOT_WHOLE, offer?.addon, "not the whole film"))
        host.log("this copy isn't the whole film ($why): " + stepWords(step))
        switchNote = notWholeWords(NOT_WHOLE_NOTE, !isEpisode)
        resumeAt = copyStartMs
        act(step)
    }

    /** What the copy playing's end would be now ([PlayRules.copyEnd]), [durationMs] long. */
    private fun copyEnd(durationMs: Long) = PlayRules.copyEnd(copyStartMs, copyPlayedMs(), durationMs, runtimeMin(), isEpisode, copyJumped)

    /** How long the copy playing has played since its first frame; 0 before it showed one. */
    private fun copyPlayedMs(): Long = copyFrameAt?.let { (ticker.now() - it).coerceAtLeast(0L) } ?: 0L

    /** The film's runtime in minutes: the title's details', else the title's own; null when neither says. */
    private fun runtimeMin(): Int? =
        host.runtimeMinutes().takeIf { it > 0 } ?: app.fireplayer.vod.TitleDetails.runtimeMinutes(request.target.title.runtime)

    private fun awaitNetwork() {
        netWait?.cancel()
        host.log("no network: waiting for it with the same source at ${PlayRules.clock(host.positionMs())}")
        host.showProgress(Recovery.NET_WAITING)
        pollNetwork(ticker.now())
    }

    private fun pollNetwork(since: Long) {
        netWait = ticker.after(Recovery.NET_POLL_MS) {
            netWait = null
            if (closed || playing || stopped) return@after
            val waited = ticker.now() - since
            when (Recovery.netStep(session.online(), waited)) {
                Recovery.NetStep.WAIT -> pollNetwork(since)
                Recovery.NetStep.PLAY -> {
                    host.log("network back after ${waited / 1000} s: same source again")
                    host.retryAfter(Recovery.NET_SETTLE_MS)
                }
                Recovery.NetStep.GIVE_UP -> {
                    host.log("no network for ${waited / 1000} s: stopping")
                    end(NO_NETWORK)
                }
            }
        }
    }

    private fun stepWords(step: Step): String = when (step) {
        Step.TryNext -> "the next source"
        is Step.Retry -> "the same link again in ${step.afterMs / 1000} s"
        Step.Refresh -> "a fresh link for the same file"
        Step.DecodeOnDevice -> "the sound decoded on the Stick"
        is Step.Stop -> "stopping"
    }

    private fun act(step: Step) {
        when (step) {
            Step.TryNext -> open(null, OpenWhy.FAILED)
            is Step.Retry -> host.retryAfter(step.afterMs)
            // The add-on is asked again for the same file's link (not a switch, and not the old link again).
            Step.Refresh -> current?.let { s -> refresh(s) } ?: open(null, OpenWhy.FAILED)
            Step.DecodeOnDevice -> host.decodeOnDevice()
            is Step.Stop -> end(step.message)
        }
    }

    // ---- The library and the next episode --------------------------------------------------------------------------

    /**
     * Where playback is (on leaving, and now and then): the resume point, Continue Watching and the watched mark. Never
     * from a copy that isn't the whole film, nor an end from one a switch started at or past its own end
     * ([PlayRules.keepsPlace]): the film's own place, kept before, stays.
     */
    fun record(positionMs: Long, durationMs: Long, creditsAtMs: Long?) {
        if (!everPlayed || durationMs <= 0 || notWholeNow) return
        if (!PlayRules.keepsPlace(copyEnd(durationMs), copyStartMs, positionMs, durationMs, creditsAtMs)) return
        library.recordPlayback(request.target.title, request.target.episode, positionMs, durationMs, creditsAtMs)
    }

    /** Called as it plays: in the last ~4 minutes of an episode, the next one's sources are looked up once. */
    fun nearEnd(positionMs: Long, durationMs: Long) {
        val n = next ?: return
        if (nextLooked || durationMs <= 0 || durationMs - positionMs > PlayRules.NEXT_LOOKUP_MS) return
        nextLooked = true
        nextLookup = session.lookUpNext(n) { nextList = it }
    }

    /** The episode played to its end: the next one starts from its own sources (true), or there's none (false). */
    fun ended(playNext: Boolean): Boolean {
        val n = next ?: return false
        if (!playNext) return false
        host.playNext(nextRequest(n))
        return true
    }

    /** The next episode's play: from the start, its list when already complete, the same kind of source preferred. */
    fun nextRequest(n: EpisodeRef): PlayRequest = PlayRequest(
        target = SourceTarget(request.target.title, n),
        start = StartAt.Beginning,
        origin = PlayOrigin.NEXT_EPISODE,
        list = nextList?.takeIf { it.complete && it.target.episode == n },
        like = like(),
        filmMode = request.filmMode,
    )

    /**
     * The kind the next episode follows ([PlayRequest.like]): the copy playing with the offer it plays from alone. A row
     * of two add-ons is one file of this episode by what they say of it; the next episode's files of the same torrent may
     * not be (an add-on that numbers its pack differently), and then that one's own (its binge group) comes next, as it
     * did when the row was its alone.
     */
    private fun like(): PlaybackSource? {
        val s = current ?: return null
        val o = offer?.takeIf { s.offers.size > 1 && it in s.offers } ?: return s
        return s.copy(offers = listOf(o))
    }

    /**
     * Another episode of the show, chosen in the player's Episodes sheet: a new play in place of this one, from [start]
     * (where it stopped, or its beginning). The same file as last time when it resumes, else the same kind of source as
     * the one playing (§6.5's next-episode rule).
     */
    fun playEpisode(episode: EpisodeRef, start: StartAt) {
        if (closed) return
        host.playNext(episodeRequest(episode, start))
    }

    /** The play of [episode] from [start], for [playEpisode]. */
    fun episodeRequest(episode: EpisodeRef, start: StartAt): PlayRequest = PlayRequest(
        target = SourceTarget(request.target.title, episode),
        start = start,
        origin = PlayOrigin.NEXT_EPISODE,
        preferred = if (start is StartAt.At) library.lastRelease(request.target.title, episode) else null,
        like = like(),
        filmMode = request.filmMode,
    )

    /** The add-on the playing source came from, for the player's details ("from Torrentio"). */
    val addon: Addon? get() = offer?.addon

    fun close() {
        if (closed) return
        closed = true
        opening?.cancel()
        loadTimer?.cancel()
        netWait?.cancel()
        nextLookup?.cancel()
        session.close()
    }

    private fun lookingText(l: SourceList): String {
        val now = ticker.now()
        val states = Addon.entries.mapNotNull { a -> l.states[a]?.let { PlayRules.stateText(a, it, now) } }
        return (listOf("Finding sources") + states).joinToString(" · ")
    }

    private fun checkingText(s: PlaybackSource): String =
        listOf("Checking the link", PlayRules.badges(s), "from ${PlayRules.addonsOf(s)}").filter { it.isNotEmpty() }.joinToString(" · ")

    companion object {
        const val LIMIT_NOTE = "Playback keeps pausing · Next source in Picture & sound tries another"
        /** Said as the next source starts after one this Stick showed no picture for ([noPicture]). */
        const val NO_PICTURE_NOTE = "This copy showed no picture here · playing the next one"
        /** Said as the next source starts after one that wasn't the whole film ([reachedEnd], [lengthKnown]). */
        const val NOT_WHOLE_NOTE = "This copy isn't the whole film · playing the next one"
        /** Said before why the play stopped, when the last copy wasn't the whole film and none was left. */
        const val NOT_WHOLE_STOP = "This copy isn't the whole film."

        /** [words] about a [film], or about an episode. */
        fun notWholeWords(words: String, film: Boolean): String = if (film) words else words.replace("film", "episode")
        /** Said as a resume starts on another copy: the one left is no longer listed, or no longer cached. */
        const val COPY_GONE_NOTE = "The copy you were watching isn't available · playing the best one"
        const val NOT_FETCHED = "Nothing played · Sources lists every file"
        const val GENERIC_STOP = "This title couldn't play · Sources lists every file"
        /** The network stayed away ([Recovery.NET_WAIT_MS]): the failure table's words for it. */
        const val NO_NETWORK = "No internet connection. Check the Wi-Fi and try again."
        /** A switch for stalls that found nothing is tried again after this long, ms. */
        const val STALL_RETRY_MS = 120_000L
        /** A stall at least this long is noted in the log when it ends. */
        const val LOGGED_STALL_MS = 2_000L
    }
}

/**
 * Why the last play of a film or episode stopped, kept in memory for its title page: Back from a play that gave up lands
 * on the page with the reason in the source line and focus on Sources (r3_title-page §3.6). Only plain words, never links.
 */
object PlayOutcomes {
    private val stopped = ConcurrentHashMap<String, String>()

    fun put(target: SourceTarget, message: String) {
        stopped[target.key] = message
    }

    fun clear(target: SourceTarget) {
        stopped.remove(target.key)
    }

    /** The reason [target]'s last play stopped, once (it's gone after this). */
    fun take(target: SourceTarget): String? = stopped.remove(target.key)
}
