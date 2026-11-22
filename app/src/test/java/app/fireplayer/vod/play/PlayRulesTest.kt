package app.fireplayer.vod.play

import app.fireplayer.player.MediaSpec
import app.fireplayer.vod.Addon
import app.fireplayer.vod.AddonState
import app.fireplayer.vod.Episode
import app.fireplayer.vod.EpisodeRef
import app.fireplayer.vod.Hdr
import app.fireplayer.vod.LastPlayed
import app.fireplayer.vod.LookupWhy
import app.fireplayer.vod.Measured
import app.fireplayer.vod.Position
import app.fireplayer.vod.Readiness
import app.fireplayer.vod.SourceGroup
import app.fireplayer.vod.SourceTarget
import app.fireplayer.vod.StartAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayRulesTest {
    private val target = SourceTarget(Fixtures.film)

    @Test
    fun theTitlePageAsksSoonerWithAResumePointOrFromNextEpisodes() {
        assertEquals(LookupWhy.PAGE, PlayRules.pageLookup(hasResumePoint = false, soon = false))
        assertEquals(LookupWhy.PAGE_SOON, PlayRules.pageLookup(hasResumePoint = true, soon = false))
        assertEquals(LookupWhy.PAGE_SOON, PlayRules.pageLookup(hasResumePoint = false, soon = true))
    }

    @Test
    fun startIsExplicitAndFollowsTheResumeRule() {
        val resumable = Position(600_000, 7_200_000, 0)
        assertEquals(StartAt.At(600_000), PlayRules.startAt(resumable, fromBeginning = false))
        assertEquals(StartAt.Beginning, PlayRules.startAt(resumable, fromBeginning = true))
        // Under 30 s, or in the last 3 minutes: from the beginning.
        assertEquals(StartAt.Beginning, PlayRules.startAt(Position(20_000, 7_200_000, 0), false))
        assertEquals(StartAt.Beginning, PlayRules.startAt(Position(7_100_000, 7_200_000, 0), false))
        assertEquals(StartAt.Beginning, PlayRules.startAt(null, false))
        assertEquals(StartAt.At(600_000), PlayRules.startAt(LastPlayed(null, resumable)))
        assertEquals(StartAt.Beginning, PlayRules.startAt(LastPlayed(null, resumable, markedWatched = true)))
    }

    @Test
    fun theStripSaysWhoIsStillLookingAndWhatTheKeysDo() {
        val now = 100_000L
        val a = Fixtures.source(1)
        val list = Fixtures.list(target, listOf(a), states = mapOf(
            Addon.TORRENTIO to AddonState.Answered(33),
            Addon.MEDIAFUSION to AddonState.Asking(since = now - 9_000, deadline = now + 16_000),
        ))
        assertEquals("Torrentio ✓ 33 · MediaFusion still looking 9 s of 25 · OK: play best so far · Back: cancel", PlayRules.strip(list, now))
        assertEquals("Waiting for MediaFusion · 4 s", PlayRules.waitingLabel(list, now, pressedAt = now - 4_000))
        assertEquals(9f / 25f, PlayRules.waitFraction(list, now), 0.001f)
        // Nothing to play yet: no "OK" in the strip.
        val none = Fixtures.list(target, emptyList(), states = list.states, best = null)
        assertFalse(PlayRules.strip(none, now).contains("OK"))
    }

    @Test
    fun stateWordsForEveryAddonState() {
        assertNull(PlayRules.stateText(Addon.TORRENTIO, AddonState.NotSet, 0))
        assertEquals("MediaFusion didn't answer", PlayRules.stateText(Addon.MEDIAFUSION, AddonState.Failed("x"), 0))
        assertEquals("MediaFusion busy", PlayRules.stateText(Addon.MEDIAFUSION, AddonState.Busy(5), 0))
        assertEquals("Torrentio still looking", PlayRules.stateText(Addon.TORRENTIO, AddonState.NotAsked, 0))
    }

    @Test
    fun theWaitEndsWhenCompleteOrEveryLimitHasPassed() {
        val now = 100_000L
        val asking = Fixtures.list(target, emptyList(), states = mapOf(
            Addon.TORRENTIO to AddonState.Answered(0),
            Addon.MEDIAFUSION to AddonState.Asking(since = now - 25_000, deadline = now),
        ))
        assertFalse(PlayRules.waitedOut(asking, now))
        assertFalse(PlayRules.waitedOut(asking, now + PlayRules.GRACE_MS))
        assertTrue(PlayRules.waitedOut(asking, now + PlayRules.GRACE_MS + 1))
        assertTrue(PlayRules.waitedOut(Fixtures.list(target, emptyList()), now))
        // An add-on not asked yet (budget) holds the wait like one asking.
        val notAsked = Fixtures.list(target, emptyList(), states = mapOf(Addon.TORRENTIO to AddonState.NotAsked))
        assertFalse(PlayRules.waitedOut(notAsked, now))
    }

    @Test
    fun theSourceLineNamesWhatPlayWouldStartAndWhereItsFactsCameFrom() {
        val best = Fixtures.source(1)
        assertNull(PlayRules.sourceLine(null))
        val done = Fixtures.list(target, listOf(best))
        assertEquals("Best for this TV · 4K · HDR10 · WEB · 29.3 GB · from Torrentio", PlayRules.sourceLine(done))
        val soFar = done.copy(states = mapOf(Addon.TORRENTIO to AddonState.Answered(1), Addon.MEDIAFUSION to AddonState.Asking(0, 25_000)))
        assertTrue(PlayRules.sourceLine(soFar)!!.startsWith("Best so far · "))
        val last = Fixtures.source(2, also = Addon.MEDIAFUSION)
        assertTrue(PlayRules.sourceLine(done.copy(lastUsed = last))!!.let { it.startsWith("Last used · ") && it.endsWith("from Both add-ons") })
        assertEquals("No sources found", PlayRules.sourceLine(Fixtures.list(target, emptyList())))
        val notReady = Fixtures.source(3, readiness = Readiness.NOT_READY, group = SourceGroup.NOT_ON_RD)
        assertEquals("None ready on your debrid service · 1 to fetch in Sources", PlayRules.sourceLine(Fixtures.list(target, listOf(notReady))))
        val noAddons = Fixtures.list(target, emptyList(), states = Addon.entries.associateWith { AddonState.NotSet })
        assertEquals(PlayRules.NO_ADDONS_LINE, PlayRules.sourceLine(noAddons))
        // Nobody answered yet: the line stays empty.
        assertNull(PlayRules.sourceLine(Fixtures.list(target, emptyList(), states = mapOf(Addon.TORRENTIO to AddonState.Asking(0, 10_000)))))
    }

    @Test
    fun theSourcesButtonCountsOnceAnAddonAnswered() {
        assertEquals("Sources", PlayRules.sourcesLabel(null))
        assertEquals("Sources", PlayRules.sourcesLabel(Fixtures.list(target, emptyList(), states = mapOf(Addon.TORRENTIO to AddonState.Asking(0, 1)))))
        assertEquals("Sources · 2", PlayRules.sourcesLabel(Fixtures.list(target, listOf(Fixtures.source(1), Fixtures.source(2)))))
    }

    @Test
    fun aSourcesTwoLinesCarryNoLinkAndOneWarning() {
        val s = Fixtures.source(1).let {
            it.copy(facts = it.facts.copy(hdr = setOf(Hdr.DOLBY_VISION, Hdr.HDR10), dvProfile = 7, pack = true), copies = 3,
                warning = "Dolby Vision 7: may not play here")
        }
        assertEquals("4K · DV7 · HDR · WEB-DL · 29.3 GB · ≈25 Mb/s · Pack · ×3", PlayRules.badges(s))
        // The Sources row: quality · HDR kind · made from · size, then the quieter line ending with the add-on.
        assertEquals("4K · DV · WEB · 29.3 GB", PlayRules.mainLine(s))
        assertEquals("⚠ Dolby Vision 7: may not play here · HEVC 10-bit · DD+ 5.1 · EN · Pack · Torrentio", PlayRules.detailLine(s))
        assertFalse(PlayRules.badges(s).contains("invalid"))
        assertFalse((PlayRules.mainLine(s) + PlayRules.detailLine(s)).contains("invalid"))
        // Seeders only for files not cached yet, before the add-on.
        val nr = Fixtures.source(2, readiness = Readiness.NOT_READY).let { it.copy(facts = it.facts.copy(seeders = 12)) }
        assertTrue(PlayRules.detailLine(nr).endsWith("12 seeders · Torrentio"))
        assertEquals("from Torrentio", PlayRules.factsFrom(Fixtures.source(1)) { "Nov 12" })
    }

    @Test
    fun aRowsMainLineSaysQualityHdrKindMadeFromAndSize() {
        fun of(hdr: Set<Hdr>, origin: String?, text: String = "Film.2160p.mkv") = Fixtures.source(1).let {
            it.copy(facts = it.facts.copy(hdr = hdr, origin = origin, sizeBytes = 58_300_000_000L), offers = listOf(it.offers[0].copy(text = text)))
        }
        assertEquals("4K · DV · HDR10+ · Remux · 58.3 GB", PlayRules.mainLine(of(setOf(Hdr.DOLBY_VISION, Hdr.HDR10_PLUS, Hdr.HDR10), "REMUX")))
        assertEquals("4K · HDR10+ · BluRay · 58.3 GB", PlayRules.mainLine(of(setOf(Hdr.HDR10_PLUS, Hdr.HDR10), "BluRay")))
        assertEquals("4K · HLG · WEB · 58.3 GB", PlayRules.mainLine(of(setOf(Hdr.HLG), "WEBRip")))
        assertEquals("4K · HDTV · 58.3 GB", PlayRules.mainLine(of(emptySet(), "HDTV")))
        // IMAX, IMX or open matte in a 2160p copy's name: "4K IMAX".
        val imax = of(setOf(Hdr.DOLBY_VISION, Hdr.HDR10_PLUS), "REMUX", "The.Film.2026.IMAX.2160p.REMUX")
        assertTrue(PlayRules.imax(imax))
        assertEquals("4K IMAX · DV · HDR10+ · Remux · 58.3 GB", PlayRules.mainLine(imax))
        // Too long for the row: the HDR kind keeps its first part, never a word cut.
        assertEquals("4K IMAX · DV · Remux · 58.3 GB", PlayRules.mainLine(imax, chars = 38))
        assertFalse(PlayRules.imax(Fixtures.source(2, height = 1080).let { it.copy(offers = listOf(it.offers[0].copy(text = "Film.IMAX.1080p"))) }))
    }

    @Test
    fun aRowsSecondLineDropsTheLeastUsefulPartsWhenLong() {
        val s = Fixtures.source(1, also = Addon.MEDIAFUSION).let {
            it.copy(facts = it.facts.copy(audio = "TrueHD Atmos", channels = "7.1", languages = listOf("en", "fr", "de", "es", "it")),
                warning = "Needs 69 Mb/s · this connection brought 72")
        }
        val full = "⚠ Needs 69 Mb/s · this connection brought 72 · HEVC 10-bit · TrueHD Atmos 7.1 · EN + FR + DE + 2 more · Both add-ons"
        assertEquals(full, PlayRules.detailLine(s))
        // The languages shorten first, then the codec and the sound go, the languages last; the warning and the add-on stay.
        assertEquals("⚠ Needs 69 Mb/s · this connection brought 72 · HEVC 10-bit · TrueHD Atmos 7.1 · EN + 4 more · Both add-ons",
            PlayRules.detailLine(s, chars = 110))
        assertEquals("⚠ Needs 69 Mb/s · this connection brought 72 · TrueHD Atmos 7.1 · EN + 4 more · Both add-ons", PlayRules.detailLine(s, chars = 92))
        assertEquals("⚠ Needs 69 Mb/s · this connection brought 72 · EN + 4 more · Both add-ons", PlayRules.detailLine(s, chars = 80))
        assertEquals("⚠ Needs 69 Mb/s · this connection brought 72 · Both add-ons", PlayRules.detailLine(s, chars = 10))
        // At the Sources panel's width, a copy with a warning and a pack still says its language: the codec goes first.
        val dv = Fixtures.source(2).let {
            it.copy(facts = it.facts.copy(pack = true), warning = "Dolby Vision 7: may not play here")
        }
        assertEquals("⚠ Dolby Vision 7: may not play here · DD+ 5.1 · EN · Pack · Torrentio", PlayRules.detailLine(dv, chars = 80))
    }

    @Test
    fun aRowSaysItsAudioLanguagesMeasuredFirst() {
        fun of(vararg langs: String, measured: String? = null) = Fixtures.source(1).let {
            it.copy(facts = it.facts.copy(languages = langs.toList()),
                measured = measured?.let { m -> Measured(MediaSpec(audioLangs = m), null, 1L) })
        }
        assertEquals("EN", PlayRules.audioLanguages(of("en")))
        assertEquals("EN + FR", PlayRules.audioLanguages(of("en", "fr")))
        assertEquals("EN + FR + DE", PlayRules.audioLanguages(of("en", "fr", "de")))
        assertEquals("EN + FR + DE + 2 more", PlayRules.audioLanguages(of("en", "fr", "de", "es", "it")))
        assertEquals("EN + 4 more", PlayRules.audioLanguages(of("en", "fr", "de", "es", "it"), short = true))
        // Two stay as they are when short: nothing shorter says as much.
        assertEquals("EN + FR", PlayRules.audioLanguages(of("en", "fr"), short = true))
        // "Multi Audio" alone, with one language named, and with several (which say it already).
        assertEquals("Multi", PlayRules.audioLanguages(of("multi")))
        assertEquals("EN + more", PlayRules.audioLanguages(of("multi", "en")))
        assertEquals("EN + FR", PlayRules.audioLanguages(of("multi", "en", "fr")))
        assertNull(PlayRules.audioLanguages(of()))
        // Once played here, the file's own tracks (three-letter codes, "und" left out) say it, not the add-on's guess.
        assertEquals("FR + EN", PlayRules.audioLanguages(of("multi", measured = "fre,eng,und,fra")))
        assertEquals("JA", PlayRules.audioLanguages(of(measured = "jpn")))
        // Tracks that name no language: the add-on's word again.
        assertEquals("EN", PlayRules.audioLanguages(of("en", measured = "und")))
        // The row's second line carries it in place of the add-on's.
        assertEquals("HEVC 10-bit · DD+ 5.1 · FR · Torrentio", PlayRules.detailLine(of("en", measured = "fre")))
        assertEquals(listOf("fr"), PlayRules.languageCodes(of("en", measured = "fre")))
    }

    @Test
    fun sizesClocksAndSwitchPositions() {
        assertEquals("29.3 GB", PlayRules.size(29_300_000_000L))
        assertEquals("870 MB", PlayRules.size(870_000_000L))
        assertEquals("1:02:03", PlayRules.clock(3_723_000L))
        assertEquals("42:10", PlayRules.clock(2_530_000L))
        assertEquals(55_000L, PlayRules.switchStart(60_000L))
        assertEquals(0L, PlayRules.switchStart(3_000L))
        assertEquals("4K", PlayRules.resolution(2160))
        assertEquals("720p", PlayRules.resolution(720))
        assertNull(PlayRules.resolution(null))
    }

    @Test
    fun theNextEpisodeIsTheNextAiredRegularOneAcrossSeasons() {
        fun ep(s: Int, e: Int, aired: Long? = 1L) = Episode(Episode.idOf("tt1", s, e), s, e, "E$e", released = aired)
        val eps = listOf(ep(1, 1), ep(1, 2), ep(2, 1), ep(2, 2, aired = 10_000L), ep(0, 1))
        assertEquals(EpisodeRef(1, 2, "E2"), PlayRules.nextEpisode(eps, EpisodeRef(1, 1), 5_000L)?.ref)
        assertEquals(EpisodeRef(2, 1, "E1"), PlayRules.nextEpisode(eps, EpisodeRef(1, 2), 5_000L)?.ref)
        // Not aired yet, the end, or a special: none.
        assertNull(PlayRules.nextEpisode(eps, EpisodeRef(2, 1), 5_000L))
        assertNull(PlayRules.nextEpisode(eps, EpisodeRef(2, 2), 50_000L))
        assertNull(PlayRules.nextEpisode(eps, EpisodeRef(0, 1), 5_000L))
    }
}
