package app.fireplayer.vod.sources

import app.fireplayer.player.CopyHealth
import app.fireplayer.vod.Hdr
import app.fireplayer.vod.Measured
import app.fireplayer.vod.PlaybackSource
import app.fireplayer.vod.TvFit
import app.fireplayer.vod.TvPlan
import app.fireplayer.vod.play.FilmSupply
import app.fireplayer.vod.play.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Best for this TV" aims at the viewer's preferred bitrate as set: what earlier films were measured to arrive at
 * ([LineRate]) neither lowers the target nor sets a copy aside. A copy's own buffering here still counts against it,
 * until its record expires or later plays of it go well; the data limit and the TV's own checks are as before. The
 * reading is still kept, for the film buffer and the in-play check ([FilmSupply]).
 */
class PreferredFirstTest {

    private val plan = TvPlan(true, listOf(setOf(Hdr.HDR10, Hdr.HDR10_PLUS), setOf(Hdr.HLG)), 2160, emptySet())
    private val now = 1_764_000_000_000L
    private val day = 24 * 3600_000L

    /** Two healthy 4K HDR10 copies, otherwise the same: 35 and 60 Mb/s. */
    private val c35 = Fixtures.source(1, mbps = 35.0)
    private val c60 = Fixtures.source(2, mbps = 60.0)

    private fun input(preferred: Double = 95.0, plan: TvPlan = this.plan, measured: (String) -> Measured? = { null }) =
        RankInput(plan, listOf("en"), measured, 198, episode = false, now = now, preferredMbps = preferred)

    private fun keys(sources: List<PlaybackSource>, input: RankInput) = SourceRank.rank(sources, input).map { it.key }

    @Test
    fun atAPreferred95The60MbsCopyLeadsWithOrWithoutAnEarlier42MbsReading() {
        // No reading kept.
        assertEquals(listOf("k2", "k1"), keys(listOf(c35, c60), input()))
        // An earlier film was measured at 42 Mb/s (kept for the film buffer and the in-play check): the order is the same.
        val store = object : LineRate.Store {
            var text: String? = null
            override fun read() = text
            override fun write(text: String?) {
                this.text = text
            }
        }
        val line = LineRate(store) { now }
        line.seen(42.0)
        assertEquals(42.0, line.current()!!, 0.01)
        val ranked = SourceRank.rank(listOf(c35, c60), input())
        assertEquals(listOf("k2", "k1"), ranked.map { it.key })
        assertEquals("k2", SourceRank.best(ranked)!!.key)
        assertTrue(ranked.all { it.fit == TvFit.LIKELY && it.warning == null })
        // While it plays, the 60 Mb/s file arriving at 42 is still short: Ask / Switch / Keep answers it there.
        assertTrue(FilmSupply.short(60.0, FilmSupply.Reading(lineMbps = 42.0, fillRate = 0.9f)))
    }

    @Test
    fun anExplicit40MbsLimitStillPutsThe35MbsCopyFirst() {
        val capped = plan.copy(maxMbps = 40)
        val ranked = SourceRank.rank(listOf(c60, c35), input(plan = capped))
        assertEquals(listOf("k1", "k2"), ranked.map { it.key })
        val over = ranked.first { it.key == "k2" }
        assertEquals(TvFit.LIKELY_NOT, over.fit)
        assertEquals("Over your 40 Mb/s limit", over.warning)
    }

    @Test
    fun aCopyThatBufferedHereStaysDownAfterAnotherFileArrivesQuickly() {
        val stalled = CopyHealth(watchedMs = 600_000, stalls = 12, stallMs = 120_000)
        val c60key = c60.release!!.measuredKey(null)
        fun record(h: CopyHealth) = { k: String -> if (k == c60key) Measured(null, h, h.at) else null }
        // It buffered here a day ago. Another film since arrived at 160 Mb/s: that says nothing of this file's server.
        val buffered = record(stalled.copy(at = now - day))
        val ranked = SourceRank.rank(listOf(c60, c35), input(measured = buffered))
        assertEquals(listOf("k1", "k2"), ranked.map { it.key })
        assertEquals(TvFit.LIKELY_NOT to SourceRank.BUFFERED, ranked.first { it.key == "k2" }.let { it.fit to it.warning })
        // A week on, the record no longer counts.
        assertEquals(listOf("k2", "k1"), keys(listOf(c35, c60), input(measured = record(stalled.copy(at = now - 8 * day)))))
        // Played well since (two smooth hours): no longer one that keeps stopping to load.
        val recovered = stalled.copy(at = now - day) + CopyHealth(watchedMs = 2 * 3600_000L, at = now - day / 2)
        assertFalse(recovered.buffers)
        assertEquals(listOf("k2", "k1"), keys(listOf(c35, c60), input(measured = record(recovered))))
    }

    @Test
    fun theTvsOwnChecksStillSetCopiesAside() {
        // A Dolby Vision profile 5 copy on a TV without Dolby Vision, however near the preferred bitrate.
        val p5 = c60.copy(facts = c60.facts.copy(hdr = setOf(Hdr.DOLBY_VISION), dvProfile = 5))
        val ranked = SourceRank.rank(listOf(p5, c35), input())
        assertEquals(listOf("k1", "k2"), ranked.map { it.key })
        assertEquals(TvFit.LIKELY_NOT to SourceRank.NEEDS_DV, ranked.first { it.key == "k2" }.let { it.fit to it.warning })
        // Taller than the TV shows.
        val tall = plan.copy(topHeight = 1080)
        assertEquals(TvFit.LIKELY_NOT, SourceRank.fit(c60.facts, tall, null).first)
        assertNull(SourceRank.fit(c60.facts, plan, null).second)
    }
}
