package app.fireplayer.subs

import app.fireplayer.subs.FakeFilm.Break
import org.junit.Test
import java.util.Random
import kotlin.math.abs

/**
 * How fast lining up against the film's own subtitles is, on made-up episodes shaped like the Legion ones tested on the
 * Fire TV Stick 4K Max: seconds from opening until the delay is right for the next line, after a skip ahead and back,
 * after rewinding into a stretch skipped over, and around each break. The film loads the way the Max loaded it: four
 * times as fast as it plays until it's 90 s ahead (so after opening or a jump, the next minute is there in seconds).
 * Prints a table; asserts nothing but that it never settles on a wrong delay (see [ReferenceSyncTest] for the rules).
 */
class ReferenceBench {

    private val min = 60_000L
    private val VERBOSE = System.getenv("BENCH_VERBOSE") != null
    private val seeds = 1L..12L

    private class Cue(val startMs: Long, val endMs: Long)

    /** The film's own track in another language: lines split, joined and timed a little differently. */
    private fun ownTrack(film: FakeFilm, seed: Long): List<Cue> {
        val rnd = Random(seed * 31 + 7)
        val placed = ArrayList<Cue>()
        for (p in film.pieces) {
            val d = p.delayMs ?: continue
            for (l in film.lines) {
                val s = l.startMs + d
                if (s >= p.fromMs && s < p.toMs) placed += Cue(s, minOf(l.endMs + d, p.toMs))
            }
        }
        val out = ArrayList<Cue>()
        var i = 0
        while (i < placed.size) {
            var c = placed[i]
            val r = rnd.nextDouble()
            if (r < 0.15 && c.endMs - c.startMs > 1_400) {
                val mid = (c.startMs + c.endMs) / 2
                out += Cue(c.startMs, mid - 80)
                c = Cue(mid + 80, c.endMs)
            } else if (r < 0.25 && i + 1 < placed.size && placed[i + 1].startMs - c.endMs < 700) {
                c = Cue(c.startMs, placed[i + 1].endMs)
                i++
            }
            val s = c.startMs + rnd.nextInt(500) - 250
            out += Cue(s, maxOf(s + 400, c.endMs + rnd.nextInt(800) - 400))
            i++
        }
        return out.sortedBy { it.startMs }
    }

    /** Plays [film] with its track loading as on the Max, lining [tracker] up against it as AutoSync's thread does. */
    private class Run(val film: FakeFilm, val cues: List<Cue>, val tracker: OffsetTracker) {
        val source = EmbeddedCues.Source("film")
        val sync = ReferenceSync()
        val decisions = ArrayList<Pair<Long, OffsetTracker.Decision>>()
        var playMs = 0L
            private set
        private var loadedMs = 0L
        private var next = 0

        init {
            source.track(1, ReferenceCues.Info("3", "es", null, false, false, false))
            source.tracksKnown = true
        }

        /** Plays a second at a time until [until] says so (true) or [toMs]; the seconds played, or null if never. */
        fun playUntil(toMs: Long, until: () -> Boolean): Long? {
            val from = playMs
            if (until()) return 0
            while (playMs < toMs) {
                playMs += 1_000
                load(minOf(minOf(playMs + AHEAD_MS, loadedMs + SPEED * 1_000), film.lengthMs))
                if (sync.due(source)) source.snapshot()?.let { for (d in sync.advance(it, tracker)) decisions += playMs to d }
                if (until()) return (playMs - from) / 1000
            }
            return null
        }

        fun play(toMs: Long) { playUntil(toMs) { false } }

        fun seek(toMs: Long) {
            playMs = toMs
            loadedMs = toMs
            next = cues.indexOfFirst { it.startMs >= toMs }.let { if (it < 0) cues.size else it }
            source.loaded(toMs)
        }

        private fun load(toMs: Long) {
            while (loadedMs < toMs) {
                val step = minOf(loadedMs + 40, toMs)
                while (next < cues.size && cues[next].startMs < step) {
                    val c = cues[next++]
                    if (c.startMs >= loadedMs) source.cue(1, c.startMs, c.endMs - c.startMs, 1, listOf("Words."))
                }
                source.loaded(step)
                loadedMs = step
            }
        }

        /** Whether the delay is right for the next line to show from where it plays (within 0.3 s). */
        fun rightForNextLine(): Boolean {
            val d = film.truthAt(playMs)
            val line = film.lines.map { it.startMs + d }.filter { it >= playMs }.minOrNull() ?: return true
            val got = tracker.delayAt(line) ?: return false
            return abs(got - film.truthAt(line)) <= 300
        }

        /** Seconds from now until the next line shows (film time). */
        fun toNextLine(): Long {
            val d = film.truthAt(playMs)
            return ((film.lines.map { it.startMs + d }.filter { it >= playMs }.minOrNull() ?: playMs) - playMs) / 1000
        }

        companion object {
            const val AHEAD_MS = 90_000L
            const val SPEED = 4
        }
    }

    private fun legion(seed: Long, pace: FakeFilm.Pace) = FakeFilm.make(seed, minutes = 48, delayMs = 1_300, pace = pace,
        breaks = listOf(Break(12 * min, adMs = 45_000, skipMs = 41_300), Break(24 * min, adMs = 30_000, skipMs = 28_700),
            Break(36 * min, adMs = 60_000, skipMs = 58_200)))

    private fun row(name: String, values: List<Long?>) {
        val got = values.filterNotNull().sorted()
        val never = values.count { it == null }
        val text = if (got.isEmpty()) "never" else
            "median ${got[got.size / 2]} s, worst ${got.last()} s, best ${got.first()} s" + if (never > 0) ", $never never" else ""
        println("  %-58s %s".format(name, text))
    }

    @Test
    fun timings() {
        println("Film's own subtitles (another language), loading 4× until 90 s ahead, ${seeds.count()} episodes each:")
        for ((paceName, pace) in listOf("talky" to FakeFilm.TALKY, "sparse" to FakeFilm.SPARSE)) {
            println(" $paceName episodes")
            val open = ArrayList<Long?>()
            val openLead = ArrayList<Long?>()
            val ahead = ArrayList<Long?>()
            val back = ArrayList<Long?>()
            val gap = ArrayList<Long?>()
            val breaks = ArrayList<Long?>()
            var wrong = 0
            for (seed in seeds) {
                val film = legion(seed, pace)
                val cues = ownTrack(film, seed)
                // Opening a new episode: seconds until the delay is right for the next line, and how many lines showed wrong.
                val run = Run(film, cues, OffsetTracker(film.subs, knownJumps = listOf(3_700L, 1_300L), clean = true))
                val firstLine = film.lines.first().startMs + 1_300
                val t = run.playUntil(10 * min) { run.tracker.delayAt(firstLine)?.let { abs(it - 1_300) <= 300 } == true }
                open += t
                openLead += t?.let { (firstLine / 1000) - it }
                // Skips as on the Max: from 8:20 ten minutes ahead, then five back, then back into the stretch skipped.
                run.play(8 * min + 20_000)
                run.seek(18 * min + 20_000)
                val n0 = run.decisions.size
                ahead += run.playUntil(25 * min) { run.rightForNextLine() }.also { a ->
                    if (VERBOSE && (a ?: 99) > 12) {
                        println("  seed $seed ahead $a: " + run.decisions.drop(n0).filter { it.second.outcome != OffsetTracker.Outcome.WAIT }.take(12).joinToString("\n    ") { "${it.first / 1000}: ${it.second}" })
                    }
                }
                run.play(19 * min + 20_000)
                run.seek(14 * min + 20_000)
                back += run.playUntil(20 * min) { run.rightForNextLine() }
                run.play(15 * min + 30_000)
                run.seek(11 * min + 30_000)
                gap += run.playUntil(14 * min) { run.rightForNextLine() }
                // Playing through the breaks from the start: seconds after each ad ends (negative: before).
                val through = Run(film, cues, OffsetTracker(film.subs, knownJumps = listOf(3_700L, 1_300L), clean = true))
                val ads = film.ads
                for ((k, ad) in ads.withIndex()) {
                    through.play(maxOf(through.playMs, ad.fromMs - 3 * min))
                    val want = film.pieces.filter { it.delayMs != null }[k + 1].delayMs!!
                    val firstAfter = film.lines.map { it.startMs + want }.filter { it >= ad.toMs }.minOrNull()!!
                    val s = through.playUntil(ad.toMs + 4 * min) { through.tracker.delayAt(firstAfter)?.let { abs(it - want) <= 300 } == true }
                    breaks += s?.let { (through.playMs - ad.toMs) / 1000 }
                }
                through.play(film.lengthMs)
                val truth = film.pieces.mapNotNull { it.delayMs }
                wrong += (run.tracker.segments + through.tracker.segments).count { s -> truth.none { abs(it - s.delayMs) <= 300 } }
            }
            row("opening: synced after (s)", open)
            row("opening: synced before the first line by (s)", openLead)
            row("skip 10 min ahead: right for the next line after (s)", ahead)
            row("skip 5 min back: right for the next line after (s)", back)
            row("back into the stretch skipped: right after (s)", gap)
            row("break: right after the ad ends (s, negative before)", breaks)
            println("  wrong delays settled on: $wrong")
        }
    }
}
