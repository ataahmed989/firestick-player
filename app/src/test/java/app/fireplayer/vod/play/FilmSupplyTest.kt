package app.fireplayer.vod.play

import app.fireplayer.vod.OpenWhy
import app.fireplayer.vod.Opened
import app.fireplayer.vod.PlayOrigin
import app.fireplayer.vod.PlayRequest
import app.fireplayer.vod.PlaybackSource
import app.fireplayer.vod.SourceTarget
import app.fireplayer.vod.StartAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A film that keeps stopping to load because the connection brings it no faster than it plays ([FilmSupply]), and what
 * the player does about it by Settings › Playback › When a film keeps stalling ([SourcePlayback]). Figures as measured:
 * an 89 GB remux of 2 h 53 min (69 Mb/s) on a line sustaining 72 Mb/s.
 */
class FilmSupplyTest {

    @Test fun aKeptSourceIsToldOncePlainlyWhatsHappening() {
        assertEquals(
            "This copy needs about 78 Mb/s and about 47 is arriving, so it will keep pausing · Sources lists every file",
            FilmSupply.keptNote(78.0, FilmSupply.Reading(47.2, 0.46f), null),
        )
        assertEquals(
            "This copy arrives slower than it plays, so it will keep pausing · Sources lists every file",
            FilmSupply.keptNote(null, FilmSupply.Reading(null, 0.46f), null),
        )
    }

    // ---- The rule

    @Test fun aSourceWithinATenthOfTheLineIsShort() {
        val line = FilmSupply.Reading(lineMbps = 72.0, fillRate = 0.98f)
        assertTrue(FilmSupply.short(69.0, line))
        assertTrue(FilmSupply.short(90.0, line))
        // 64.8 Mb/s is nine tenths of 72: at and under it, not short.
        assertFalse(FilmSupply.short(64.8, line))
        assertFalse(FilmSupply.short(26.0, line))
    }

    @Test fun theFilmArrivingWithPlentyToSpareIsNeverShort() {
        // The two measurements are taken apart; both must say it.
        assertFalse(FilmSupply.short(69.0, FilmSupply.Reading(lineMbps = 72.0, fillRate = 2.4f)))
        assertTrue(FilmSupply.short(69.0, FilmSupply.Reading(lineMbps = 72.0, fillRate = null)))
    }

    @Test fun withoutTheSourcesRateOrTheLinesTheArrivalDecides() {
        assertTrue(FilmSupply.short(null, FilmSupply.Reading(72.0, 1.0f)))
        assertTrue(FilmSupply.short(69.0, FilmSupply.Reading(null, 0.9f)))
        assertFalse(FilmSupply.short(null, FilmSupply.Reading(72.0, 1.2f)))
        assertFalse(FilmSupply.short(69.0, FilmSupply.Reading(null, null)))
        assertFalse(FilmSupply.short(69.0, null))
    }

    @Test fun aSourceTheLineSustainsNeedsAtMostThreeQuartersOfIt() {
        assertEquals(54.0, FilmSupply.ceilingMbps(FilmSupply.Reading(72.0, 0.98f))!!, 0.001)
        assertNull(FilmSupply.ceilingMbps(FilmSupply.Reading(null, 0.98f)))
        assertNull(FilmSupply.ceilingMbps(null))
    }

    @Test fun theWordsSayBothRatesAndTheLighterSource() {
        val q = FilmSupply.question(69.2, FilmSupply.Reading(72.4, 0.98f), Fixtures.source(2, mbps = 26.0))
        assertEquals(
            "This source needs about 69 Mb/s and this connection is bringing about 72, so it keeps pausing. " +
                "Switch to 4K · HDR · WEB-DL · 29.3 GB · ≈26 Mb/s? It carries on from here.",
            q,
        )
        assertTrue(FilmSupply.why(null, FilmSupply.Reading(72.0, 1.0f)).contains("about 72 Mb/s"))
        assertFalse(FilmSupply.why(null, null).contains("Mb/s"))
    }

    @Test fun theSettingsWordsAndItsDefault() {
        assertEquals(listOf("Ask first", "Switch to the next copy", "Keep trying"), WhenBuffering.entries.map { it.label })
        // As the row says them, beside its long name.
        assertEquals(listOf("Ask", "Switch", "Keep"), WhenBuffering.entries.map { it.short })
        // Saved by these names since before the row was renamed: every choice made carries over.
        assertEquals(listOf("ASK", "LIGHTER", "KEEP"), WhenBuffering.entries.map { it.name })
        assertEquals(WhenBuffering.LIGHTER, WhenBuffering.named("LIGHTER"))
        assertEquals(WhenBuffering.ASK, WhenBuffering.named(null))
        assertEquals(WhenBuffering.ASK, WhenBuffering.named("something else"))
        assertEquals(WhenBuffering.KEEP, WhenBuffering.named("KEEP"))
    }

    @Test fun keepIsRememberedForTheSourceLighterForTheFilm() {
        assertEquals(StallChoice.KEEP, StallChoice.decode(StallChoice.encode(StallChoice.KEEP, "k1"), "k1"))
        assertNull(StallChoice.decode(StallChoice.encode(StallChoice.KEEP, "k1"), "k2"))
        assertEquals(StallChoice.LIGHTER, StallChoice.decode(StallChoice.encode(StallChoice.LIGHTER, "k1"), "k2"))
        assertNull(StallChoice.decode(null, "k1"))
        assertNull(StallChoice.decode("nonsense", "k1"))
    }

    // ---- The player's side

    private val ticker = FakeTicker()
    private val library = FakeLibrary()
    private val log = ArrayList<String>()
    private val questions = ArrayList<Pair<String, List<Pair<String, () -> Unit>>>>()
    private val dismisses = ArrayList<() -> Unit>()
    private val starts = ArrayList<Triple<String, Long, Boolean>>()
    private val lineShorts = ArrayList<Double>()
    private var position = 3_600_000L
    private var mode = WhenBuffering.ASK
    private var reading: FilmSupply.Reading? = FilmSupply.Reading(72.0, 0.98f)
    private var kept: String? = null

    private val host = object : SourcePlayback.Host {
        override fun showProgress(text: String) = Unit
        override fun startSource(ready: Opened.Ready, startMs: Long, switching: Boolean) {
            starts += Triple(ready.source.key, startMs, switching)
        }
        override fun ask(question: String, choices: List<Pair<String, () -> Unit>>, onDismiss: () -> Unit) {
            questions += question to choices
            dismisses += onDismiss
        }
        override fun stopWith(message: String) { log += "stop $message" }
        override fun notice(text: String) { log += "notice $text" }
        override fun positionMs() = position
        override fun retryAfter(ms: Long) = Unit
        override fun decodeOnDevice() = Unit
        override fun playNext(next: PlayRequest) = Unit
        override fun log(text: String) { log += "log $text" }
        override fun whenBuffering() = mode
        override fun supply() = reading
        override fun stallChoice() = kept
        override fun rememberStallChoice(choice: String) { kept = choice }
        override fun lineShort(mbps: Double) { lineShorts += mbps }
    }

    private val remux = Fixtures.source(1, mbps = 69.0)
    private val encode = Fixtures.source(2, mbps = 26.0)

    /** A play of [first] that has shown its first frame, with [lighter] the session's lighter source. */
    private fun playing(first: PlaybackSource = remux, lighter: PlaybackSource? = encode, chosen: PlaybackSource? = null): Pair<SourcePlayback, FakeSession> {
        val request = PlayRequest(SourceTarget(Fixtures.film), StartAt.Beginning, PlayOrigin.TITLE_PAGE, chosen = chosen)
        val session = FakeSession(request)
        session.lighterSource = lighter
        val p = SourcePlayback(request, session, library, ticker, host)
        p.start()
        session.ready(first)
        p.firstFrame()
        return p to session
    }

    private fun answer(label: String) = questions.last().second.first { it.first == label }.second()

    @Test fun aRemuxTheLineCantKeepUpWithIsAskedAboutOnce() {
        val (p, s) = playing()
        s.switchNow = true
        p.buffering(true)
        // Asked, not switched: the next source of the same size would stall the same.
        assertEquals(1, s.opens.size)
        assertEquals(1, questions.size)
        assertTrue(questions[0].first.startsWith("This source needs about 69 Mb/s and this connection is bringing about 72"))
        assertTrue(questions[0].first.contains("≈26 Mb/s"))
        assertEquals(listOf(FilmSupply.SWITCH, FilmSupply.KEEP), questions[0].second.map { it.first })
        // The best source needing at most three quarters of the line was asked for, and the line's speed kept.
        assertEquals(listOf(54.0), s.lighterAsked)
        assertEquals(listOf(72.0), lineShorts)
        // While the question is up, and after Back closes it: nothing more in this play.
        p.tick()
        p.buffering(false)
        p.buffering(true)
        val back: () -> Unit = dismisses[dismisses.size - 1]
        back()
        p.tick()
        assertEquals(1, questions.size)
        assertEquals(1, s.opens.size)
        assertNull(kept)
    }

    @Test fun switchingKeepsThePositionAndIsRememberedForTheFilm() {
        val (p, s) = playing()
        s.switchNow = true
        p.buffering(true)
        answer(FilmSupply.SWITCH)
        assertEquals(encode to OpenWhy.USER, s.opens.last())
        assertEquals("LIGHTER", kept)
        s.ready(encode)
        // In place, 5 s before where it was.
        assertEquals(Triple("k2", 3_595_000L, true), starts.last())
        assertTrue(log.contains("notice Resumed near 59:55"))

        // Another evening, the same film on a heavy source again: switched with a note, not asked again.
        questions.clear()
        val (p2, s2) = playing()
        s2.switchNow = true
        p2.buffering(true)
        assertTrue(questions.isEmpty())
        assertEquals(encode to OpenWhy.STALLS, s2.opens.last())
        assertTrue(log.contains("notice ${FilmSupply.switchedNote(reading)}"))
    }

    @Test fun keepThisSourceMeansNoSwitchAndNoQuestionForItAgain() {
        val (p, s) = playing()
        s.switchNow = true
        p.buffering(true)
        answer(FilmSupply.KEEP)
        assertEquals("KEEP|k1", kept)
        p.buffering(false)
        p.buffering(true)
        p.tick()
        assertEquals(1, s.opens.size)
        assertEquals(1, questions.size)

        // The same source another day: left alone, even by the ordinary next-source switch.
        val (p2, s2) = playing()
        reading = FilmSupply.Reading(300.0, 3f)
        s2.switchNow = true
        p2.buffering(true)
        assertEquals(1, s2.opens.size)
        assertEquals(1, questions.size)
        // Another source of the same film is judged afresh: asked about, as any copy that keeps stalling.
        val (p3, s3) = playing(first = Fixtures.source(3, mbps = 25.0))
        s3.switchNow = true
        p3.buffering(true)
        assertEquals(FilmSupply.PLAIN_QUESTION, questions.last().first)
        assertEquals(1, s3.opens.size)
    }

    @Test fun theSettingSwitchesWithoutAskingAndSaysWhy() {
        mode = WhenBuffering.LIGHTER
        val (p, s) = playing()
        s.switchNow = true
        p.buffering(true)
        assertTrue(questions.isEmpty())
        assertEquals(encode to OpenWhy.STALLS, s.opens.last())
        assertTrue(log.contains("notice Kept pausing at 72 Mb/s · switching to a lighter source"))
        // While it's opened the watch waits; nothing is remembered for the film (it's the setting's doing).
        p.tick()
        assertEquals(2, s.opens.size)
        assertNull(kept)
    }

    @Test fun keepTryingNeverSwitchesWhateverTheReason() {
        mode = WhenBuffering.KEEP
        val (p, s) = playing()
        s.switchNow = true
        p.buffering(true)
        p.tick()
        // Nor when the connection is fast enough and the stalls are the server's.
        reading = FilmSupply.Reading(300.0, 3f)
        p.buffering(false)
        p.buffering(true)
        assertEquals(1, s.opens.size)
        assertTrue(questions.isEmpty())
        assertTrue(lineShorts.isEmpty())
        assertEquals(1, log.count { it.startsWith("log stalls past the limit: staying on this source") })
    }

    @Test fun withNothingLighterReadyItSaysSoOnceAndStays() {
        val (p, s) = playing(lighter = null)
        s.switchNow = true
        p.buffering(true)
        p.tick()
        p.buffering(false)
        p.buffering(true)
        assertEquals(1, s.opens.size)
        assertTrue(questions.isEmpty())
        assertEquals(1, log.count { it == "notice ${FilmSupply.noneNote(reading)}" })
        assertEquals(listOf(72.0), lineShorts)
    }

    @Test fun aSourceTheLineKeepsUpWithIsAskedAboutThenMovesOnByTheSetting() {
        // 25 Mb/s on a 72 Mb/s line, picked by FirePlayer: the stalls are the server's. Ask first: the next source
        // only if the viewer says so, never a lighter one (the line isn't short).
        val (p, s) = playing(first = Fixtures.source(3, mbps = 25.0))
        s.switchNow = true
        p.buffering(true)
        assertEquals(FilmSupply.PLAIN_QUESTION, questions.single().first)
        assertEquals(1, s.opens.size)
        assertTrue(lineShorts.isEmpty())
        assertTrue(s.lighterAsked.isEmpty())
        // Switch to the next copy: the next source is tried, as before.
        mode = WhenBuffering.LIGHTER
        val (p2, s2) = playing(first = Fixtures.source(3, mbps = 25.0))
        s2.switchNow = true
        p2.buffering(true)
        assertEquals(null to OpenWhy.STALLS, s2.opens.last())
        assertEquals(1, questions.size)
        assertTrue(s2.lighterAsked.isEmpty())
    }

    @Test fun aSourcePickedByHandIsOnlyLeftWhenTheViewerSaysSo() {
        val mine = Fixtures.source(3, mbps = 25.0)
        val (p, s) = playing(first = mine, chosen = mine)
        s.switchNow = true
        p.buffering(true)
        assertEquals(1, s.opens.size)
        assertEquals(FilmSupply.PLAIN_QUESTION, questions.single().first)
        // Keep: not asked again in this play, and never switched.
        answer(FilmSupply.KEEP)
        p.buffering(false)
        p.buffering(true)
        p.tick()
        assertEquals(1, s.opens.size)
        assertEquals(1, questions.size)

        // Try next source: at the current position, as Next source in Picture & sound.
        questions.clear()
        val (p2, s2) = playing(first = mine, chosen = mine)
        s2.switchNow = true
        p2.buffering(true)
        answer(FilmSupply.TRY_NEXT)
        assertEquals(null to OpenWhy.USER, s2.opens.last())
        // With the setting on "Switch to the next copy", the viewer has allowed it: no question.
        mode = WhenBuffering.LIGHTER
        questions.clear()
        val (p3, s3) = playing(first = mine, chosen = mine)
        s3.switchNow = true
        p3.buffering(true)
        assertEquals(null to OpenWhy.STALLS, s3.opens.last())
        assertTrue(questions.isEmpty())
    }

    @Test fun eitherQuestionIsTheCopysOneQuestion() {
        // The line read plenty when it first stalled: the plain question, answered Keep.
        reading = FilmSupply.Reading(300.0, 3f)
        val (p, s) = playing()
        s.switchNow = true
        p.buffering(true)
        assertEquals(FilmSupply.PLAIN_QUESTION, questions.single().first)
        answer(FilmSupply.KEEP)
        // Then it reads short: not asked again, the other way. The line's speed is still kept for the lists.
        reading = FilmSupply.Reading(72.0, 0.98f)
        p.buffering(false)
        p.buffering(true)
        p.tick()
        assertEquals(1, questions.size)
        assertEquals(1, s.opens.size)
        assertEquals(listOf(72.0), lineShorts)

        // The other way round: Back on the short-supply question, then a reading of plenty.
        questions.clear()
        val (p2, s2) = playing()
        s2.switchNow = true
        p2.buffering(true)
        assertTrue(questions.single().first.startsWith("This source needs about 69 Mb/s"))
        val back: () -> Unit = dismisses[dismisses.size - 1]
        back()
        reading = FilmSupply.Reading(300.0, 3f)
        p2.buffering(false)
        p2.buffering(true)
        p2.tick()
        assertEquals(1, questions.size)
        assertEquals(1, s2.opens.size)
    }

    @Test fun switchToItAboutACopyLeftSinceLeavesTheOnePlayingNow() {
        val (p, s) = playing()
        s.switchNow = true
        p.buffering(true)
        // While the question is up, the copy fails and the next one plays.
        p.failed(2004, 404)
        assertEquals(null to OpenWhy.FAILED, s.opens.last())
        s.ready(Fixtures.source(3, mbps = 25.0))
        answer(FilmSupply.SWITCH)
        // Kept for the film, but the copy playing now isn't left for a question about another.
        assertEquals("LIGHTER", kept)
        assertEquals(2, s.opens.size)
        assertEquals("k3", p.current?.key)
    }

    @Test fun aRowPickedInThePlayersSourcesPanelCountsAsPickedByHand() {
        val (p, s) = playing(first = Fixtures.source(3, mbps = 25.0))
        val mine = Fixtures.source(4, mbps = 30.0)
        p.pick(mine, beginning = false)
        s.ready(mine)
        p.firstFrame()
        s.switchNow = true
        p.buffering(true)
        assertEquals(FilmSupply.PLAIN_QUESTION, questions.single().first)
        assertEquals(mine to OpenWhy.USER, s.opens.last())
    }

    @Test fun aLighterSourceThatIsStillTooHeavyIsJudgedAfresh() {
        mode = WhenBuffering.LIGHTER
        val middle = Fixtures.source(5, mbps = 50.0)
        val (p, s) = playing(lighter = middle)
        s.switchNow = true
        p.buffering(true)
        assertEquals(middle to OpenWhy.STALLS, s.opens.last())
        s.ready(middle)
        p.firstFrame()
        // The line has dropped to 52 Mb/s: 50 is too much for it now, and 26 is ready.
        reading = FilmSupply.Reading(52.0, 0.95f)
        s.lighterSource = encode
        p.buffering(true)
        assertEquals(encode to OpenWhy.STALLS, s.opens.last())
        assertEquals(listOf(72.0, 52.0), lineShorts)
    }
}
