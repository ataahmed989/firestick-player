package app.fireplayer.vod.ui

import app.fireplayer.vod.Callout
import app.fireplayer.vod.Day
import app.fireplayer.vod.Episode
import app.fireplayer.vod.EpisodeRef
import app.fireplayer.vod.Kind
import app.fireplayer.vod.LastPlayed
import app.fireplayer.vod.PlayAction
import app.fireplayer.vod.PlayTarget
import app.fireplayer.vod.Position
import app.fireplayer.vod.Season
import app.fireplayer.vod.Title
import app.fireplayer.vod.TitleDetails
import app.fireplayer.vod.data.Callouts
import app.fireplayer.vod.playTarget
import app.fireplayer.vod.ui.TitleFixtures.NOW
import app.fireplayer.vod.ui.TitleFixtures.THIS_YEAR
import app.fireplayer.vod.ui.TitleButtons.Which
import app.fireplayer.vod.ui.TitleRules.Dir
import app.fireplayer.vod.ui.TitleRules.Part
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleRulesTest {

    private val expected = TitleFixtures.expected()

    /** "cinemeta_meta_film.json + tmdb_….json" → the Cinemeta file's details, with the case's age rating. */
    private fun detailsFor(case: String, ageRating: String?): TitleDetails =
        TitleFixtures.details(case.substringBefore(" + ").substringBefore(" without"), ageRating)

    private fun code(e: Episode) = "S${e.season} E${e.episode}"

    // ---- Words for numbers and dates ----

    @Test
    fun runtimeWords() {
        assertEquals("2h 47m", TitleRules.runtime(167))
        assertEquals("1h", TitleRules.runtime(60))
        assertEquals("47m", TitleRules.runtime(47))
        assertEquals("3h 1m", TitleRules.runtime(181))
        assertNull(TitleRules.runtime(0))
        assertNull(TitleRules.runtime(null))
    }

    @Test
    fun datesSayTheirYearOnlyWhenItIsNotThisOne() {
        assertEquals("Oct 5, 2013", TitleRules.dayText(Day(2013, 10, 5)))
        assertEquals("Dec 3", TitleRules.shortDay(Day(2026, 12, 3), 2026))
        assertEquals("Jan 7, 2027", TitleRules.shortDay(Day(2027, 1, 7), 2026))
    }

    // ---- The fixtures' films and shows (expected_title_page.json) ----

    @Test
    fun filmsReadAsExpected() {
        val films = expected.getJSONObject("films")
        for (case in films.keys()) {
            val e = films.getJSONObject(case)
            val d = detailsFor(case, if (e.isNull("age_rating")) null else e.getString("age_rating"))
            assertEquals(case, e.getString("facts"), TitleRules.facts(d))
            assertEquals(case, e.getString("cast_line"), TitleRules.castLine(d))
            val oscar = if (e.isNull("oscar_callout")) null else e.getString("oscar_callout")
            assertEquals(case, oscar, (Callouts.choose(d, false, { false }, null, NOW) as? Callout.Oscars)?.text)
            val coming = if (e.isNull("coming")) null else e.getString("coming")
            val play = TitleRules.filmPlay(d, null, NOW, THIS_YEAR)
            assertEquals(case, coming, if (play.enabled) null else play.label)
            assertEquals(case, coming == null, play.enabled)
        }
    }

    @Test
    fun showsReadAsExpected() {
        val shows = expected.getJSONObject("shows")
        for (case in shows.keys()) {
            val e = shows.getJSONObject(case)
            val d = detailsFor(case, if (e.isNull("age_rating")) null else e.getString("age_rating"))
            assertEquals(case, e.getString("facts"), TitleRules.facts(d))
            assertEquals(case, e.getString("cast_line"), TitleRules.castLine(d))

            val seasons = TitleRules.seasonOrder(d.seasons)
            val pills = e.getJSONArray("season_pills").let { a -> (0 until a.length()).map { a.getString(it) } }
            assertEquals(case, pills, if (TitleRules.showsSeasonPicker(seasons)) seasons.map { TitleRules.seasonLabel(it.number) } else emptyList<String>())

            val all = seasons.flatMap { it.episodes }
            assertEquals(case, e.getInt("episodes_total"), all.size)
            assertEquals(case, e.getInt("episodes_aired"), all.count { it.aired(NOW) })
            val first = e.getJSONArray("first_three_in_order").let { a -> (0 until a.length()).map { a.getString(it) } }
            val last = e.getJSONArray("last_three_in_order").let { a -> (0 until a.length()).map { a.getString(it) } }
            assertEquals(case, first, all.take(3).map { code(it) })
            assertEquals(case, last, all.takeLast(3).map { code(it) })

            val newOnes = e.getJSONArray("new_in_last_7_days").let { a -> (0 until a.length()).map { a.getString(it) } }
            assertEquals(case, newOnes, all.filter { TitleRules.episodeCard(d.title, it, false, null, NOW, THIS_YEAR).badge == "NEW" }.map { code(it) })

            val next = all.firstOrNull { it.season > 0 && !it.aired(NOW) }
            if (e.isNull("next_unaired_regular")) {
                assertNull(case, next)
            } else {
                val n = e.getJSONObject("next_unaired_regular")
                assertEquals(case, n.getString("episode"), next?.let { code(it) })
                assertEquals(case, n.getString("name"), next?.name)
                assertEquals(case, "Airs " + n.getString("label_date_pacific"), next?.let { TitleRules.airs(it, THIS_YEAR) })
            }

            val samples = e.getJSONObject("cards_sample")
            for (key in samples.keys()) {
                val ep = all.first { code(it) == key }
                val lines = samples.getJSONArray(key)
                val card = TitleRules.episodeCard(d.title, ep, false, null, NOW, THIS_YEAR)
                assertEquals("$case $key", lines.getString(0), TitleRules.episodeLine1(card.episode!!))
                assertEquals("$case $key", lines.getString(1), card.caption)
                assertEquals("$case $key", ep.aired(NOW), card.playable)
            }

            val targets = e.getJSONArray("play_targets")
            for (i in 0 until targets.length()) {
                val pt = targets.getJSONObject(i)
                val last = if (pt.isNull("record")) null else pt.getJSONObject("record").let { r ->
                    LastPlayed(EpisodeRef(r.getInt("season"), r.getInt("episode")), Position(r.getLong("positionMs"), r.getLong("durationMs"), NOW))
                }
                val result = pt.getJSONArray("result")
                val target = playTarget(d.episodes, last, NOW)
                // The research's words are Play's whole sentence (read out); the pill says them, or (a Resume) their
                // start without the time left, which its progress line shows.
                val play = TitleRules.showPlay(d, target, THIS_YEAR)
                assertEquals("$case #$i", result.getString(0), play.says)
                assertEquals("$case #$i", result.getString(0).substringBefore(" · ").takeIf { play.restart } ?: result.getString(0), play.label)
                assertEquals("$case #$i", result.getString(1), target?.episode?.let { "${it.season}:${it.episode}" })
                val upToDate = target?.upcoming?.let { TitleRules.calloutText(Callout.UpToDate(it), THIS_YEAR) }
                assertEquals("$case #$i", if (result.isNull(2)) null else result.getString(2), upToDate)
            }
        }
    }

    // ---- Callouts ----

    private val film = TitleDetails(Title("tt1", Kind.MOVIE, "A film", year = "2024"), oscars = "Won 2 Oscars", top10Rank = null)
    private val show = TitleDetails(Title("tt2", Kind.SERIES, "A show"), oscars = "Nominated for 1 Oscar")
    private val ep1 = Episode("tt2:1:1", 1, 1, "One", released = NOW - 100_000, day = Day(2026, 11, 18))
    private val ep2 = Episode("tt2:1:2", 1, 2, "Two", released = NOW + 86_400_000, day = Day(2026, 12, 3))

    /** The page's chip comes from the repository's rule ([Callouts.choose]); its order as the page relies on it. */
    @Test
    fun theOneCalloutComesInTheBriefsOrder() {
        val none: (Episode) -> Boolean = { false }
        val all: (Episode) -> Boolean = { true }
        assertEquals(Callout.Top10(4), Callouts.choose(film.copy(top10Rank = 4), true, none, null, NOW))
        assertEquals(Callout.Watched, Callouts.choose(film, true, none, null, NOW))
        assertEquals(Callout.Oscars("Won 2 Oscars"), Callouts.choose(film, false, none, null, NOW))
        assertNull(Callouts.choose(film.copy(oscars = null), false, none, null, NOW))

        val aired = show.copy(seasons = listOf(Season(1, listOf(ep1, ep2))))
        val newer = PlayTarget(ep1, PlayAction.PLAY, newEpisodes = true)
        val upToDate = PlayTarget(ep1, PlayAction.WATCH_AGAIN, upcoming = ep2)
        assertEquals(Callout.WatchedAll, Callouts.choose(aired, false, all, newer, NOW))
        assertEquals(Callout.NewEpisodes, Callouts.choose(aired, false, none, newer, NOW))
        assertEquals(Callout.UpToDate(ep2), Callouts.choose(aired, false, none, upToDate, NOW))
        assertEquals(Callout.Oscars("Nominated for 1 Oscar"), Callouts.choose(aired, false, none, null, NOW))
        // A film's watched mark means nothing on a show, a show's "all" nothing on a film.
        assertEquals(Callout.Oscars("Nominated for 1 Oscar"), Callouts.choose(aired, true, none, null, NOW))
        assertEquals(Callout.Oscars("Won 2 Oscars"), Callouts.choose(film, false, all, null, NOW))
        // Nothing aired yet: never "watched all".
        assertEquals(Callout.Oscars("Nominated for 1 Oscar"), Callouts.choose(show.copy(seasons = listOf(Season(1, listOf(ep2)))), false, all, null, NOW))
    }

    @Test
    fun calloutWords() {
        assertEquals("#4 in Top 10 this week", TitleRules.calloutText(Callout.Top10(4), 2026))
        assertEquals("✓ Watched", TitleRules.calloutText(Callout.Watched, 2026))
        assertEquals("✓ Watched all", TitleRules.calloutText(Callout.WatchedAll, 2026))
        assertEquals("New episodes", TitleRules.calloutText(Callout.NewEpisodes, 2026))
        val s5 = Episode("tt2:5:1", 5, 1, "Next", released = null, day = Day(2026, 12, 3))
        assertEquals("Up to date · S5 E1 airs Dec 3", TitleRules.calloutText(Callout.UpToDate(s5), 2026))
        assertEquals("Up to date · S5 E1 coming", TitleRules.calloutText(Callout.UpToDate(s5.copy(day = null)), 2026))
        assertEquals("Won 2 Oscars", TitleRules.calloutText(Callout.Oscars("Won 2 Oscars"), 2026))
    }

    @Test
    fun watchedAllNeedsEveryAiredRegularEpisode() {
        val sp = Episode("tt2:0:1", 0, 1, "Special", released = NOW - 1000)
        val eps = listOf(ep1, ep2, sp)
        assertTrue(TitleRules.allWatched(eps, NOW) { it === ep1 })
        assertFalse(TitleRules.allWatched(eps, NOW) { false })
        // Nothing aired yet: not "watched all".
        assertFalse(TitleRules.allWatched(listOf(ep2), NOW) { true })
    }

    // ---- Play ----

    private fun lastAt(pos: Long, dur: Long, markedWatched: Boolean = false) = LastPlayed(null, Position(pos, dur, NOW), markedWatched = markedWatched)

    @Test
    fun aFilmResumesAsFirePlayerOneDid() {
        val d = TitleDetails(Title("tt1", Kind.MOVIE, "Film", year = "2024"), releasedAt = NOW - 1000)
        val hour = 3_600_000L
        // Past 30 s and not in the last 3 minutes: Resume, with what's left and the line.
        val resume = TitleRules.filmPlay(d, lastAt(hour - 42 * 60_000L, hour), NOW, 2026)
        assertEquals("Resume · 42m left", resume.label)
        assertTrue(resume.restart)
        assertEquals(1f - 42f / 60f, resume.progress!!, 0.001f)
        // A pill too narrow for all of that says "Resume" (the line shows how far); read out, the whole of it.
        assertEquals("Resume", resume.compact)
        assertEquals("Resume · 42m left", resume.says)
        // 30 s or less: Play from the start.
        assertEquals(TitleRules.Play("Play"), TitleRules.filmPlay(d, lastAt(30_000, hour), NOW, 2026))
        assertEquals("Resume · 59m left", TitleRules.filmPlay(d, lastAt(31_000, hour), NOW, 2026).label)
        // The last 3 minutes count as the end.
        assertEquals(TitleRules.Play("Play"), TitleRules.filmPlay(d, lastAt(hour - 180_000, hour), NOW, 2026))
        assertEquals("Resume · 3m left", TitleRules.filmPlay(d, lastAt(hour - 181_000, hour), NOW, 2026).label)
        // Marked watched since: finished.
        assertEquals(TitleRules.Play("Play"), TitleRules.filmPlay(d, lastAt(600_000, hour, markedWatched = true), NOW, 2026))
        // Never played.
        assertEquals(TitleRules.Play("Play"), TitleRules.filmPlay(d, null, NOW, 2026))
    }

    @Test
    fun aFilmNotOutYetSaysWhenAndCannotPlay() {
        val d = TitleDetails(Title("tt1", Kind.MOVIE, "Film", year = "2027"), releasedAt = NOW + 86_400_000, released = Day(2027, 3, 6))
        val p = TitleRules.filmPlay(d, null, NOW, 2026)
        assertEquals("Coming Mar 6, 2027", p.label)
        assertFalse(p.enabled)
        assertEquals("Coming Mar 6", TitleRules.coming(d.copy(released = Day(2026, 3, 6)), 2026))
        assertEquals("Coming 2027", TitleRules.coming(d.copy(released = null), 2026))
        assertEquals("Coming soon", TitleRules.coming(d.copy(released = null, title = d.title.copy(year = null)), 2026))
    }

    @Test
    fun aShowsPlayWords() {
        val d = TitleDetails(Title("tt2", Kind.SERIES, "Show"), seasons = listOf(Season(1, listOf(ep1, ep2))))
        assertEquals(TitleRules.Play("Play S1 E1"), TitleRules.showPlay(d, PlayTarget(ep1, PlayAction.PLAY), 2026))
        // Watch again names the episode where the pill has room for it.
        assertEquals(
            TitleRules.Play("Watch again · S1 E1", compact = "Watch again"),
            TitleRules.showPlay(d, PlayTarget(ep1, PlayAction.WATCH_AGAIN), 2026),
        )
        // Resume names the episode (tuning round 1: the pill is as short as its words): what's left is its progress
        // line and the episode's pod, and is still read out.
        val pos = Position(11 * 60_000L, 23 * 60_000L, NOW)
        val resume = TitleRules.showPlay(d, PlayTarget(ep1, PlayAction.RESUME, position = pos), 2026)
        assertEquals("Resume S1 E1", resume.label)
        assertEquals("Resume S1 E1", resume.compact)
        assertEquals("Resume S1 E1 · 12m left", resume.says)
        assertEquals(11f / 23f, resume.progress!!, 0.001f)
        assertTrue(resume.restart)
        // Play and the states that can't play say one thing, shown and read out.
        TitleRules.showPlay(d, PlayTarget(ep1, PlayAction.PLAY), 2026).let { assertTrue(it.label == it.compact && it.label == it.says) }
        // Nothing aired: when the first one airs, dimmed.
        val unaired = TitleRules.showPlay(d.copy(seasons = listOf(Season(1, listOf(ep2)))), null, 2026)
        assertEquals("Coming Dec 3", unaired.label)
        assertFalse(unaired.enabled)
        // Episodes not known yet (details from the poster alone): plain Play.
        assertEquals(TitleRules.Play("Play"), TitleRules.showPlay(d.copy(seasons = emptyList()), null, 2026))
        // Listed with Specials only: Play never starts one, so it's dimmed and passed by (Sources is left out too).
        val special = Episode("tt2:0:1", 0, 1, "Special", released = NOW - 100_000, day = Day(2026, 11, 18))
        val specials = d.copy(seasons = listOf(Season(0, listOf(special))))
        assertNull(playTarget(specials.episodes, null, NOW))
        assertEquals(TitleRules.Play("Play", enabled = false), TitleRules.showPlay(specials, null, 2026))
    }

    // ---- Episodes ----

    @Test
    fun anEpisodesCardAndTheHeaderWhileItIsChosen() {
        val ep = Episode("tt1:2:5", 2, 5, "Peacekeepers", released = NOW - 400L * 86_400_000, day = Day(2013, 10, 5), runtimeMin = 23)
        val pos = Position(11 * 60_000L, 23 * 60_000L, NOW)
        assertEquals("S2 E5 · Peacekeepers · 23m · Oct 5, 2013 · 12m left", TitleRules.episodeFacts(ep, pos, NOW, 2026))
        assertEquals("S2 E5 · Peacekeepers · 23m · Oct 5, 2013", TitleRules.episodeFacts(ep, null, NOW, 2026))
        assertEquals("E5 · Peacekeepers", TitleRules.episodeLine1(ep.ref))
        val show = Title("tt1", Kind.SERIES, "Korra", background = "bg")
        val card = TitleRules.episodeCard(show, ep, watched = false, position = pos, now = NOW, thisYear = 2026)
        assertEquals("tt1:2:5", card.title.id)
        assertEquals("23m · Oct 5, 2013", card.caption)
        assertEquals(11f / 23f, card.progress!!, 0.001f)
        assertNull(card.badge)
        assertTrue(card.playable)
        // Watched: the ✓, no bar.
        val watched = TitleRules.episodeCard(show, ep, watched = true, position = pos, now = NOW, thisYear = 2026)
        assertTrue(watched.watched)
        assertNull(watched.progress)
        // The length playback found wins over the show's usual one.
        assertEquals("49m · Oct 5, 2013", TitleRules.episodeMeta(ep, Position(60_000, 49 * 60_000L, NOW), NOW, 2026))
        // Aired in the last 7 days: NEW; not aired: dimmed, "Airs Dec 3", not playable, and its header line says so.
        assertEquals("NEW", TitleRules.episodeCard(show, ep1, false, null, NOW, 2026).badge)
        val later = TitleRules.episodeCard(show, ep2, false, null, NOW, 2026)
        assertFalse(later.playable)
        assertEquals("Airs Dec 3", later.caption)
        assertEquals("S1 E2 · Two · Airs Dec 3", TitleRules.episodeFacts(ep2, null, NOW, 2026))
        assertEquals("Not aired yet", TitleRules.airs(ep2.copy(day = null), 2026))
        // A special says so.
        assertEquals("Special 3", TitleRules.episodeCode(EpisodeRef(0, 3)))
    }

    @Test
    fun seasonsSpecialsLastAndWhereThePageOpens() {
        val s0 = Season(0, listOf(Episode("tt2:0:1", 0, 1, "Sp")))
        val s1 = Season(1, listOf(ep1, ep2))
        val s2 = Season(2, listOf(Episode("tt2:2:1", 2, 1, "Next")))
        val order = TitleRules.seasonOrder(listOf(s0, s2, s1))
        assertEquals(listOf(1, 2, 0), order.map { it.number })
        assertEquals(listOf("Season 1", "Season 2", "Specials"), order.map { TitleRules.seasonLabel(it.number) })
        assertTrue(TitleRules.showsSeasonPicker(listOf(s1, s0)))
        assertFalse(TitleRules.showsSeasonPicker(listOf(s1)))
        assertEquals(2, TitleRules.openingSeason(order, s2.episodes[0]))
        assertEquals(1, TitleRules.openingSeason(order, null))
        assertEquals(0, TitleRules.openingSeason(listOf(s0), null))
        assertEquals(1, TitleRules.focusIndex(s1.episodes, ep2))
        assertEquals(0, TitleRules.focusIndex(s1.episodes, s2.episodes[0]))
    }

    // ---- Details: DetailsWindowRulesTest ----

    // ---- The D-pad map ----

    @Test
    fun aShowsPartsAreJoinedAsTheyAreLaidOut() {
        val show = TitleRules.Parts(season = true, episodes = true, more = true)
        fun go(from: Part, dir: Dir, has: TitleRules.Parts = show) = TitleRules.next(from, dir, has, isShow = true)
        // The column: Right to the episodes, Down (past its last row) to More Like This under it; Left is the rail's.
        assertEquals(Part.EPISODES, go(Part.BUTTONS, Dir.RIGHT))
        assertEquals(Part.MORE, go(Part.BUTTONS, Dir.DOWN))
        assertNull(go(Part.BUTTONS, Dir.UP))
        assertNull(go(Part.BUTTONS, Dir.LEFT))
        assertTrue(TitleRules.toRail(Part.BUTTONS, Dir.LEFT, isShow = true))
        // The season dropdown sits above the pods: Down to them, Left to the column, nothing above or right of it.
        assertEquals(Part.EPISODES, go(Part.SEASON, Dir.DOWN))
        assertEquals(Part.BUTTONS, go(Part.SEASON, Dir.LEFT))
        assertNull(go(Part.SEASON, Dir.UP))
        assertNull(go(Part.SEASON, Dir.RIGHT))
        // The pods: Up to the dropdown, Left (from the first) to the column, Down to More Like This.
        assertEquals(Part.SEASON, go(Part.EPISODES, Dir.UP))
        assertEquals(Part.BUTTONS, go(Part.EPISODES, Dir.LEFT))
        assertEquals(Part.MORE, go(Part.EPISODES, Dir.DOWN))
        assertNull(go(Part.EPISODES, Dir.RIGHT))
        // More Like This under them all: Up to the pods; Left from its first poster is the rail's.
        assertEquals(Part.EPISODES, go(Part.MORE, Dir.UP))
        assertNull(go(Part.MORE, Dir.DOWN))
        assertNull(go(Part.MORE, Dir.LEFT))
        assertTrue(TitleRules.toRail(Part.MORE, Dir.LEFT, isShow = true))
        for (p in listOf(Part.SEASON, Part.EPISODES)) assertFalse(TitleRules.toRail(p, Dir.LEFT, isShow = true))
        for (d in listOf(Dir.UP, Dir.DOWN, Dir.RIGHT)) for (p in Part.values()) assertFalse(TitleRules.toRail(p, d, isShow = true))

        // One season: no dropdown, nothing above the pods.
        val one = show.copy(season = false)
        assertNull(go(Part.EPISODES, Dir.UP, one))
        // Episodes not known yet: Right from the column does nothing; with seasons but no pods, the dropdown.
        val none = TitleRules.Parts(season = false, episodes = false, more = true)
        assertNull(go(Part.BUTTONS, Dir.RIGHT, none))
        assertEquals(Part.SEASON, go(Part.BUTTONS, Dir.RIGHT, none.copy(season = true)))
        assertEquals(Part.MORE, go(Part.SEASON, Dir.DOWN, none.copy(season = true)))
        assertEquals(Part.BUTTONS, go(Part.MORE, Dir.UP, none))
        // No More Like This: nothing under the column or the pods.
        assertNull(go(Part.BUTTONS, Dir.DOWN, show.copy(more = false)))
        assertNull(go(Part.EPISODES, Dir.DOWN, show.copy(more = false)))
    }

    @Test
    fun aFilmsMoreLikeThisIsBesideTheColumn() {
        val film = TitleRules.Parts(season = false, episodes = false, more = true)
        fun go(from: Part, dir: Dir, has: TitleRules.Parts = film) = TitleRules.next(from, dir, has, isShow = false)
        assertEquals(Part.MORE, go(Part.BUTTONS, Dir.RIGHT))
        assertNull(go(Part.BUTTONS, Dir.DOWN))
        assertNull(go(Part.BUTTONS, Dir.RIGHT, film.copy(more = false)))
        // Left from its first poster comes back to the column, never the rail; nothing is above or under it.
        assertEquals(Part.BUTTONS, go(Part.MORE, Dir.LEFT))
        assertFalse(TitleRules.toRail(Part.MORE, Dir.LEFT, isShow = false))
        assertNull(go(Part.MORE, Dir.UP))
        assertNull(go(Part.MORE, Dir.DOWN))
        assertTrue(TitleRules.toRail(Part.BUTTONS, Dir.LEFT, isShow = false))
    }

    @Test
    fun theActionColumnIsStackedWithTheCirclesSideBySide() {
        val all: (Which) -> Boolean = { true }
        fun step(from: Which, dir: Dir, can: (Which) -> Boolean = all) = TitleRules.buttonStep(from, dir, can)
        // Down the column: Resume, Play from beginning, Sources, then (+); (⋯) is beside (+).
        assertEquals(Which.RESTART, step(Which.PLAY, Dir.DOWN))
        assertEquals(Which.SOURCES, step(Which.RESTART, Dir.DOWN))
        assertEquals(Which.MY_LIST, step(Which.SOURCES, Dir.DOWN))
        assertEquals(Which.MORE, step(Which.MY_LIST, Dir.RIGHT))
        assertEquals(Which.MY_LIST, step(Which.MORE, Dir.LEFT))
        // And back up: both circles go to Sources.
        assertEquals(Which.SOURCES, step(Which.MY_LIST, Dir.UP))
        assertEquals(Which.SOURCES, step(Which.MORE, Dir.UP))
        assertEquals(Which.RESTART, step(Which.SOURCES, Dir.UP))
        assertEquals(Which.PLAY, step(Which.RESTART, Dir.UP))
        // Presses that leave the column: Up from Play (nowhere), Down from the circles, Left and Right off its sides.
        assertNull(step(Which.PLAY, Dir.UP))
        assertNull(step(Which.MY_LIST, Dir.DOWN))
        assertNull(step(Which.MORE, Dir.DOWN))
        assertNull(step(Which.MORE, Dir.RIGHT))
        assertNull(step(Which.MY_LIST, Dir.LEFT))
        for (w in listOf(Which.PLAY, Which.RESTART, Which.SOURCES)) {
            assertNull(step(w, Dir.LEFT))
            assertNull(step(w, Dir.RIGHT))
        }
        // No resume point: Play from beginning is gone, and passed by both ways.
        val noRestart: (Which) -> Boolean = { it != Which.RESTART }
        assertEquals(Which.SOURCES, step(Which.PLAY, Dir.DOWN, noRestart))
        assertEquals(Which.PLAY, step(Which.SOURCES, Dir.UP, noRestart))
        // A film that isn't out: Play dimmed, no Sources; only the circles take focus.
        val circles: (Which) -> Boolean = { it == Which.MY_LIST || it == Which.MORE }
        assertNull(step(Which.MY_LIST, Dir.UP, circles))
        assertEquals(Which.MORE, step(Which.MY_LIST, Dir.RIGHT, circles))
        assertEquals(Which.MY_LIST, step(Which.PLAY, Dir.DOWN, circles))
    }

    @Test
    fun aPillsWordsAreNeverCutShort() {
        // Ten units a letter: the room decides which form shows, always at the buttons' size while one of them fits.
        val width: (String) -> Float = { it.length * 10f }
        val full = "Watch again · S12 E24"
        assertEquals(TitleRules.Fit(full, small = false), TitleRules.fitWords(full, "Watch again", 210f, width))
        assertEquals(TitleRules.Fit("Watch again", small = false), TitleRules.fitWords(full, "Watch again", 209f, width))
        assertEquals(TitleRules.Fit("Watch again", small = false), TitleRules.fitWords(full, "Watch again", 110f, width))
        // Too wide in both forms (no state's words are): the shorter one, a size down.
        assertEquals(TitleRules.Fit("Watch again", small = true), TitleRules.fitWords(full, "Watch again", 109f, width))
        // Words with no shorter form: as they are, a size down only when they don't fit.
        assertEquals(TitleRules.Fit("Play S1 E1", small = false), TitleRules.fitWords("Play S1 E1", "Play S1 E1", 100f, width))
        assertEquals(TitleRules.Fit("Play S1 E1", small = true), TitleRules.fitWords("Play S1 E1", "Play S1 E1", 99f, width))
    }

    @Test
    fun theWaitAndTheCountOnThePills() {
        // The Play pill says how long it has waited; for whom is on the source line.
        assertEquals("Waiting · 9 s", TitleRules.waitWords("Waiting for MediaFusion · 9 s"))
        assertEquals("Waiting · 12 s", TitleRules.waitWords("Waiting for the add-ons · 12 s"))
        assertEquals("Waiting · 0 s", TitleRules.waitWords("Waiting for sources · 0 s"))
        assertEquals("Waiting", TitleRules.waitWords("Waiting for sources"))
        assertEquals("Waiting", TitleRules.waitWords(""))
        // "Sources · 18": the dot and the count are the quieter part; "Sources" alone has none.
        assertEquals(7, TitleRules.quietFrom("Sources · 18"))
        assertEquals(" · 18", "Sources · 18".substring(TitleRules.quietFrom("Sources · 18")))
        assertEquals(-1, TitleRules.quietFrom("Sources"))
    }

    @Test
    fun backAndTheGlide() {
        assertTrue(TitleRules.backLeaves(Part.BUTTONS))
        for (p in listOf(Part.SEASON, Part.EPISODES, Part.MORE)) assertFalse(TitleRules.backLeaves(p))
        assertTrue(TitleRules.glided(Part.MORE, isShow = true))
        assertFalse(TitleRules.glided(Part.MORE, isShow = false))
        assertFalse(TitleRules.glided(Part.EPISODES, isShow = true))
    }
}
