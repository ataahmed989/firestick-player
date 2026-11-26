package app.fireplayer.vod.play

import app.fireplayer.vod.Addon
import app.fireplayer.vod.Card
import app.fireplayer.vod.EpisodeRef
import app.fireplayer.vod.FakeVodRepository
import app.fireplayer.vod.Hdr
import app.fireplayer.vod.LastRelease
import app.fireplayer.vod.PlayOrigin
import app.fireplayer.vod.ReleaseKey
import app.fireplayer.vod.SoundFormat
import app.fireplayer.vod.SourceTarget
import app.fireplayer.vod.StartAt
import app.fireplayer.vod.TvCapabilities
import app.fireplayer.vod.TvPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VodLaunchTest {
    private val repo = FakeVodRepository()
    private val library = FakeLibrary()
    private val release = LastRelease(Addon.MEDIAFUSION, ReleaseKey.of(Fixtures.hash(7), 2)!!, 1L)

    @Test
    fun aContinueFilmPlaysFromWhereItStoppedOnTheSameFile() {
        repo.now = 5L
        repo.recordProgress(Fixtures.film, null, 600_000, 7_200_000)
        library.recordRelease(Fixtures.film, null, release)
        val r = VodLaunch.fromContinueCard(Card(Fixtures.film), repo, library)!!
        assertEquals(SourceTarget(Fixtures.film), r.target)
        assertEquals(StartAt.At(600_000), r.start)
        assertEquals(PlayOrigin.CONTINUE_CARD, r.origin)
        assertEquals(release, r.preferred)
        assertEquals("tt0133093", r.imdbId)
        assertEquals(603, r.tmdbId)
    }

    @Test
    fun aContinueEpisodePlaysTheCardsEpisode() {
        repo.recordProgress(Fixtures.show, EpisodeRef(2, 5), 300_000, 2_700_000)
        val ep = EpisodeRef(2, 5, "Name")
        library.recordRelease(Fixtures.show, ep, release)
        val r = VodLaunch.fromContinueCard(Card(Fixtures.show, episode = ep), repo, library)!!
        assertEquals(ep, r.target.episode)
        assertEquals(StartAt.At(300_000), r.start)
        assertEquals(release, r.preferred)
        assertEquals(2, r.season)
        assertEquals(5, r.episode)
        assertEquals("series", r.target.streamType)
        assertEquals("tt1695360:2:5", r.target.streamId)
        // A card for an episode with no position of its own (watched to the end): from the beginning.
        repo.recordProgress(Fixtures.show, EpisodeRef(2, 6), 2_690_000, 2_700_000)
        library.recordRelease(Fixtures.show, EpisodeRef(2, 6), release)
        val done = VodLaunch.fromContinueCard(Card(Fixtures.show, episode = EpisodeRef(2, 6)), repo, library)!!
        assertEquals(StartAt.Beginning, done.start)
        // Not a resume: the ranking's pick, not the copy played last.
        assertNull(done.preferred)
    }

    @Test
    fun aCopyOfNoReleaseKnownLeftIsWhatContinueAndResumePlayAgain() {
        // MediaFusion's copy whose link showed no info hash: kept by its key ("x|mediafusion|<print>|tt…:S:E").
        val ep = EpisodeRef(1, 9)
        val copy = LastRelease.ofCopy(Addon.MEDIAFUSION, "x|mediafusion|0123456789abcdef|tt1695360:1:9", 1L)
        repo.recordProgress(Fixtures.show, ep, 600_000, 1_680_000)
        library.recordRelease(Fixtures.show, ep, copy)
        val card = VodLaunch.fromContinueCard(Card(Fixtures.show, episode = ep), repo, library)!!
        assertEquals(copy, card.preferred)
        assertNull(card.preferred?.release)
        val resume = VodLaunch.fromTitlePage(SourceTarget(Fixtures.show, ep), StartAt.At(600_000), null, library)
        assertEquals(copy, resume.preferred)
        // From the beginning: the ranking's pick, as for a release.
        assertNull(VodLaunch.fromTitlePage(SourceTarget(Fixtures.show, ep), StartAt.Beginning, null, library).preferred)
    }

    @Test
    fun aSeriesCardWithoutAnEpisodeHasNoPlay() {
        assertNull(VodLaunch.fromContinueCard(Card(Fixtures.show), repo, library))
    }

    @Test
    fun theTitlePagePrefersTheLastReleaseOnlyWhenResuming() {
        library.recordRelease(Fixtures.film, null, release)
        val t = SourceTarget(Fixtures.film)
        val resume = VodLaunch.fromTitlePage(t, StartAt.At(1_000), null, library)
        assertEquals(release, resume.preferred)
        assertEquals(PlayOrigin.TITLE_PAGE, resume.origin)
        val fresh = VodLaunch.fromTitlePage(t, StartAt.Beginning, null, library)
        assertNull(fresh.preferred)
        val chosen = VodLaunch.fromTitlePage(t, StartAt.Beginning, null, library, chosen = Fixtures.source(1))
        assertEquals(PlayOrigin.SOURCES_PANEL, chosen.origin)
        assertTrue(VodLaunch.withFilmMode(chosen).filmMode)
    }

    @Test
    fun thisTvWordsAndTheCustomPlan() {
        val tv = TvCapabilities(setOf(Hdr.HDR10, Hdr.HDR10_PLUS, Hdr.HLG), 3840, 2160, setOf(SoundFormat.AC3, SoundFormat.EAC3), 0L)
        assertEquals("HDR10+, HDR10 and HLG", TvPlanRules.hdrReport(tv))
        assertEquals("None reported (SDR, or HDR off on this input)", TvPlanRules.hdrReport(tv.copy(hdr = emptySet())))
        assertEquals("3840×2160 (4K)", TvPlanRules.pictureReport(tv))
        assertEquals("Dolby Digital and Dolby Digital Plus", TvPlanRules.soundReport(tv.passthrough))
        assertEquals("PCM and AAC only", TvPlanRules.soundReport(emptySet()))
        assertEquals("Not checked yet", TvPlanRules.hdrReport(null))
        val samsung = TvPlan(true, listOf(setOf(Hdr.HDR10, Hdr.HDR10_PLUS), setOf(Hdr.HLG)), 2160, emptySet())
        assertEquals(0, TvPlanRules.hdrIndex(samsung))
        assertEquals("HDR10+ = HDR10, then HLG", TvPlanRules.hdrWords(samsung.hdrOrder))
        assertEquals("SDR first", TvPlanRules.hdrWords(emptyList()))
        val withDd = TvPlanRules.toggleSound(samsung, SoundFormat.EAC3)
        assertEquals(setOf(SoundFormat.EAC3), withDd.sound)
        assertEquals(false, withDd.automatic)
        assertEquals(emptySet<SoundFormat>(), TvPlanRules.toggleSound(withDd, SoundFormat.EAC3).sound)
        assertEquals("No limit", TvPlanRules.mbpsLabel(null))
        assertEquals("40 Mb/s", TvPlanRules.mbpsLabel(40))
    }
}
