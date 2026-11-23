package app.fireplayer.vod.play

import app.fireplayer.player.MatchFixes
import app.fireplayer.player.ShowGuide
import app.fireplayer.subs.Tmdb
import app.fireplayer.vod.Day
import app.fireplayer.vod.Episode
import app.fireplayer.vod.EpisodeRef
import app.fireplayer.vod.Kind
import app.fireplayer.vod.PlayOrigin
import app.fireplayer.vod.PlayRequest
import app.fireplayer.vod.Season
import app.fireplayer.vod.SourceTarget
import app.fireplayer.vod.StartAt
import app.fireplayer.vod.Title
import app.fireplayer.vod.TitleDetails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fix match setting a source's episode to the one it really is, and the rest of its season with it ("Also fix 9 other
 * episodes from this source"), for a playlist's show and a show from Films & Shows; and the Episode details heading.
 */
class EpisodeFixTest {

    // ---- Aligning the rest of the source's season (MatchFixes) ----

    /** A source's episode [season] / [number], paired (as shown) with [at]. */
    private fun ep(season: Int, number: Int, at: Pair<Int, Int> = season to number) = ShowGuide.Ep(
        "$season-$number", "mkv", season, number, "", at.first, at.second, "", "", null, 0, "", ShowGuide.Match.SURE, null,
    )

    private fun season(season: Int, count: Int) = (1..count).map { ep(season, it) }

    @Test
    fun oneEpisodeOffMovesTheRestOfItsSeasonByAsMuchWhereTheyStillLandOnAnEpisode() {
        val eps = season(1, 10)
        val five = eps[4]
        // The source's E5 is TMDB's E4: the others move one back, but E1 has nowhere to go and stays.
        val shift = MatchFixes.alignShift(eps, five, 1, 4)
        assertEquals(1, shift.season)
        assertEquals(2, shift.from)
        assertEquals(1, shift.toSeason)
        assertEquals(-1, shift.delta)
        assertEquals(8, MatchFixes.alignCount(eps, five, shift, emptySet()))
        // One already put right by hand stays as it is, and isn't counted.
        assertEquals(7, MatchFixes.alignCount(eps, five, shift, setOf("1-7")))
        // Where each one goes once saved.
        val fix = MatchFixes.placed(null, 42, five.id, 1, 4, shift)
        assertEquals(1 to 4, fix.place(five.id, 1, 5))
        assertEquals(1 to 9, fix.place("1-10", 1, 10))
        assertEquals(1 to 1, fix.place("1-2", 1, 2))
        assertNull(fix.place("1-1", 1, 1))
        assertEquals(42, fix.tmdbId)
    }

    @Test
    fun aSeasonPackUnderTheWrongSeasonMovesWholeAndNothingElse() {
        val eps = season(3, 8) + season(4, 3)
        val four = eps[3]
        val shift = MatchFixes.alignShift(eps, four, 2, 4)
        assertEquals(1, shift.from)
        assertEquals(0, shift.delta)
        // The other seven of the pack, not the next season's.
        assertEquals(7, MatchFixes.alignCount(eps, four, shift, emptySet()))
        val fix = MatchFixes.placed(null, 7, four.id, 2, 4, shift)
        assertEquals(2 to 1, fix.place("3-1", 3, 1))
        assertNull(fix.place("4-1", 4, 1))
    }

    @Test
    fun aShowNumberedStraightThroughKeepsItsEarlierEpisodes() {
        val eps = season(1, 40)
        val shift = MatchFixes.alignShift(eps, eps[26], 2, 1)
        assertEquals(27, shift.from)
        assertEquals(-26, shift.delta)
        assertEquals(13, MatchFixes.alignCount(eps, eps[26], shift, emptySet()))
    }

    @Test
    fun nothingToAlignWhenTheOthersAreAlreadyRight() {
        val eps = season(1, 10)
        val shift = MatchFixes.alignShift(eps, eps[4], 1, 5)
        assertEquals(0, MatchFixes.alignCount(eps, eps[4], shift, emptySet()))
        // Others paired elsewhere by then (a show numbered straight through) are counted when the shift moves them.
        val paired = eps.map { ep(it.providerSeason, it.providerNumber, 2 to it.providerNumber) }
        assertEquals(9, MatchFixes.alignCount(paired, paired[4], shift, emptySet()))
    }

    @Test
    fun aNewFixKeepsTheShowsOtherFixesUnlessTheShowChanged() {
        val old = MatchFixes.Show(42, mapOf("1-3" to (1 to 2)), listOf(MatchFixes.Show.Shift(1, 6, 1, -1), MatchFixes.Show.Shift(2, 1, 2, 1)),
            setOf("1-5", "2-2"))
        val align = MatchFixes.Show.Shift(1, 4, 1, -2)
        val kept = MatchFixes.placed(old, 42, "1-5", 1, 3, align)
        assertEquals(1 to 2, kept.picks["1-3"])
        assertEquals(1 to 3, kept.picks["1-5"])
        // The season's run from E6 gives way to the new one from E4; the other season's stays.
        assertEquals(listOf("2,1,2,1", "1,4,1,-2"), kept.shifts.map { "${it.season},${it.from},${it.toSeason},${it.delta}" })
        assertEquals(setOf("2-2"), kept.confirmed)
        // Without aligning, only the one episode is placed.
        assertEquals(2, MatchFixes.placed(old, 42, "1-5", 1, 3, null).shifts.size)
        // Another show: the old show's fixes go.
        val other = MatchFixes.placed(old, 99, "1-5", 1, 3, null)
        assertEquals(setOf("1-5"), other.picks.keys)
        assertTrue(other.shifts.isEmpty())
        assertEquals("vod|tt1695360", MatchFixes.vodKey("tt1695360"))
    }

    // ---- Films & Shows: what the player shows for an episode set to another one (PlayerMeta) ----

    private val show = Title("tt1695360", Kind.SERIES, "Show", year = "2012", tmdbId = 1234)
    private val day = 24 * 60 * 60_000L

    private fun cinemeta(s: Int, e: Int, name: String) =
        Episode(Episode.idOf(show.id, s, e), s, e, name, released = 1_000L, day = Day(2013, 11, e), overview = "About $name",
            still = "https://img.invalid/$s-$e", runtimeMin = 23)

    private val details = TitleDetails(
        title = show,
        seasons = listOf(Season(2, listOf(cinemeta(2, 9, "Nine"), cinemeta(2, 10, "Ten"), cinemeta(2, 11, "Eleven")))),
    )

    private fun request(season: Int, episode: Int) =
        PlayRequest(SourceTarget(show, EpisodeRef(season, episode, "x")), StartAt.Beginning, PlayOrigin.TITLE_PAGE)

    @Test
    fun anEpisodeSetToAnotherOfTheShowShowsThatOneButPlaysByItsOwnNumbers() {
        val fix = MatchFixes.placed(null, 1234, "2:10", 2, 9, null)
        val g = PlayerMeta.guide(details, now = day, fix = fix)!!
        val ten = PlayerMeta.episode(g, request(2, 10))!!
        assertEquals("2:10", ten.id)
        assertEquals("S2 E9", ten.label)
        assertEquals("Nine", ten.name)
        assertEquals("About Nine", ten.overview)
        assertEquals("2013-11-09", ten.airDate)
        // It still plays, and is kept by the library, as the source's S2 E10.
        assertEquals(EpisodeRef(2, 10, null), PlayerMeta.refOf(ten))
        // The others are as they were.
        assertEquals(EpisodeRef(2, 11, "Eleven"), PlayerMeta.refOf(g.episode("2:11")!!))
        // Skip intro, the subtitles search and This episode's name ask about the one it really is.
        val shown = PlayerMeta.fixed(request(2, 10), null, fix)
        assertEquals(9, shown.episode)
        assertEquals("tt1695360", shown.imdbId)
        assertEquals("tt1695360/2/9", PlayerMeta.skipKey(shown))
        assertEquals("Show · S2 E9", PlayerMeta.thisTitleName(shown, null))
        // An episode the fix doesn't move is the very same play.
        val eleven = request(2, 11)
        assertTrue(PlayerMeta.fixed(eleven, null, fix) === eleven)
        assertEquals(
            "Set by hand to S2 E9 · Nine on TMDB: details, Skip intro and subtitles go by it. Sources still come by the " +
                "show's IMDb id, as S2 E10.",
            PlayerMeta.matchNote(request(2, 10), null, "S2 E9 · Nine", placed = true),
        )
    }

    @Test
    fun anEpisodeSetToAnotherShowShowsTmdbsEpisodeOnceItsIn() {
        val fix = MatchFixes.placed(null, 777, "2:10", 1, 3, MatchFixes.Show.Shift(2, 9, 1, -7))
        // Before TMDB's episodes are read: its numbers, nothing of the other show's.
        val before = PlayerMeta.guide(details, now = day, fix = fix)!!.episode("2:10")!!
        assertEquals("S1 E3", before.label)
        assertEquals("", before.name)
        assertNull(before.image)
        val real = mapOf(
            (1 to 2) to Tmdb.TvEpisode(1, 1, 2, "Two", "Story two", "https://img.invalid/t2", 24, "2020-01-02", 7.5),
            (1 to 3) to Tmdb.TvEpisode(2, 1, 3, "Three", "Story three", null, 25, "2020-01-09", 8.1),
        )
        val g = PlayerMeta.guide(details, now = day, fix = fix, real = real)!!
        val ten = g.episode("2:10")!!
        assertEquals("S1 E3", ten.label)
        assertEquals("Three", ten.name)
        assertEquals(8.1, ten.rating, 0.0)
        assertEquals(25, ten.runtimeMin)
        // The rest of the season moved with it.
        assertEquals("Two", g.episode("2:9")!!.name)
        assertEquals(EpisodeRef(2, 9, null), PlayerMeta.refOf(g.episode("2:9")!!))
        // Shown as the other show's, by the fix: the stand-in title with the episode it really is.
        val shown = PlayerMeta.fixed(request(2, 10), VodFix(777, true, "Other", 2020), fix)
        assertEquals("tmdb:series:777/1/3", PlayerMeta.skipKey(shown))
        assertEquals("Other · S1 E3", PlayerMeta.thisTitleName(shown, VodFix(777, true, "Other", 2020)))
        // Without a fix nothing changes.
        assertEquals("Ten", PlayerMeta.guide(details, now = day)!!.episode("2:10")!!.name)
    }

    @Test
    fun theEpisodeWindowIsHeadedByItsNumbers() {
        assertEquals("S2 · E10", ep(2, 10).numbers)
        assertEquals("S1 · E3", ep(2, 10, 1 to 3).numbers)
    }
}
