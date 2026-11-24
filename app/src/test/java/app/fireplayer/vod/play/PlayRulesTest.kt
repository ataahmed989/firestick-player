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
import app.fireplayer.vod.SoundFormat
import app.fireplayer.vod.SourceGroup
import app.fireplayer.vod.SourceTarget
import app.fireplayer.vod.StartAt
import app.fireplayer.vod.TvFit
import app.fireplayer.vod.TvPlan
import app.fireplayer.vod.sources.SourceRank
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
        assertTrue(PlayRules.sourceLine(done.copy(lastUsed = last))!!.let { it.startsWith("Last used · ") && it.endsWith("from Torrentio + MediaFusion") })
        // The Best used last time: still said as the Best.
        assertEquals("Best for this TV · 4K · HDR10 · WEB · 29.3 GB · from Torrentio", PlayRules.sourceLine(done.copy(lastUsed = best)))
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

    /** The second Stick's Samsung: HDR10 and HDR10+ alike, then HLG; Dolby Digital and Dolby Digital Plus passed through. */
    private val q60r = TvPlan(true, listOf(setOf(Hdr.HDR10, Hdr.HDR10_PLUS), setOf(Hdr.HLG)), 2160, setOf(SoundFormat.AC3, SoundFormat.EAC3),
        dv7AsHdr10 = true)

    @Test
    fun aSourcesTwoLinesCarryNoLinkAndOneWarning() {
        val s = Fixtures.source(1).let {
            it.copy(facts = it.facts.copy(hdr = setOf(Hdr.DOLBY_VISION, Hdr.HDR10), dvProfile = 7, pack = true), copies = 3,
                warning = "Dolby Vision 7: may not play here")
        }
        assertEquals("4K · DV7 · HDR · WEB-DL · 29.3 GB · ≈25 Mb/s · Pack · ×3", PlayRules.badges(s))
        // The Sources row: quality · HDR · made from · size, then the quieter line: warning, language, sound, add-ons.
        assertEquals("4K · DV · WEB · 29.3 GB", PlayRules.mainLine(s))
        assertEquals("⚠ Dolby Vision 7: may not play here · English · 5.1 · Torrentio", PlayRules.noteLine(s, null, listOf("en")))
        assertFalse(PlayRules.badges(s).contains("invalid"))
        assertFalse((PlayRules.mainLine(s) + PlayRules.noteLine(s, null, emptyList())).contains("invalid"))
        // Seeders only for files not cached yet, before the add-on.
        val nr = Fixtures.source(2, readiness = Readiness.NOT_READY).let { it.copy(facts = it.facts.copy(seeders = 12)) }
        assertTrue(PlayRules.noteLine(nr, null, emptyList()).endsWith("12 seeders · Torrentio"))
        assertFalse(PlayRules.noteLine(Fixtures.source(2).let { it.copy(facts = it.facts.copy(seeders = 12)) }, null, emptyList()).contains("seeders"))
        assertEquals("from Torrentio", PlayRules.factsFrom(Fixtures.source(1)) { "Nov 12" })
    }

    @Test
    fun aRowsMainLineSaysQualityWhatThisTvShowsMadeFromAndSize() {
        fun of(hdr: Set<Hdr>, origin: String?, text: String = "Film.2160p.mkv", dv: Int? = null) = Fixtures.source(1).let {
            it.copy(facts = it.facts.copy(hdr = hdr, origin = origin, sizeBytes = 58_300_000_000L, dvProfile = dv),
                offers = listOf(it.offers[0].copy(text = text)))
        }
        // The add-ons' words, for a list not ranked for a TV.
        assertEquals("4K · DV · HDR10+ · Remux · 58.3 GB", PlayRules.mainLine(of(setOf(Hdr.DOLBY_VISION, Hdr.HDR10_PLUS, Hdr.HDR10), "REMUX")))
        assertEquals("4K · HDR10+ · Encode · 58.3 GB", PlayRules.mainLine(of(setOf(Hdr.HDR10_PLUS, Hdr.HDR10), "BluRay")))
        assertEquals("4K · HLG · WEB · 58.3 GB", PlayRules.mainLine(of(setOf(Hdr.HLG), "WEBRip")))
        assertEquals("4K · HDTV · 58.3 GB", PlayRules.mainLine(of(emptySet(), "HDTV")))
        // What this TV shows: a Dolby Vision file with HDR10+ inside is HDR10+ on a TV without Dolby Vision; profile 7
        // too where this Stick plays its base layer; profile 5 stays DV (its row warns); on a Dolby Vision TV, DV.
        val hybrid = of(setOf(Hdr.DOLBY_VISION, Hdr.HDR10_PLUS, Hdr.HDR10), "REMUX")
        assertEquals("4K · HDR10+ · Remux · 58.3 GB", PlayRules.mainLine(hybrid, plan = q60r))
        assertEquals("4K · HDR10 · Remux · 58.3 GB", PlayRules.mainLine(of(setOf(Hdr.DOLBY_VISION), "REMUX", dv = 7), plan = q60r))
        assertEquals("4K · HDR10 · WEB · 58.3 GB", PlayRules.mainLine(of(setOf(Hdr.DOLBY_VISION), "WEB-DL", dv = 8), plan = q60r))
        assertEquals("4K · DV · WEB · 58.3 GB", PlayRules.mainLine(of(setOf(Hdr.DOLBY_VISION), "WEB-DL", dv = 5), plan = q60r))
        val dvTv = q60r.copy(hdrOrder = listOf(setOf(Hdr.DOLBY_VISION), setOf(Hdr.HDR10, Hdr.HDR10_PLUS)))
        assertEquals("4K · DV · Remux · 58.3 GB", PlayRules.mainLine(hybrid, plan = dvTv))
        // An HDR TV without HDR10+ shows its HDR10 picture.
        assertEquals("4K · HDR10 · Remux · 58.3 GB", PlayRules.mainLine(hybrid, plan = q60r.copy(hdrOrder = listOf(setOf(Hdr.HDR10)))))
        // IMAX or IMX in a 2160p copy's name: "4K IMAX".
        val imax = of(setOf(Hdr.DOLBY_VISION, Hdr.HDR10_PLUS), "REMUX", "The.Film.2026.IMAX.2160p.REMUX")
        assertTrue(PlayRules.imax(imax))
        assertEquals("4K IMAX · DV · HDR10+ · Remux · 58.3 GB", PlayRules.mainLine(imax))
        assertEquals("4K IMAX · HDR10+ · Remux · 58.3 GB", PlayRules.mainLine(imax, plan = q60r))
        // Too long for the row (the add-ons' words): the HDR kind keeps its first part, never a word cut.
        assertEquals("4K IMAX · DV · Remux · 58.3 GB", PlayRules.mainLine(imax, chars = 38))
        assertFalse(PlayRules.imax(Fixtures.source(2, height = 1080).let { it.copy(offers = listOf(it.offers[0].copy(text = "Film.IMAX.1080p"))) }))
        // A 3D copy says so where its resolution is.
        assertEquals("1080p 3D · HDR10 · WEB · 29.3 GB", PlayRules.mainLine(Fixtures.source(3, group = SourceGroup.THREE_D, height = 1080)))
    }

    @Test
    fun aRowsSecondLineSaysOnlyWhatMayChangeTheChoice() {
        val s = Fixtures.source(1, also = Addon.MEDIAFUSION).let {
            it.copy(facts = it.facts.copy(audio = "TrueHD Atmos", channels = "7.1", languages = listOf("ru", "uk", "kk", "en")),
                warning = "Needs 69 Mb/s · this connection brought 72")
        }
        val full = "⚠ Needs 69 Mb/s · this connection brought 72 · English · 7.1 · Torrentio + MediaFusion"
        assertEquals(full, PlayRules.noteLine(s, q60r, listOf("en")))
        // The sound goes first, then the language; the warning and the add-ons stay.
        assertEquals("⚠ Needs 69 Mb/s · this connection brought 72 · English · Torrentio + MediaFusion", PlayRules.noteLine(s, q60r, listOf("en"), chars = 82))
        assertEquals("⚠ Needs 69 Mb/s · this connection brought 72 · Torrentio + MediaFusion", PlayRules.noteLine(s, q60r, listOf("en"), chars = 10))
        // The Best's lead comes after the warning; then the add-ons may go too.
        assertEquals("⚠ Needs 69 Mb/s · this connection brought 72 · cached", PlayRules.noteLine(s, q60r, listOf("en"), chars = 10, lead = listOf("cached")))
        // Not a warning worth a row: "High data rate" (every remux has it; Details keeps it). Set aside only for being
        // more than this TV shows: said.
        assertEquals("English · 7.1 · Torrentio + MediaFusion", PlayRules.noteLine(s.copy(warning = SourceRank.HIGH_RATE), q60r, listOf("en")))
        assertEquals("⚠ More than this TV shows · English · 7.1 · Torrentio + MediaFusion",
            PlayRules.noteLine(s.copy(warning = null, fit = TvFit.LIKELY_NOT), q60r, listOf("en")))
        // Three add-ons: counted.
        val three = s.copy(offers = s.offers + s.offers[0].copy(addon = Addon.TORZ))
        assertEquals("3 add-ons", PlayRules.addonsOf(three))
    }

    @Test
    fun theLanguageThatMattersIsTheFilmsOwnAndTheViewersByName() {
        fun of(vararg langs: String, measured: String? = null, subs: List<String> = emptyList()) = Fixtures.source(1).let {
            it.copy(facts = it.facts.copy(languages = langs.toList(), subs = subs),
                measured = measured?.let { m -> Measured(MediaSpec(audioLangs = m), null, 1L) })
        }
        val english = listOf("en")
        // An English film: English, or "No English" for a copy known to lack it; nothing when nothing is known.
        assertEquals("English", PlayRules.languageWord(of("ru", "uk", "kk", "en"), english))
        assertEquals("No English", PlayRules.languageWord(of("ru", "uk"), english))
        assertNull(PlayRules.languageWord(of(), english))
        assertNull(PlayRules.languageWord(of("multi"), english))
        assertNull(PlayRules.languageWord(of("multi", "ru"), english))
        // Once played here, the file's own tracks (three-letter codes, "und" left out) say it, not the add-on's guess.
        assertEquals("No English", PlayRules.languageWord(of("en", measured = "jpn"), english))
        assertEquals("English", PlayRules.languageWord(of("multi", measured = "fre,eng,und,fra"), english))
        assertEquals("English", PlayRules.languageWord(of("en", measured = "und"), english))
        assertEquals(listOf("fr"), PlayRules.languageCodes(of("en", measured = "fre")))
        // A Japanese film, for an English viewer: its own language first, by name.
        val japanese = listOf("ja", "en")
        assertEquals("Japanese", PlayRules.languageWord(of("ja"), japanese))
        assertEquals("Japanese + English", PlayRules.languageWord(of("ja", "en"), japanese))
        assertEquals("Japanese, English subtitles", PlayRules.languageWord(of("ja", subs = listOf("en")), japanese))
        assertEquals("English", PlayRules.languageWord(of("en"), japanese))
        assertEquals("No English", PlayRules.languageWord(of("fr"), japanese))
        // A list not ranked (no languages): the viewer's taken as English.
        assertEquals("English", PlayRules.languageWord(of("en"), emptyList()))
    }

    @Test
    fun theSoundWordIsWhatIsHeardOnThisTv() {
        fun of(audio: String?, channels: String?, measured: MediaSpec? = null) = Fixtures.source(1).let {
            it.copy(facts = it.facts.copy(audio = audio, channels = channels), measured = measured?.let { m -> Measured(m, null, 1L) })
        }
        // TrueHD is decoded on the Stick where the TV takes Dolby Digital Plus at most: Atmos isn't heard, the 7.1 is.
        assertEquals("7.1", PlayRules.soundWord(of("TrueHD Atmos", "7.1"), q60r))
        assertEquals("Atmos", PlayRules.soundWord(of("TrueHD Atmos", "7.1"), q60r.copy(sound = q60r.sound + SoundFormat.TRUEHD)))
        assertEquals("5.1", PlayRules.soundWord(of("DD+ Atmos", "5.1"), q60r))
        assertEquals("Atmos", PlayRules.soundWord(of("DD+ Atmos", "5.1"), q60r.copy(sound = q60r.sound + SoundFormat.EAC3_JOC)))
        assertEquals("Stereo", PlayRules.soundWord(of("AAC", "2.0"), q60r))
        assertNull(PlayRules.soundWord(of("DTS", null), q60r))
        // What played here first.
        assertEquals("7.1", PlayRules.soundWord(of("DD+ Atmos", "5.1", MediaSpec(audioCodec = "TrueHD", channels = 8)), q60r))
        // No plan (a list not ranked): the file's Atmos.
        assertEquals("Atmos", PlayRules.soundWord(of("TrueHD Atmos", "7.1"), null))
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
