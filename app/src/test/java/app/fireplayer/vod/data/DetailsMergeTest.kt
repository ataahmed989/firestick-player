package app.fireplayer.vod.data

import app.fireplayer.vod.Callout
import app.fireplayer.vod.Day
import app.fireplayer.vod.Episode
import app.fireplayer.vod.EpisodeRef
import app.fireplayer.vod.From
import app.fireplayer.vod.Kind
import app.fireplayer.vod.LastPlayed
import app.fireplayer.vod.Position
import app.fireplayer.vod.Title
import app.fireplayer.vod.TitleDetails
import app.fireplayer.vod.data.TitlePageFixtures.NOW
import app.fireplayer.vod.data.TitlePageFixtures.cardLines
import app.fireplayer.vod.data.TitlePageFixtures.castLine
import app.fireplayer.vod.data.TitlePageFixtures.coming
import app.fireplayer.vod.data.TitlePageFixtures.details
import app.fireplayer.vod.data.TitlePageFixtures.expected
import app.fireplayer.vod.data.TitlePageFixtures.facts
import app.fireplayer.vod.data.TitlePageFixtures.pills
import app.fireplayer.vod.data.TitlePageFixtures.ref
import app.fireplayer.vod.playTarget
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The title page's details put together from Cinemeta and TMDB, checked against the reference's expected file. */
class DetailsMergeTest {

    private fun text(o: JSONObject, key: String): String? = if (o.isNull(key)) null else o.getString(key)

    private fun story(d: TitleDetails) = d.story?.from?.let { if (it == From.TMDB) "TMDB" else "Cinemeta" }

    @Test
    fun filmsMatchTheReference() {
        val films = expected.getJSONObject("films")
        val cases = mapOf(
            "cinemeta_meta_film.json + tmdb_movie_details_appended.json" to details("cinemeta_meta_film.json", Kind.MOVIE, "tmdb_movie_details_appended.json"),
            "cinemeta_meta_film.json without TMDB" to details("cinemeta_meta_film.json", Kind.MOVIE),
            "cinemeta_meta_film_oscars.json" to details("cinemeta_meta_film_oscars.json", Kind.MOVIE),
            "cinemeta_meta_film_upcoming.json + tmdb_movie_details_upcoming.json" to details("cinemeta_meta_film_upcoming.json", Kind.MOVIE, "tmdb_movie_details_upcoming.json"),
            "cinemeta_meta_film_missing_fields.json" to details("cinemeta_meta_film_missing_fields.json", Kind.MOVIE),
        )
        for ((key, d) in cases) {
            val want = films.getJSONObject(key)
            assertEquals(key, want.getString("facts"), facts(d))
            assertEquals(key, text(want, "story_source"), story(d))
            assertEquals(key, text(want, "cast_line"), castLine(d))
            assertEquals(key, text(want, "age_rating"), d.ageRating)
            assertEquals(key, text(want, "oscar_callout"), d.oscars)
            assertEquals(key, text(want, "coming"), coming(d))
        }
    }

    @Test
    fun showsMatchTheReference() {
        val shows = expected.getJSONObject("shows")
        val cases = mapOf(
            "cinemeta_meta_show_ended_specials.json + tmdb_tv_details_appended.json" to details("cinemeta_meta_show_ended_specials.json", Kind.SERIES, "tmdb_tv_details_appended.json"),
            "cinemeta_meta_show_long_airing_cut.json" to details("cinemeta_meta_show_long_airing_cut.json", Kind.SERIES),
            "cinemeta_meta_show_airing_new.json" to details("cinemeta_meta_show_airing_new.json", Kind.SERIES),
            "cinemeta_meta_show_season_not_started.json + tmdb_tv_details_airing.json" to details("cinemeta_meta_show_season_not_started.json", Kind.SERIES, "tmdb_tv_details_airing.json"),
            "cinemeta_meta_show_unsorted.json" to details("cinemeta_meta_show_unsorted.json", Kind.SERIES),
        )
        for ((key, d) in cases) {
            val want = shows.getJSONObject(key)
            assertEquals(key, want.getString("facts"), facts(d))
            assertEquals(key, text(want, "story_source"), story(d))
            // The reference left Cinemeta's "&apos;" in; FirePlayer reads it.
            assertEquals(key, text(want, "cast_line")?.replace("&apos;", "'"), castLine(d))
            assertEquals(key, text(want, "age_rating"), d.ageRating)
            val pills = want.getJSONArray("season_pills")
            assertEquals(key, (0 until pills.length()).map { pills.getString(it) }, pills(d))
            val eps = d.episodes
            assertEquals(key, want.getInt("episodes_total"), eps.size)
            assertEquals(key, want.getInt("episodes_aired"), eps.count { it.aired(NOW) })
            val first = want.getJSONArray("first_three_in_order")
            assertEquals(key, (0 until 3).map { first.getString(it) }, eps.take(3).map(::ref))
            val last = want.getJSONArray("last_three_in_order")
            assertEquals(key, (0 until 3).map { last.getString(it) }, eps.takeLast(3).map(::ref))
            val next = eps.firstOrNull { it.season > 0 && !it.aired(NOW) }
            val wantNext = want.optJSONObject("next_unaired_regular")
            assertEquals(key, wantNext?.getString("episode"), next?.let(::ref))
            assertEquals(key, wantNext?.getString("label_date_pacific"), next?.day?.let { TitlePageFixtures.label(it, false) })
            val new = want.getJSONArray("new_in_last_7_days")
            assertEquals(key, (0 until new.length()).map { new.getString(it) }, eps.filter { it.isNew(NOW) }.map(::ref))
            val cards = want.getJSONObject("cards_sample")
            for (c in cards.keys()) {
                val ep = eps.first { ref(it) == c }
                val lines = cards.getJSONArray(c)
                assertEquals("$key $c", listOf(lines.getString(0), if (lines.isNull(1)) null else lines.getString(1)), cardLines(ep))
            }
        }
    }

    @Test
    fun tmdbWinsStoryCastAndCreatorsAndSaysSo() {
        val d = details("cinemeta_meta_show_ended_specials.json", Kind.SERIES, "tmdb_tv_details_appended.json")
        assertEquals(From.TMDB, d.story!!.from)
        assertEquals(From.TMDB, d.creators!!.from)
        assertEquals(From.TMDB, d.cast!!.from)
        assertNull(d.directors)
        assertNull(d.writers)
        assertEquals(4, d.seasonCount)
        assertEquals("Ended", d.status)
        // A film's crew from TMDB, Cinemeta's without a key.
        val film = details("cinemeta_meta_film.json", Kind.MOVIE, "tmdb_movie_details_appended.json")
        assertEquals(From.TMDB, film.directors!!.from)
        assertEquals(6, film.cast!!.value.size)
        assertEquals(Day(2024, 3, 1), film.released)
        assertNull(film.status)
        assertTrue(film.seasons.isEmpty())
        val plain = details("cinemeta_meta_film.json", Kind.MOVIE)
        assertEquals(From.CINEMETA, plain.writers!!.from)
        assertEquals(listOf("Timothée Chalamet", "Zendaya", "Rebecca Ferguson"), plain.cast!!.value)
        assertEquals("United States, Canada, United Arab Emirates, Hungary, Italy, New Zealand, Jordan", plain.country)
    }

    @Test
    fun nothingButTheTitleIsStillAPage() {
        val asked = Title("tt1", Kind.SERIES, "A show", year = "2020–", description = "Story", genres = listOf("A", "B", "C", "D"),
            runtime = "45 min", imdbRating = "0", background = "https://bg")
        val d = DetailsMerge.compose(asked, null, null, null, 4)
        assertEquals("A show", d.title.name)
        assertEquals(listOf("A", "B", "C"), d.title.genres)
        assertNull(d.title.imdbRating)
        assertEquals(45, d.runtimeMin)
        assertEquals("Story", d.story!!.value)
        assertEquals(4, d.top10Rank)
        assertEquals("https://bg", d.title.background)
        assertTrue(d.seasons.isEmpty())
        // The chosen backdrop wins over the title's.
        assertEquals("https://chosen", DetailsMerge.compose(asked, null, null, "https://chosen", null).title.background)
        // A show's status from TMDB in Cinemeta's words.
        val e = TmdbDetails.Extras(1, Kind.SERIES, status = "Canceled", releaseDate = "2020-01-05")
        val withTmdb = DetailsMerge.compose(asked, null, e, null, null)
        assertEquals("Ended", withTmdb.status)
        assertEquals(Day(2020, 1, 5), withTmdb.released)
        assertEquals("Continuing", DetailsMerge.cinemetaStatus("Returning Series"))
    }

    @Test
    fun theCalloutFollowsTheBriefsOrder() {
        val film = details("cinemeta_meta_film.json", Kind.MOVIE)
        val none = { _: Episode -> false }
        assertEquals(Callout.Top10(4), Callouts.choose(film.copy(top10Rank = 4), true, none, null, NOW))
        assertEquals(Callout.Watched, Callouts.choose(film, true, none, null, NOW))
        assertEquals(Callout.Oscars("Won 2 Oscars"), Callouts.choose(film, false, none, null, NOW))
        assertNull(Callouts.choose(details("cinemeta_meta_film_upcoming.json", Kind.MOVIE), false, none, null, NOW))

        val greys = details("cinemeta_meta_show_long_airing_cut.json", Kind.SERIES)
        val caughtUp = LastPlayed(EpisodeRef(23, 5), Position(2_500_000, 2_580_000, NOW - 3600_000))
        val target = playTarget(greys.episodes, caughtUp, NOW)!!
        assertEquals("S23 E6", ref((Callouts.choose(greys, false, none, target, NOW) as Callout.UpToDate).next))
        // Every aired regular episode watched: "Watched all" (specials don't count).
        val all = { e: Episode -> e.season > 0 && e.aired(NOW) }
        assertEquals(Callout.WatchedAll, Callouts.choose(greys, false, all, target, NOW))
        // A new episode aired after the show was last watched.
        val behind = LastPlayed(EpisodeRef(23, 4), Position(2_500_000, 2_580_000, NOW - 10 * 24 * 3600_000L))
        assertEquals(Callout.NewEpisodes, Callouts.choose(greys, false, none, playTarget(greys.episodes, behind, NOW), NOW))
        assertNull(Callouts.choose(greys, false, none, playTarget(greys.episodes, null, NOW), NOW))
    }

    @Test
    fun markWatchedUpToHereNeverCrossesIntoSpecials() {
        val eps = details("cinemeta_meta_show_unsorted.json", Kind.SERIES).episodes
        val s2e3 = eps.first { it.season == 2 && it.episode == 3 }
        val upTo = EpisodeMarks.upTo(eps, s2e3, NOW)
        assertEquals(7 + 3, upTo.size)
        assertTrue(upTo.none { it.isSpecial })
        val special = eps.first { it.season == 0 && it.episode == 2 }
        assertEquals(listOf("S0 E1", "S0 E2"), EpisodeMarks.upTo(eps, special, NOW).map(::ref))
        // Not aired yet: nothing.
        val greys = details("cinemeta_meta_show_long_airing_cut.json", Kind.SERIES).episodes
        assertTrue(EpisodeMarks.upTo(greys, greys.first { !it.aired(NOW) }, NOW).isEmpty())
    }

    @Test
    fun datesReadIsoAndDays() {
        assertEquals(java.time.Instant.parse("2012-04-15T05:00:00Z").toEpochMilli(), Dates.parse("2012-04-15T05:00:00.000Z"))
        assertEquals(java.time.Instant.parse("2024-02-27T00:00:00Z").toEpochMilli(), Dates.parse("2024-02-27"))
        assertEquals(java.time.Instant.parse("2026-11-20T07:00:00Z").toEpochMilli(), Dates.parse("2026-11-20T08:00:00+01:00"))
        assertNull(Dates.parse("TBA"))
        assertNull(Dates.parse(null))
        assertNull(Dates.parse("2024-13-01"))
        assertEquals(Day(2026, 11, 19), Dates.dayOf(Dates.parse("2026-11-20T05:00:00.000Z")!!, Dates.PACIFIC))
        assertEquals(Day(2026, 11, 20), Dates.dayOf(Dates.parse("2026-11-20T05:00:00.000Z")!!, Dates.UTC))
        assertEquals("2026-11-18", Dates.isoDay(NOW))
        assertEquals(2026, Dates.yearOf(NOW))
    }

    @Test
    fun detailsWindowsPartsAreTmdbsWhenItHasThem() {
        val film = details("cinemeta_meta_film.json", Kind.MOVIE, "tmdb_movie_details_appended.json")
        assertEquals("https://image.tmdb.org/t/p/w185/fixture_c1.jpg", film.castPictures[0])
        assertNull(film.castPictures[2])
        assertEquals(film.cast!!.value.size, film.castPictures.size)
        assertEquals("English", film.language)
        assertEquals(listOf("Legendary Pictures"), film.studios)
        assertTrue(film.networks.isEmpty())
        // Cinemeta's countries first.
        assertTrue(film.country!!.startsWith("United States, Canada"))
        val show = details("cinemeta_meta_show_ended_specials.json", Kind.SERIES, "tmdb_tv_details_appended.json")
        assertEquals(listOf("Nickelodeon"), show.networks)
        assertTrue(show.studios.isEmpty())
        assertTrue(show.castPictures.isEmpty())
        // Without TMDB: none of them, nothing made up.
        val alone = details("cinemeta_meta_film.json", Kind.MOVIE)
        assertTrue(alone.castPictures.isEmpty())
        assertNull(alone.language)
        assertTrue(alone.studios.isEmpty())
        // A meta without countries takes TMDB's, named as Cinemeta names them.
        val meta = TitlePageFixtures.meta("cinemeta_meta_film.json", Kind.MOVIE).copy(country = null)
        val asked = Title(meta.title.id, Kind.MOVIE, meta.title.name)
        val fromTmdb = DetailsMerge.compose(asked, meta, TitlePageFixtures.tmdb("tmdb_movie_details_appended.json", Kind.MOVIE), null, null)
        assertEquals("United States", fromTmdb.country)
        // Portraits only beside TMDB's own cast: with Cinemeta's names, none.
        val noTmdbCast = TitlePageFixtures.tmdb("tmdb_movie_details_appended.json", Kind.MOVIE).copy(cast = emptyList())
        assertTrue(DetailsMerge.compose(asked, meta, noTmdbCast, null, null).castPictures.isEmpty())
    }

    @Test
    fun languagesAreNamedInEnglish() {
        assertEquals("English", DetailsMerge.languageName("en"))
        assertEquals("Korean", DetailsMerge.languageName("KO"))
        assertEquals("Cantonese", DetailsMerge.languageName("cn"))
        assertNull(DetailsMerge.languageName("xx"))
        assertNull(DetailsMerge.languageName("zz"))
        assertNull(DetailsMerge.languageName(""))
        assertNull(DetailsMerge.languageName("e1"))
        assertNull(DetailsMerge.languageName(null))
    }
}
