package app.fireplayer.subs

import app.fireplayer.subs.FakeFilm.Break
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs

/**
 * Lining downloaded subtitles up against the film's own subtitle track as it loads ([EmbeddedCues], [ReferenceSync]),
 * on made-up films: another language's lines (split, joined and timed a little differently), SDH with sound notes,
 * pictures, forced-only tracks, ad breaks, seeking both ways and subtitles already lined up.
 */
class ReferenceSyncTest {

    private val min = 60_000L
    private val seeds = 1L..8L

    private fun near(expected: Long, actual: Long?, within: Long = 300) = actual != null && abs(expected - actual) <= within

    /** A cue of the film's own track, in film time; [text] null for a picture. */
    class Cue(val startMs: Long, val endMs: Long, val text: String?)

    /** The film's own track: its lines where they are in the film (not in the ads), as [kind] would have them. */
    private fun ownTrack(film: FakeFilm, seed: Long, kind: String): List<Cue> {
        val rnd = Random(seed * 31 + kind.hashCode())
        val placed = ArrayList<Cue>()
        for (p in film.pieces) {
            val d = p.delayMs ?: continue
            for (l in film.lines) {
                val s = l.startMs + d
                if (s >= p.fromMs && s < p.toMs) placed += Cue(s, minOf(l.endMs + d, p.toMs), "Line at $s.")
            }
        }
        if (kind == "same") return placed
        val out = ArrayList<Cue>()
        var i = 0
        while (i < placed.size) {
            var c = placed[i]
            val r = rnd.nextDouble()
            // Another language: some lines split in two, some joined with the next, all timed a little differently.
            if (kind != "pictures" && r < 0.15 && c.endMs - c.startMs > 1_400) {
                val mid = (c.startMs + c.endMs) / 2
                out += Cue(c.startMs, mid - 80, c.text)
                c = Cue(mid + 80, c.endMs, c.text)
            } else if (kind != "pictures" && r < 0.25 && i + 1 < placed.size && placed[i + 1].startMs - c.endMs < 700) {
                c = Cue(c.startMs, placed[i + 1].endMs, c.text)
                i++
            }
            val s = c.startMs + rnd.nextInt(500) - 250
            val e = maxOf(s + 400, c.endMs + rnd.nextInt(800) - 400)
            out += Cue(s, e, if (kind == "pictures") null else c.text)
            // SDH: sound notes in the pauses, and speakers named.
            if (kind == "sdh" && rnd.nextDouble() < 0.3) out += Cue(e + 150, e + 1_400, listOf("[door slams]", "(TENSE MUSIC)", "♪ ♪")[rnd.nextInt(3)])
            i++
        }
        return out.sortedBy { it.startMs }
    }

    /**
     * Plays [film] with its own track [cues] loading [aheadMs] ahead of where it plays, the way the player loads it,
     * and lines [tracker] up against it the way [AutoSync]'s worker thread does (once a second of film played).
     */
    private class Loading(
        val film: FakeFilm,
        val cues: List<Cue>,
        info: ReferenceCues.Info,
        val tracker: OffsetTracker,
        val aheadMs: Long = 120_000,
    ) {
        val source = EmbeddedCues.Source("film")
        val sync = ReferenceSync()
        /** Each decision with where the film was playing when it was made. */
        val decisions = ArrayList<Pair<Long, OffsetTracker.Decision>>()
        var playMs = 0L
            private set
        private var loadedMs = 0L
        private val picture = info.picture

        init {
            source.track(1, info)
            source.tracksKnown = true
        }

        fun play(toMs: Long) {
            while (playMs < toMs) {
                playMs = minOf(playMs + 1_000, toMs)
                load(minOf(playMs + aheadMs, film.lengthMs))
                if (!sync.due(source)) continue
                val snap = source.snapshot() ?: continue
                for (d in sync.advance(snap, tracker)) decisions += playMs to d
            }
        }

        fun seek(toMs: Long) {
            playMs = toMs
            loadedMs = toMs
            source.loaded(toMs)
        }

        private fun load(toMs: Long) {
            while (loadedMs < toMs) {
                val next = minOf(loadedMs + 40, toMs)
                for (c in cues) if (c.startMs >= loadedMs && c.startMs < next) {
                    if (picture) {
                        // A picture with no end of its own, then the screen cleared.
                        source.cue(1, c.startMs, null, 1, emptyList())
                        source.cue(1, c.endMs, null, 0, emptyList())
                    } else {
                        source.cue(1, c.startMs, c.endMs - c.startMs, 1, listOf(c.text!!))
                    }
                }
                source.loaded(next)
                loadedMs = next
            }
        }

        val moves get() = decisions.map { it.second }.filter { it.outcome == OffsetTracker.Outcome.MOVED }
    }

    private fun info(language: String = "fr", forced: Boolean = false, sdh: Boolean = false, picture: Boolean = false) =
        ReferenceCues.Info("3", language, null, forced, sdh, picture)

    /**
     * Every delay found is one the film has, and every line after the first few minutes gets the right one, but for
     * the last few seconds before an ad: in talkative films where a change begins is only known to within a line or two
     * (as with listening), and just before an ad it makes no difference to the next minute.
     */
    private fun checkRight(name: String, film: FakeFilm, tracker: OffsetTracker, fromMs: Long = 0) {
        val truth = film.pieces.mapNotNull { it.delayMs }
        for (s in tracker.segments) assertTrue("$name: made up ${s.delayMs} in ${tracker.segments}", truth.any { near(it, s.delayMs) })
        for (l in film.lines) {
            val d = film.truthAt(l.startMs + film.truthAt(l.startMs))
            val at = l.startMs + d
            if (at < fromMs + 3 * min || film.ads.any { at in it.fromMs - 10_000..it.toMs + 5_000 }) continue
            assertTrue("$name: at ${OffsetTracker.clock(at)} ${tracker.delayAt(at)} for $d, ${tracker.segments}", near(d, tracker.delayAt(at)))
        }
    }

    /**
     * For each ad, how long after it ended the delay was right where the first line after it is (negative: before
     * the ad was on screen, as loading is ahead); null if never.
     */
    private fun followed(film: FakeFilm, run: Loading): List<Long?> {
        val stretches = film.pieces.filter { it.delayMs != null }
        return film.ads.mapIndexed { k, ad ->
            val want = stretches[k + 1].delayMs!!
            val firstLine = film.lines.map { it.startMs + want }.filter { it >= ad.toMs }.minOrNull() ?: return@mapIndexed null
            run.decisions.firstOrNull { (_, d) -> d.toMs >= ad.toMs && near(want, OffsetTracker.delayAt(d.segments, firstLine)) }?.first?.minus(ad.toMs)
        }
    }

    private fun bumpers(seed: Long, pace: FakeFilm.Pace = FakeFilm.TALKY) = FakeFilm.make(seed, minutes = 48, delayMs = 1_400, pace = pace,
        breaks = (1..3).map { Break(it * 12 * min, adMs = 45_000, skipMs = 30_000) })

    @Test
    fun anotherLanguageFollowsEveryBreakBeforeItsOnScreen() {
        for (pace in listOf("sparse" to FakeFilm.SPARSE, "talky" to FakeFilm.TALKY)) {
            val lags = ArrayList<Long>()
            for (seed in seeds) {
                val film = bumpers(seed, pace.second)
                val run = Loading(film, ownTrack(film, seed, "language"), info(), OffsetTracker(film.subs, clean = true))
                run.play(film.lengthMs)
                checkRight("language ${pace.first} seed $seed", film, run.tracker)
                for (lag in followed(film, run)) { assertNotNull("${pace.first} seed $seed: a break never followed", lag); lags += lag!! }
            }
            lags.sort()
            println("film's own subtitles, ${pace.first}, 3 breaks × ${seeds.count()}: right ${-lags.last() / 1000} .. ${-lags.first() / 1000} s " +
                "before the ad ends (median ${-lags[lags.size / 2] / 1000}; negative: after)")
            // Loading two minutes ahead, a break in a talkative episode is followed before it's played; in sparse talk
            // most are, a first break (no jump known yet) sometimes a minute or two after.
            if (pace.second == FakeFilm.TALKY) assertTrue("followed $lags", lags.last() <= 0) else assertTrue("followed $lags", lags[lags.size / 2] <= 0)
        }
    }

    @Test
    fun sdhSoundNotesAreLeftOut() {
        assertFalse(ReferenceCues.talks("[door slams]"))
        assertFalse(ReferenceCues.talks("(TENSE MUSIC)"))
        assertFalse(ReferenceCues.talks("♪ La la la ♪"))
        assertFalse(ReferenceCues.talks("<i>[sighs]</i>"))
        assertTrue(ReferenceCues.talks("JOHN: Where were you?"))
        assertTrue(ReferenceCues.talks("[sighs] Fine."))
        assertTrue(ReferenceCues.talks("- (whispering) Come here."))
        for (seed in seeds) {
            val film = bumpers(seed)
            val cues = ownTrack(film, seed, "sdh")
            val run = Loading(film, cues, info(language = "en", sdh = true), OffsetTracker(film.subs, clean = true))
            run.play(film.lengthMs)
            val notes = cues.count { !ReferenceCues.talks(it.text!!) }
            assertTrue(notes > 50)
            assertEquals(cues.size - notes, run.source.snapshot()!!.cueCount)
            checkRight("sdh seed $seed", film, run.tracker)
            assertTrue(followed(film, run).all { it != null && it <= 0 })
        }
    }

    @Test
    fun picturesWork() {
        for (seed in seeds) {
            val film = bumpers(seed)
            val run = Loading(film, ownTrack(film, seed, "pictures"), info(picture = true), OffsetTracker(film.subs, clean = true))
            run.play(film.lengthMs)
            checkRight("pictures seed $seed", film, run.tracker)
            assertTrue(followed(film, run).all { it != null && it <= 0 })
        }
    }

    @Test
    fun seekingBothWays() {
        for (seed in seeds) {
            val film = bumpers(seed)
            val run = Loading(film, ownTrack(film, seed, "language"), info(), OffsetTracker(film.subs, clean = true))
            run.play(16 * min); run.seek(30 * min); run.play(33 * min); run.seek(5 * min); run.play(14 * min); run.seek(40 * min); run.play(film.lengthMs)
            val truth = film.pieces.mapNotNull { it.delayMs }
            for (s in run.tracker.segments) assertTrue("seek seed $seed: ${run.tracker.segments}", truth.any { near(it, s.delayMs) })
            // Where it played, after the first few minutes, the delay is right.
            for (at in listOf(6 * min, 10 * min, 13 * min, 31 * min, 32 * min, 41 * min, 47 * min)) {
                assertTrue("seek seed $seed at ${at / min} min: ${run.tracker.segments}", near(film.truthAt(at), run.tracker.delayAt(at)))
            }
        }
    }

    @Test
    fun alreadyLinedUpStaysPut() {
        for (seed in seeds) {
            val film = bumpers(seed)
            val known = film.pieces.filter { it.delayMs != null }.map { OffsetTracker.Segment(it.fromMs, it.delayMs!!) }
            val run = Loading(film, ownTrack(film, seed, "language"), info(), OffsetTracker(film.subs, known, knownJumps = listOf(15_000L, -8_000L), clean = true))
            run.play(film.lengthMs)
            assertEquals("seed $seed: ${run.moves}", 0, run.moves.size)
            checkRight("synced seed $seed", film, run.tracker)
        }
    }

    @Test
    fun adsWithNoBreakInTheSubtitlesMoveNothing() {
        for (seed in seeds) for (ad in listOf(45_000L, 3 * min)) {
            val film = FakeFilm.make(seed, minutes = 36, delayMs = 800, pace = FakeFilm.TALKY,
                breaks = listOf(Break(10 * min, adMs = ad, skipMs = ad), Break(24 * min, adMs = ad, skipMs = ad)))
            val run = Loading(film, ownTrack(film, seed, "language"), info(), OffsetTracker(film.subs, knownJumps = listOf(15_000L, ad, -8_000L), clean = true))
            run.play(film.lengthMs)
            assertEquals("seed $seed ad $ad: ${run.moves}", 0, run.moves.size)
            checkRight("allowed ads seed $seed", film, run.tracker)
        }
    }

    @Test
    fun forcedOnlyTracksAreNeverUsed() {
        val source = EmbeddedCues.Source("film")
        source.track(1, info(language = "en", forced = true))
        source.tracksKnown = true
        for (s in 0 until 600) source.loaded(s * 1_000L)
        for (k in 0 until 200) source.cue(1, 10_000L + k * 2_500, 2_000, 1, listOf("Words $k."))
        assertNull(source.snapshot())
        assertFalse(source.hasCandidates())
        // A track not marked forced but with only a few lines (signs) is left once enough of the film says so.
        val signs = EmbeddedCues.Source("film")
        signs.track(1, info(language = "en"))
        signs.tracksKnown = true
        for (k in 0 until 5) signs.cue(1, 30_000L + k * 60_000, 2_000, 1, listOf("SIGN $k"))
        for (s in 0 until 60) signs.loaded(s * 1_000L)
        assertNotNull(signs.snapshot())
        for (s in 60 until 600) signs.loaded(s * 1_000L)
        assertNull(signs.snapshot())
        assertTrue(signs.hasCandidates())
    }

    @Test
    fun noTrackOfItsOwn() {
        // A film whose file has no subtitles: nothing to line up against, so auto sync listens as before.
        val source = EmbeddedCues.Source("film")
        source.tracksKnown = true
        for (s in 0 until 600) source.loaded(s * 1_000L)
        assertNull(source.snapshot())
        assertFalse(source.hasCandidates())
    }

    @Test
    fun theTrackChosen() {
        fun track(info: ReferenceCues.Info, cues: Int) = ReferenceCues(info).apply {
            for (k in 0 until cues) add(5_000L + k * 3_000, 2_000, 1, listOf("Words."))
        }
        val forced = track(info(forced = true), 400)
        val sdh = track(info(sdh = true), 300)
        val pictures = track(info(picture = true), 300)
        val regular = track(info(language = "de"), 100)
        val covered = 20 * min
        // Regular before SDH (as asked), text before pictures, the one with most lines among equals; never forced.
        assertEquals(regular, ReferenceCues.choose(listOf(forced, sdh, pictures, regular), covered))
        assertEquals(pictures, ReferenceCues.choose(listOf(forced, sdh, pictures), covered))
        assertEquals(sdh, ReferenceCues.choose(listOf(forced, sdh), covered))
        assertEquals(regular, ReferenceCues.choose(listOf(track(info(language = "es"), 60), regular), covered))
        assertNull(ReferenceCues.choose(listOf(forced), covered))
        assertTrue(ReferenceCues.forcedLabel("English (Forced)"))
        assertTrue(ReferenceCues.forcedLabel("Signs & Songs"))
        assertTrue(ReferenceCues.hearingImpairedLabel("English SDH"))
        assertTrue(ReferenceCues.hearingImpairedLabel("English [CC]"))
        assertFalse(ReferenceCues.hearingImpairedLabel("English"))
    }

    @Test
    fun aPictureNeverClearedEndsAtTheNext() {
        val cues = ReferenceCues(info(picture = true))
        cues.add(1_000, null, 1, emptyList())
        cues.add(3_000, null, 1, emptyList())
        cues.add(4_500, null, 0, emptyList())
        cues.add(30_000, null, 1, emptyList())
        val got = cues.intervals(100_000).map { it[0] to it[1] }
        assertEquals(listOf(1_000L to 3_000L, 3_000L to 4_500L, 30_000L to 40_000L), got)
    }
}
