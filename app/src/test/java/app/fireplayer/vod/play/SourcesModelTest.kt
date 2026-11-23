package app.fireplayer.vod.play

import app.fireplayer.vod.Addon
import app.fireplayer.vod.AddonState
import app.fireplayer.vod.Attempt
import app.fireplayer.vod.Failure
import app.fireplayer.vod.FailureKind
import app.fireplayer.vod.Hdr
import app.fireplayer.vod.Measured
import app.fireplayer.vod.OpenWhy
import app.fireplayer.vod.Readiness
import app.fireplayer.vod.SourceGroup
import app.fireplayer.vod.SourceTarget
import app.fireplayer.vod.TvFit
import app.fireplayer.vod.TvPlan
import app.fireplayer.vod.play.SourcesModel.Section
import app.fireplayer.vod.sources.RankInput
import app.fireplayer.vod.sources.SourceRank
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Sources panel's list: its sections and their order, the SD rule, the Best row and its reason, the rows' marks. */
class SourcesModelTest {
    private val target = SourceTarget(Fixtures.film)

    /** A 4K IMAX Dolby Vision remux, cached. */
    private fun imaxCopy(i: Int) = Fixtures.source(i).let {
        it.copy(facts = it.facts.copy(hdr = setOf(Hdr.DOLBY_VISION, Hdr.HDR10), origin = "REMUX"),
            offers = listOf(it.offers[0].copy(text = "The.Film.2026.IMAX.2160p.REMUX.DV")))
    }

    private val imax = (41..42).map { imaxCopy(it) }
    private val uhd = (1..7).map { Fixtures.source(it) }
    private val fhd = (11..12).map { Fixtures.source(it, group = SourceGroup.FHD, height = 1080) }
    private val hd = (21..23).map { Fixtures.source(it, group = SourceGroup.LOWER, height = 720) }
    private val notReady = Fixtures.source(31, readiness = Readiness.NOT_READY, group = SourceGroup.NOT_ON_RD)
    private val list = Fixtures.list(target, imax + uhd + fhd + hd + notReady, lastUsed = fhd[1])

    private val plan = TvPlan(true, listOf(setOf(Hdr.HDR10, Hdr.HDR10_PLUS), setOf(Hdr.HLG)), 2160, emptySet())
    private fun input(lineMbps: Double? = null) = RankInput(plan, listOf("en"), { null }, 173, episode = false, lineMbps = lineMbps)

    private fun sd(i: Int, readiness: Readiness = Readiness.PROBABLY_READY, height: Int = 480) =
        Fixtures.source(i, readiness = readiness, height = height,
            group = if (readiness == Readiness.NOT_READY) SourceGroup.NOT_ON_RD else SourceGroup.LOWER)

    private fun keys(items: List<SourcesModel.Item>) = items.map { it.key }
    private fun List<SourcesModel.Item>.at(key: String) = first { it.key == key }

    @Test
    fun theBestRowThenSectionsInOrderEachWithItsCount() {
        val items = SourcesModel.items(list, 0, emptySet())
        assertEquals(
            listOf("note:status", "note:totals", "note:ready", SourcesModel.BEST_KEY,
                "head:IMAX", "src:IMAX:k41", "src:IMAX:k42",
                "head:UHD") + (1..5).map { "src:UHD:k$it" } + listOf("more:UHD",
                "head:FHD", "src:FHD:k11", "src:FHD:k12",
                "head:HD", "more:HD",
                "head:NOT_CACHED", "note:NOT_CACHED", "more:NOT_CACHED"),
            keys(items),
        )
        assertEquals(listOf("4K IMAX · 2", "4K · 7", "1080p · 2", "720p · 3", "Not cached yet · 1"),
            items.filter { it.kind == SourcesModel.Kind.HEADING }.map { it.label })
        assertEquals("Show 2 more", items.at("more:UHD").label)
        // 720p and Not cached yet start closed with one "Show N" row.
        assertEquals("Show 3", items.at("more:HD").label)
        assertEquals("Show 1", items.at("more:NOT_CACHED").label)
        // A row: quality · HDR kind · made from · size, then the quieter line ending with the add-on.
        assertEquals("4K · HDR10 · WEB · 29.3 GB", items.at("src:UHD:k1").label)
        assertEquals("HEVC 10-bit · DD+ 5.1 · EN · Torrentio", items.at("src:UHD:k1").note)
        assertEquals("4K IMAX · DV · Remux · 29.3 GB", items.at("src:IMAX:k41").label)
        // Every row's text is the add-ons' words, never a link.
        assertFalse(items.any { (it.label + it.note + it.value).contains("invalid") })
    }

    @Test
    fun theBestRowIsTheCopyPlayUsesWithWhyAndLastUsedIsOnlyAMarkOnItsOwnRow() {
        val items = SourcesModel.items(list, 0, emptySet())
        val best = items.at(SourcesModel.BEST_KEY)
        assertEquals("Best for this TV", best.label)
        assertEquals("k41", best.source!!.key)
        assertEquals("4K IMAX · Dolby Vision · Remux · cached", best.note)
        assertNull(best.value)
        // Its own row says Best; the last used one says so where it sits, not in a row above the best.
        assertEquals("Best", items.at("src:IMAX:k41").value)
        assertEquals("Last used", items.at("src:FHD:k12").value)
        assertEquals(listOf("src:FHD:k12"), items.filter { it.value?.contains("Last used") == true }.map { it.key })
        assertEquals(1, items.count { it.source?.key == "k12" })
    }

    @Test
    fun openingASectionListsAllOfIt() {
        val k = keys(SourcesModel.items(list, 0, setOf(Section.UHD, Section.HD)))
        assertTrue(k.containsAll((1..7).map { "src:UHD:k$it" } + (21..23).map { "src:HD:k$it" }))
        assertFalse(k.contains("more:UHD"))
        assertFalse(k.contains("more:HD"))
        assertTrue(k.contains("more:NOT_CACHED"))
    }

    @Test
    fun theFirstKindListedStartsOpenEvenWhenItsKindStartsClosed() {
        val k = keys(SourcesModel.items(Fixtures.list(target, hd + notReady), 0, emptySet()))
        assertTrue(k.containsAll((21..23).map { "src:HD:k$it" }))
        assertFalse(k.contains("more:HD"))
        assertTrue(k.contains("more:NOT_CACHED"))
    }

    @Test
    fun rowMarksPlayingTriedBestLastUsedAndPlayed() {
        val played = uhd[3].copy(measured = Measured(null, null, 5L))
        val l = list.copy(sources = list.sources.map { if (it.key == played.key) played else it })
        val attempts = listOf(Attempt(uhd[1], 0L, OpenWhy.START, Failure(FailureKind.FILE)))
        val items = SourcesModel.items(l, 0, emptySet(), attempts, playingKey = "k3", time = { "20:41" }, day = { "Nov 12" })
        assertEquals("Tried 20:41\ndidn't play", items.at("src:UHD:k2").value)
        assertEquals("Playing", items.at("src:UHD:k3").value)
        assertTrue(items.at("src:UHD:k3").chosen)
        assertEquals("✓ played Nov 12", items.at("src:UHD:k4").value)
        assertNull(items.at("src:UHD:k5").value)
        // Playing the best: its row at the top is the chosen one too.
        val onBest = SourcesModel.items(l, 0, emptySet(), playingKey = "k41")
        assertTrue(onBest.at(SourcesModel.BEST_KEY).chosen)
        assertEquals("Playing", onBest.at(SourcesModel.BEST_KEY).value)
        assertEquals("Playing\nBest", onBest.at("src:IMAX:k41").value)
    }

    @Test
    fun inThePlayerTheCopyPlayingComesFirstWithItsDetailsThenTheBest() {
        val items = SourcesModel.items(list, 0, emptySet(), playingKey = "k3")
        val keys = keys(items)
        val at = keys.indexOf(SourcesModel.PLAYING_KEY)
        assertTrue(at >= 0)
        assertEquals(at + 1, keys.indexOf(SourcesModel.BEST_KEY))
        val row = items.at(SourcesModel.PLAYING_KEY)
        assertEquals("k3", row.source?.key)
        assertEquals(SourcesModel.PLAYING_NOW, row.value)
        assertTrue(row.chosen)
        assertEquals(items.at("src:UHD:k3").label, row.label)
        // Nothing playing (the title page): no such row.
        assertFalse(keys(SourcesModel.items(list, 0, emptySet())).contains(SourcesModel.PLAYING_KEY))
    }

    @Test
    fun whileLookingTheBestIsSoFarAndTheStatusSaysWho() {
        val soFar = list.copy(states = mapOf(Addon.TORRENTIO to AddonState.Answered(13), Addon.MEDIAFUSION to AddonState.Asking(0, 25_000)))
        val items = SourcesModel.items(soFar, 9_000, emptySet())
        assertEquals("Torrentio ✓ 13 · MediaFusion still looking 9 s of 25", items[0].label)
        assertEquals("Best so far", items.at(SourcesModel.BEST_KEY).label)
    }

    @Test
    fun emptyAndUnaskedLists() {
        assertEquals(listOf("note:looking"), keys(SourcesModel.items(null, 0, emptySet())))
        val none = SourcesModel.items(Fixtures.list(target, emptyList()), 0, emptySet())
        assertEquals(listOf("note:status", "note:none"), keys(none))
        assertEquals("No sources found.", none[1].label)
        // Nothing cached: no Best row, the copies to fetch closed under their heading.
        val fetch = keys(SourcesModel.items(Fixtures.list(target, listOf(notReady)), 0, emptySet()))
        assertFalse(fetch.contains(SourcesModel.BEST_KEY))
        assertEquals(listOf("head:NOT_CACHED", "note:NOT_CACHED", "more:NOT_CACHED"), fetch.drop(3))
    }

    // ---- The SD rule

    @Test
    fun sdCopiesGoWheneverTheTitleHasACopyAt720pOrAbove() {
        assertTrue(SourceRank.isSd(sd(51)))
        assertTrue(SourceRank.isSd(sd(51, height = 576)))
        assertFalse(SourceRank.isSd(Fixtures.source(1, height = 720)))
        assertFalse(SourceRank.isSd(Fixtures.source(1, height = null, group = SourceGroup.OTHER)))
        assertEquals(listOf("k1"), SourceRank.withoutSd(listOf(Fixtures.source(1), sd(51))).map { it.key })
        // Cached SD beside a 4K copy not cached yet: the SD copy goes and isn't played by itself (Play asks to fetch).
        val ranked = SourceRank.rank(listOf(sd(51), Fixtures.source(52, readiness = Readiness.NOT_READY, group = SourceGroup.NOT_ON_RD)), input())
        assertEquals(listOf("k52"), ranked.map { it.key })
        assertNull(SourceRank.best(ranked))
        // A cinema recording or a copy of unknown resolution isn't "720p or above": SD stays beside them.
        val cam = Fixtures.source(53, height = 1080, group = SourceGroup.CINEMA)
        val other = Fixtures.source(54, height = null, group = SourceGroup.OTHER)
        assertEquals(3, SourceRank.withoutSd(listOf(sd(51), cam, other)).size)
    }

    @Test
    fun sdIsNeverListedBestOrLastUsedBesideHdButIsLowerQualityWhenThatsAllThereIs() {
        val ranked = SourceRank.rank(listOf(sd(51), sd(52, Readiness.NOT_READY), Fixtures.source(1, group = SourceGroup.FHD, height = 1080)), input())
        assertEquals(listOf("k1"), ranked.map { it.key })
        val l = Fixtures.list(target, ranked, best = SourceRank.best(ranked))
        val k = keys(SourcesModel.items(l, 0, emptySet()))
        assertFalse(k.any { it.contains("k51") || it.contains("k52") || it.contains("LOWER") })
        assertEquals("Best for this TV · 1080p · HDR10 · WEB · 29.3 GB · from Torrentio", PlayRules.sourceLine(l))

        // Nothing at 720p or above: the SD copies stay, under one open "Lower quality" (uncached ones still apart).
        val onlySd = SourceRank.rank(listOf(sd(51), sd(53, height = 576), sd(52, Readiness.NOT_READY)), input())
        assertEquals(3, onlySd.size)
        val best = SourceRank.best(onlySd)!!
        val items = SourcesModel.items(Fixtures.list(target, onlySd, best = best), 0, emptySet())
        assertEquals(listOf(SourcesModel.BEST_KEY, "head:NOT_CACHED", "note:NOT_CACHED", "more:NOT_CACHED", "head:LOWER", "src:LOWER:${onlySd[0].key}", "src:LOWER:${onlySd[1].key}"),
            keys(items).drop(3))
        assertEquals("Lower quality · 2", items.at("head:LOWER").label)
        assertTrue(SourceRank.isSd(best))
    }

    // ---- The Best row's reason

    @Test
    fun theReasonSaysQualityHdrMadeFromAndWhetherCachedOrPlayedHere() {
        assertEquals("4K IMAX · Dolby Vision · Remux · cached", SourcesModel.reason(imax[0], list))
        assertEquals("1080p · HDR10 · WEB · played here", SourcesModel.reason(fhd[0].copy(fit = TvFit.MEASURED_OK), list))
        val sdr = fhd[0].let { it.copy(facts = it.facts.copy(hdr = emptySet(), origin = "BluRay"), readiness = Readiness.UNKNOWN) }
        assertEquals("1080p · BluRay", SourcesModel.reason(sdr, list))
    }

    @Test
    fun aProfile7CopyThisTvPlayedAsItsBaseLayerIsHdr10InTheReason() {
        val asBase = Measured(app.fireplayer.player.MediaSpec(hdr = "HDR10", dvBaseLayer = 7), null, null)
        val p7 = Fixtures.source(61).let {
            it.copy(facts = it.facts.copy(hdr = setOf(Hdr.DOLBY_VISION, Hdr.HDR10), dvProfile = 7, origin = "REMUX"), fit = TvFit.MEASURED_OK, measured = asBase)
        }
        assertEquals("4K · HDR10 · Remux · played here", SourcesModel.reason(p7, list))
        assertEquals("4K · Dolby Vision · Remux · played here", SourcesModel.reason(p7.copy(measured = null), list))
    }

    @Test
    fun aLighterCopyChosenForThisConnectionSaysSo() {
        val copies = listOf(Fixtures.source(1, mbps = 26.0), Fixtures.source(2, mbps = 69.0), Fixtures.source(3, mbps = 87.0))
        val slow = SourceRank.rank(copies, input(lineMbps = 72.0))
        val best = SourceRank.best(slow)!!
        assertEquals("k1", best.key)
        assertEquals("Lighter 4K: your connection keeps up with it", SourcesModel.reason(best, Fixtures.list(target, slow, best = best)))
        // Nothing measured: the usual reason.
        val fast = SourceRank.rank(copies, input())
        assertEquals("4K · HDR10 · WEB · cached", SourcesModel.reason(SourceRank.best(fast)!!, Fixtures.list(target, fast)))
        // Every copy too heavy for the line: no "lighter", and the best's warning ends the reason.
        val heavy = SourceRank.rank(copies.drop(1), input(lineMbps = 72.0))
        val reason = SourcesModel.reason(SourceRank.best(heavy)!!, Fixtures.list(target, heavy))
        assertTrue(reason, reason.startsWith("4K · HDR10 · WEB · cached · ⚠ Needs "))
    }

    @Test
    fun detailsHaveTheFactsAndEachAddonsWordsButNoLink() {
        val both = Fixtures.source(5, also = Addon.MEDIAFUSION)
        val d = SourcesModel.details(both) { "Nov 12" }
        val text = d.joinToString("\n") { "${it.first}: ${it.second}" }
        assertTrue(text.contains("Torrentio says: Release.5.2160p.WEB-DL"))
        assertTrue(text.contains("MediaFusion says: Release 5"))
        assertTrue(text.contains("Offered by: Both add-ons"))
        assertTrue(text.contains("Probably on your debrid service"))
        assertTrue(text.contains("Audio languages: EN"))
        val multi = both.copy(facts = both.facts.copy(languages = listOf("multi", "en", "fr")))
        assertTrue(SourcesModel.details(multi) { "" }.contains("Audio languages" to "Multi, EN, FR"))
        assertFalse(text.contains("invalid"))
        assertTrue(SourcesModel.asksFirst(notReady))
        assertFalse(SourcesModel.asksFirst(both))
    }
}
