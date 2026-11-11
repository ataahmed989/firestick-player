package app.fireplayer.subs

import app.fireplayer.subs.FakeFilm.Break
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs

/**
 * How quickly auto sync follows a timing change after a break, and whether it ever makes one up, over many made-up
 * films: prints one table to compare versions by (the numbers in the pull request come from here), and fails on any
 * wrong delay. Times are film seconds after the ad ends until the right delay is in use; "talk" is how much of that
 * the speech detector marked as talking.
 */
class ResyncBench {

    private val min = 60_000L
    private val seeds = 1L..12L

    private fun near(expected: Long, actual: Long?, within: Long = 300) = actual != null && abs(expected - actual) <= within

    private class Times {
        val film = ArrayList<Long>()
        val talk = ArrayList<Long>()
        var missed = 0
        fun row(name: String): String {
            if (film.isEmpty()) return "%-48s  never followed (%d)".format(name, missed)
            film.sort(); talk.sort()
            fun q(l: List<Long>, f: Double) = l[minOf(l.size - 1, (l.size * f).toInt())] / 1000
            return "%-48s  film s: median %3d  p75 %3d  max %3d | talk s: median %3d | n=%d, not followed %d".format(
                name, q(film, 0.5), q(film, 0.75), film.last() / 1000, q(talk, 0.5), film.size, missed,
            )
        }
    }

    /** Plays [film] through, and for each ad after the first [skipFirst], how long until its delay was followed. */
    private fun follow(film: FakeFilm, tracker: OffsetTracker, hints: Boolean, into: Times, skipFirst: Int = 1): Playback {
        val p = Playback(film, tracker, formatHints = hints)
        p.play(film.lengthMs)
        val stretches = film.pieces.filter { it.delayMs != null }
        for ((k, ad) in film.ads.withIndex()) {
            if (k < skipFirst) continue
            val want = stretches[k + 1].delayMs!!
            // The first decision after the ad from which the delay in use at the first line after it is the right one.
            val firstLine = film.lines.map { it.startMs + want }.filter { it >= ad.toMs }.minOrNull() ?: continue
            val d = p.decisions.firstOrNull { it.toMs >= ad.toMs && near(want, OffsetTracker.delayAt(it.segments, firstLine)) }
            if (d == null) { into.missed++; continue }
            into.film += d.toMs - ad.toMs
            into.talk += film.talkMs(ad.toMs, d.toMs)
        }
        return p
    }

    /** Every delay in the map is one the film really has somewhere. */
    private fun wrong(film: FakeFilm, p: Playback): Int {
        val truth = film.pieces.mapNotNull { it.delayMs }
        return p.tracker.segments.count { s -> truth.none { near(it, s.delayMs) } }
    }

    @Test
    fun table() {
        val out = ArrayList<String>()
        var wrongDelays = 0
        val wrongWhere = ArrayList<String>()
        fun check(name: String, film: FakeFilm, p: Playback) {
            val w = wrong(film, p)
            if (w > 0) { wrongDelays += w; wrongWhere += "$name: ${p.tracker.segments}" }
        }

        // The same bumper three times (each needs 15 s more): the 2nd and 3rd are the same jump again.
        for (pace in listOf("sparse" to FakeFilm.SPARSE, "talky" to FakeFilm.TALKY)) for (hints in listOf(false, true)) {
            val t = Times()
            for (seed in seeds) {
                val film = FakeFilm.make(seed, minutes = 48, delayMs = 1_400, pace = pace.second,
                    breaks = (1..3).map { Break(it * 12 * min, adMs = 45_000, skipMs = 30_000) })
                check("bumper ${pace.first}", film, follow(film, OffsetTracker(film.subs), hints, t))
            }
            out += t.row("same bumper again, ${pace.first}, format hints $hints")
        }
        // Searching every shift, for comparison.
        for (pace in listOf("sparse" to FakeFilm.SPARSE, "talky" to FakeFilm.TALKY)) {
            val t = Times()
            for (seed in seeds) {
                val film = FakeFilm.make(seed, minutes = 48, delayMs = 1_400, pace = pace.second,
                    breaks = (1..3).map { Break(it * 12 * min, adMs = 45_000, skipMs = 30_000) })
                follow(film, OffsetTracker(film.subs, guessing = false), false, t)
            }
            out += t.row("same bumper again, ${pace.first}, no guesses")
        }
        // A jump remembered from another episode, at the first break.
        for (pace in listOf("sparse" to FakeFilm.SPARSE, "talky" to FakeFilm.TALKY)) {
            val t = Times()
            for (seed in seeds) {
                val film = FakeFilm.make(seed, minutes = 30, delayMs = 1_400, pace = pace.second,
                    breaks = listOf(Break(12 * min, adMs = 45_000, skipMs = 30_000)))
                check("remembered ${pace.first}", film, follow(film, OffsetTracker(film.subs, knownJumps = listOf(15_000L)), false, t, skipFirst = 0))
            }
            out += t.row("remembered jump, first break, ${pace.first}")
        }

        // Things that must never move it, or only to delays the film has; with talking heard well, and badly (a fifth of
        // what's marked is noise, and three lines in ten are missed).
        var moves = 0
        for (pace in listOf(FakeFilm.SPARSE, FakeFilm.TALKY)) for ((noise, missed) in listOf(0.08 to 0.1, 0.2 to 0.3)) for (seed in seeds) {
            // Ads full of talking the subtitles fully allow for, with jumps known that are wrong here.
            for (ad in listOf(45_000L, 90_000L, 3 * min)) {
                val film = FakeFilm.make(seed, minutes = 36, delayMs = 800, pace = pace, noise = noise, missed = missed,
                    breaks = listOf(Break(10 * min, adMs = ad, skipMs = ad), Break(24 * min, adMs = ad, skipMs = ad)))
                val p = Playback(film, OffsetTracker(film.subs, knownJumps = listOf(15_000L, ad, -8_000L)), formatHints = seed % 2 == 0L)
                p.play(film.lengthMs)
                moves += p.moves.size
                check("allowed ads", film, p)
            }
            // Already right, a jump known.
            val film = FakeFilm.make(seed, minutes = 40, delayMs = -2_300, pace = pace, noise = noise, missed = missed)
            val p = Playback(film, OffsetTracker(film.subs, knownJumps = listOf(15_000L, 30_000L)))
            p.play(film.lengthMs)
            moves += p.moves.size
            check("already synced", film, p)
        }
        out += "moves where nothing changed (ads the subtitles allow, already synced; 192 films): $moves"
        // Breaks of other lengths than the jumps tried first (one 1.5 s off), sparse and noisy talk too.
        for (breaks in listOf(
            listOf(Break(12 * min, adMs = 45_000, skipMs = 30_000), Break(24 * min, adMs = 70_000, skipMs = 30_000), Break(36 * min, adMs = 20_000, skipMs = 30_000)),
            listOf(Break(12 * min, adMs = 45_000, skipMs = 30_000), Break(24 * min, adMs = 46_500, skipMs = 30_000)),
        )) for ((noise, missed) in listOf(0.08 to 0.1, 0.2 to 0.3)) for (seed in seeds) {
            val film = FakeFilm.make(seed, minutes = 48, delayMs = 1_400, breaks = breaks, noise = noise, missed = missed)
            val p = Playback(film, OffsetTracker(film.subs, knownJumps = listOf(15_000L, -8_000L)))
            p.play(film.lengthMs)
            check("other lengths", film, p)
        }
        // Legion (2017) S1E2's copy: +1.4 s, then +3.4 s from 12:59, +4.7 s from 20:10, +6.5 s from 29:08.
        val legion = Times()
        val legionOff = Times()
        for (seed in seeds) for (guessing in listOf(true, false)) {
            val film = FakeFilm.make(seed, minutes = 45, delayMs = 1_400, pace = FakeFilm.TALKY, adTalk = false,
                breaks = listOf(Break(12 * min + 59_000 - 1_400, adMs = 2_000), Break(20 * min + 10_000 - 3_400, adMs = 1_300), Break(29 * min + 8_000 - 4_700, adMs = 1_800)))
            val p = follow(film, OffsetTracker(film.subs, guessing = guessing), false, if (guessing) legion else legionOff, skipFirst = 0)
            check("legion", film, p)
        }
        out += legion.row("Legion-like small steps, guesses on")
        out += legionOff.row("Legion-like small steps, guesses off")
        // Seeking both ways across repeated bumpers.
        for (seed in seeds) {
            val film = FakeFilm.make(seed, minutes = 48, delayMs = 1_400, pace = FakeFilm.TALKY,
                breaks = (1..3).map { Break(it * 12 * min, adMs = 45_000, skipMs = 30_000) })
            val p = Playback(film, OffsetTracker(film.subs, knownJumps = listOf(15_000L)))
            p.play(16 * min); p.seek(30 * min); p.play(33 * min); p.seek(5 * min); p.play(14 * min); p.seek(40 * min); p.play(film.lengthMs)
            check("seeking", film, p)
        }
        out += "wrong delays: $wrongDelays ${wrongWhere.take(5)}"
        println("---- resync bench ----\n" + out.joinToString("\n"))
        assertEquals(wrongWhere.joinToString("\n"), 0, wrongDelays)
        assertEquals(0, moves)
    }
}
