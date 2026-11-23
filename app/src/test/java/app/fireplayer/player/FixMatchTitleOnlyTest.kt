package app.fireplayer.player

import app.fireplayer.history.WatchHistory
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
import app.fireplayer.vod.play.PlayerMeta
import app.fireplayer.vod.play.VodFix
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fix match is for the title alone (which show or film), never an episode; and the one-time reset that clears the
 * episode fixes earlier releases kept ([FixReset]), keeping which show each title is.
 */
class FixMatchTitleOnlyTest {

    // ---- The title-only save ----

    @Test
    fun savingAShowKeepsOnlyWhichShowItIs() {
        // First fix: the show alone.
        val first = MatchFixes.titled(null, 1396, shownTmdb = 0)
        assertEquals(1396, first.tmdbId)
        assertTrue(first.confirmed.isEmpty())
        assertEquals(setOf("tmdb", "ok"), JSONObject(first.toJson()).keys().asSequence().toSet())
        // "Looks right" on episodes paired with that show stays while it's still the show.
        val looked = MatchFixes.Show(1396, setOf("101", "102"))
        assertEquals(setOf("101", "102"), MatchFixes.titled(looked, 1396, shownTmdb = 1396).confirmed)
        // Another show: those were said of the old pairing, and go.
        val other = MatchFixes.titled(looked, 4242, shownTmdb = 1396)
        assertEquals(4242, other.tmdbId)
        assertTrue(other.confirmed.isEmpty())
        // Confirmed on the show found by itself (none fixed yet), then that same show chosen: kept.
        assertEquals(setOf("7"), MatchFixes.titled(MatchFixes.Show(0, setOf("7")), 55, shownTmdb = 55).confirmed)
        assertTrue(MatchFixes.titled(MatchFixes.Show(0, setOf("7")), 56, shownTmdb = 55).confirmed.isEmpty())
    }

    @Test
    fun anOldEpisodeFixIsNeverReadAsOne() {
        // As 1.0.348 kept it: the show, an episode placed by hand and the rest of its season moved.
        val old = """{"tmdb":42,"picks":{"1-5":"1,4"},"shifts":["1,2,1,-1"],"ok":["2-2"]}"""
        val show = MatchFixes.Show.fromJson(old)!!
        assertEquals(42, show.tmdbId)
        assertEquals(setOf("2-2"), show.confirmed)
        assertFalse(show.toJson().contains("picks"))
        assertFalse(show.toJson().contains("shifts"))
    }

    // ---- Films & Shows: the title fixed, its episodes as they are ----

    private val series = Title("tt1695360", Kind.SERIES, "Show", year = "2012", tmdbId = 1234)
    private val day = 24 * 60 * 60_000L

    private fun cinemeta(s: Int, e: Int, name: String) =
        Episode(Episode.idOf(series.id, s, e), s, e, name, released = 1_000L, day = Day(2013, 11, e), overview = "About $name",
            still = "https://img.invalid/$s-$e", runtimeMin = 23)

    private val details = TitleDetails(
        title = series,
        seasons = listOf(Season(2, listOf(cinemeta(2, 9, "Nine"), cinemeta(2, 10, "Ten"), cinemeta(2, 11, "Eleven")))),
    )

    private fun request(season: Int, episode: Int) =
        PlayRequest(SourceTarget(series, EpisodeRef(season, episode, "x")), StartAt.Beginning, PlayOrigin.TITLE_PAGE)

    @Test
    fun aFixedShowFromFilmsAndShowsKeepsEveryEpisodeAtItsOwnNumbers() {
        val g = PlayerMeta.guide(details, now = day)!!
        assertEquals(listOf("S2 E9", "S2 E10", "S2 E11"), g.episodes.map { it.label })
        assertEquals(listOf("Nine", "Ten", "Eleven"), g.episodes.map { it.name })
        val ten = g.episode("2:10")!!
        assertEquals(EpisodeRef(2, 10, "Ten"), PlayerMeta.refOf(ten))
        // Fixed as another show: its name, details, Skip intro and subtitles go by that show, at the episode handed over.
        val fix = VodFix(777, true, "Other", 2020)
        val shown = PlayerMeta.fixed(request(2, 10), fix)
        assertEquals(10, shown.episode)
        assertEquals(2, shown.season)
        assertEquals("tmdb:series:777/2/10", PlayerMeta.skipKey(shown))
        assertEquals("Other · S2 E10", PlayerMeta.thisTitleName(request(2, 10), fix))
        assertEquals(
            "Fixed by hand as Other (2020) on TMDB: details, Skip intro and subtitles go by it. Sources still come by the " +
                "show's IMDb id.",
            PlayerMeta.matchNote(request(2, 10), fix, "S2 E10 · Ten"),
        )
    }

    @Test
    fun theEpisodeWindowIsHeadedByItsNumbers() {
        val ep = ShowGuide.Ep("2-10", "mkv", 2, 10, "", 2, 10, "", "", null, 0, "", ShowGuide.Match.SURE, null)
        assertEquals("S2 · E10", ep.numbers)
    }

    // ---- The one-time reset ----

    /** The store as a TV that used every kind of Fix match since 1.0.346 has it. */
    private val stored: Map<String, Any?> = mapOf(
        // 1.0.346: the show chosen, an episode placed with its season and episode pickers, "Move the rest of the season too".
        "show|http://a.invalid:80|u|11" to """{"tmdb":1396,"picks":{"501":"2,3"},"shifts":["2,4,2,-1"],"ok":[]}""",
        // 1.0.348: "Also fix 9 other episodes from this source" on a show found by itself, an episode said to look right.
        "show|http://a.invalid:80|u|12" to """{"tmdb":0,"picks":{"601":"1,4"},"shifts":["1,2,1,-1"],"ok":["602"]}""",
        // Only placed, on the show found by itself: nothing left.
        "show|http://a.invalid:80|u|13" to """{"tmdb":0,"picks":{"701":"3,1"},"shifts":[],"ok":[]}""",
        // "Looks right" alone, as every release wrote it (empty lists beside it).
        "show|http://a.invalid:80|u|14" to """{"tmdb":0,"picks":{},"shifts":[],"ok":["801"]}""",
        // Already the show alone.
        "show|http://a.invalid:80|u|15" to """{"tmdb":99,"ok":[]}""",
        // 1.0.348, Films & Shows: an episode placed, kept by the title's id.
        "show|vod|tt1695360" to """{"tmdb":1234,"picks":{"2:10":"2,9"},"shifts":["2,11,2,-1"],"ok":[]}""",
        // Unreadable.
        "show|http://a.invalid:80|u|16" to "{not json",
        // Title matches of films and Films & Shows titles: kept as they are.
        "film|a.invalid:80|900" to "The Odyssey (1997)",
        "filmtmdb|a.invalid:80|900" to "1234\t1997\tThe Odyssey",
        "vod|tt1695360" to "777\t1\t2020\tOther",
    )

    @Test
    fun theResetClearsEveryEpisodeFixAndKeepsTheTitles() {
        val plan = FixReset.plan(stored)
        // The show each playlist title is, and "Looks right", kept; episodes placed and seasons moved gone.
        fun kept(key: String) = MatchFixes.Show.fromJson(plan.write.getValue(key))!!.let { it.tmdbId to it.confirmed }
        assertEquals(1396 to emptySet<String>(), kept("show|http://a.invalid:80|u|11"))
        assertEquals(0 to setOf("602"), kept("show|http://a.invalid:80|u|12"))
        assertEquals(0 to setOf("801"), kept("show|http://a.invalid:80|u|14"))
        assertEquals(
            setOf("show|http://a.invalid:80|u|13", "show|vod|tt1695360", "show|http://a.invalid:80|u|16"),
            plan.remove,
        )
        // Films, Films & Shows titles and a show already fixed as itself alone: untouched.
        listOf("film|a.invalid:80|900", "filmtmdb|a.invalid:80|900", "vod|tt1695360", "show|http://a.invalid:80|u|15").forEach {
            assertFalse(it, it in plan.write || it in plan.remove)
        }
        plan.write.values.forEach { assertFalse(it.contains("picks") || it.contains("shifts")) }
        // The playlist shows whose guides were made with episode fixes, to be made again.
        assertEquals(
            setOf("http://a.invalid:80|u|11", "http://a.invalid:80|u|12", "http://a.invalid:80|u|13"),
            plan.moved.keys,
        )
        assertEquals(2 to 4, plan.moved.getValue("http://a.invalid:80|u|11").place("502", 2, 5))
        assertNull(plan.moved.getValue("http://a.invalid:80|u|11").place("502", 2, 3))
        assertEquals(2 to 3, plan.moved.getValue("http://a.invalid:80|u|11").place("501", 2, 9))
        assertNull(plan.moved.getValue("http://a.invalid:80|u|11").place("503", 2, 1))
    }

    @Test
    fun theResetIsDoneOnceAndHasNothingLeftToDoAfter() {
        val plan = FixReset.plan(stored)
        val after = HashMap(stored).apply {
            plan.remove.forEach { remove(it) }
            putAll(plan.write)
            put(FixReset.DONE, true)
        }
        // Even were its mark lost, a second run changes nothing.
        val again = FixReset.plan(after)
        assertTrue(again.write.isEmpty())
        assertTrue(again.remove.isEmpty())
        assertTrue(again.moved.isEmpty())
        // What's left reads as the titles alone.
        assertEquals(1396, MatchFixes.Show.fromJson(after["show|http://a.invalid:80|u|11"] as String)!!.tmdbId)
        assertEquals(setOf("602"), MatchFixes.Show.fromJson(after["show|http://a.invalid:80|u|12"] as String)!!.confirmed)
        assertEquals("777\t1\t2020\tOther", after["vod|tt1695360"])
        assertEquals(true, after[FixReset.DONE])
    }

    private fun ep(id: String, ps: Int, pn: Int, at: Pair<Int, Int>, title: String = "") = ShowGuide.Ep(
        id, "mkv", ps, pn, title, at.first, at.second, "Shown", "", null, 0, "", ShowGuide.Match.SURE, null,
    )

    @Test
    fun aContinueWatchingRowNotedAtAMovedEpisodeGoesBackToTheSourcesNumbers() {
        val moved = FixReset.plan(stored).moved.getValue("http://a.invalid:80|u|11")
        // The source's S2 E5 had been put at S2 E4 by the moved season, and its row noted so.
        val five = ep("502", 2, 5, 2 to 4, title = "Bit by a Dead Bee")
        assertEquals(WatchHistory.Numbers(2, 5, "Bit by a Dead Bee"), FixReset.ownNumbers(five, moved, 2, 4))
        // Noted at other numbers (before the fix): left as it is.
        assertNull(FixReset.ownNumbers(five, moved, 2, 5))
        // Not moved by a fix (paired so by TMDB): left as it is.
        assertNull(FixReset.ownNumbers(ep("503", 2, 1, 1 to 7), moved, 1, 7))
        // At its own numbers anyway: nothing to do.
        assertNull(FixReset.ownNumbers(ep("504", 2, 3, 2 to 3), moved, 2, 3))

        // The rows themselves: found by the source's ids, only their numbers change.
        val resume = WatchHistory.Resume("http://a.invalid:80/series/u/p/502.mkv")
        val row = WatchHistory.Record("r1", WatchHistory.Kind.EPISODE, setOf("s|a|11"), "e|a|502", "Show", 2008, 2, 4, "Shown",
            1396, 600_000L, 2_700_000L, 5L, 900_000L, resume)
        val film = row.copy(id = "r2", kind = WatchHistory.Kind.MOVIE, videoKey = "m|a|9", season = 0, episode = 0)
        val h = WatchHistory(listOf(row, film))
        val own = { r: WatchHistory.Record -> if (r.videoKey == "e|a|502") WatchHistory.Numbers(2, 5, null) else null }
        assertTrue(h.renumber(own))
        val back = h.record("r1")!!
        assertEquals(row.copy(season = 2, episode = 5, episodeTitle = null), back)
        assertEquals(film, h.record("r2"))
        // Done: a second time changes nothing.
        assertFalse(h.renumber(own))
    }
}
