package app.fireplayer.vod.data

import app.fireplayer.vod.Day
import app.fireplayer.vod.EpisodeRef
import app.fireplayer.vod.Kind
import app.fireplayer.vod.LastPlayed
import app.fireplayer.vod.Position
import app.fireplayer.vod.playTarget
import app.fireplayer.vod.data.TitlePageFixtures.NOW
import app.fireplayer.vod.data.TitlePageFixtures.cardLines
import app.fireplayer.vod.data.TitlePageFixtures.expected
import app.fireplayer.vod.data.TitlePageFixtures.meta
import app.fireplayer.vod.data.TitlePageFixtures.ref
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cinemeta's meta read for the title page: films, shows, episodes, dates, and the trimmed form on disk. */
class CinemetaDetailsTest {

    @Test
    fun aFilmMetaGivesItsFacts() {
        val m = meta("cinemeta_meta_film.json", Kind.MOVIE)
        val t = m.title
        assertEquals("tt15239678", t.id)
        assertEquals("Dune: Part Two", t.name)
        assertEquals("2024", t.year)
        assertEquals("8.4", t.imdbRating)
        assertEquals(listOf("Action", "Adventure", "Drama"), t.genres)
        assertEquals("167 min", t.runtime)
        assertEquals(693134, t.tmdbId)
        assertEquals(Dates.parse("2024-03-01"), m.releasedAt)
        assertEquals(listOf("Timothée Chalamet", "Zendaya", "Rebecca Ferguson"), m.cast)
        assertEquals(listOf("Denis Villeneuve"), m.directors)
        assertEquals(listOf("Denis Villeneuve", "Jon Spaihts", "Frank Herbert"), m.writers)
        assertEquals("Won 2 Oscars. 126 wins & 375 nominations total", m.awards)
        assertTrue(m.country!!.startsWith("United States, Canada"))
        assertTrue(m.episodes.isEmpty())
        // Cinemeta's cut description ends at its last whole word.
        assertTrue(t.description!!.endsWith("he endeavors to prevent a terrible…"))
    }

    @Test
    fun missingFieldsAreNoneNotCrashes() {
        val m = meta("cinemeta_meta_film_missing_fields.json", Kind.MOVIE)
        assertEquals("L'Avocat", m.title.name)
        assertNull(m.releasedAt)
        assertNull(m.title.description)
        assertNull(m.title.imdbRating)
        assertTrue(m.title.genres.isEmpty())
        assertTrue(m.writers.isEmpty())
        assertNull(m.awards)
        assertEquals(listOf("Tristan Séguéla"), m.directors)
        // {} (asked with the wrong kind), a meta of the other kind, another id: none.
        assertNull(CinemetaDetails.parse(Fixtures.text("cinemeta_meta_empty.json"), Kind.MOVIE))
        assertNull(CinemetaDetails.parse(Fixtures.text("cinemeta_meta_film.json"), Kind.SERIES))
        assertNull(CinemetaDetails.parse(Fixtures.text("cinemeta_meta_film.json"), Kind.MOVIE, "tt1"))
        // A show without directors or writers or a rating (Nocturne).
        val n = meta("cinemeta_meta_show_airing_new.json", Kind.SERIES)
        assertTrue(n.directors.isEmpty())
        assertTrue(n.writers.isEmpty())
        assertNull(n.title.imdbRating)
        assertEquals("Continuing", n.status)
    }

    @Test
    fun episodesAreSortedWithSpecialsLastAndGapsKept() {
        // Korra lists its 47 specials first; Breaking Bad has them in the middle.
        val korra = meta("cinemeta_meta_show_ended_specials.json", Kind.SERIES).episodes
        assertEquals(99, korra.size)
        assertEquals(listOf("S1 E1", "S1 E2", "S1 E3"), korra.take(3).map(::ref))
        assertEquals(listOf("S0 E45", "S0 E46", "S0 E47"), korra.takeLast(3).map(::ref))
        val bb = meta("cinemeta_meta_show_unsorted.json", Kind.SERIES).episodes
        assertEquals(67, bb.size)
        assertEquals(listOf(1, 2, 3, 4, 5, 0), CinemetaDetails.seasons(bb).map { it.number })
        assertEquals(listOf("S0 E3", "S0 E4", "S0 E5"), bb.takeLast(3).map(::ref))
        // Each (season, episode) once; ids as watched marks key them.
        assertEquals(bb.size, bb.map { it.id }.toSet().size)
        assertEquals("tt0903747:1:1", bb.first().id)
        // Specials have no runtime (their lengths vary); others the show's.
        assertEquals(49, bb.first().runtimeMin)
        assertNull(bb.last().runtimeMin)
    }

    @Test
    fun episodeDatesAreUsPacificAirDays() {
        val korra = meta("cinemeta_meta_show_ended_specials.json", Kind.SERIES).episodes
        // 2012-04-15T05:00Z is the evening of April 14 in the US.
        assertEquals(Day(2012, 4, 14), korra.first().day)
        assertEquals(listOf("E1 · Welcome to Republic City", "23m · Apr 14, 2012"), cardLines(korra.first()))
        val greys = meta("cinemeta_meta_show_long_airing_cut.json", Kind.SERIES).episodes
        val next = greys.first { it.season > 0 && !it.aired(NOW) }
        assertEquals("S23 E6", ref(next))
        assertEquals(Day(2026, 11, 19), next.day)
        assertEquals(listOf("E6 · Till I Collapse", "Airs Nov 19"), cardLines(next))
        // NEW: aired in the last 7 days.
        assertEquals(listOf("S23 E5"), greys.filter { it.isNew(NOW) }.map(::ref))
    }

    @Test
    fun stillsAreAskedAtW500() {
        val e = meta("cinemeta_meta_show_ended_specials.json", Kind.SERIES).episodes.first()
        assertEquals("https://episodes.metahub.space/tt1695360/1/1/w500.jpg", e.still)
        assertEquals("https://example.org/a.jpg", CinemetaDetails.still("https://example.org/a.jpg"))
        assertNull(CinemetaDetails.still(" "))
    }

    @Test
    fun theOscarCalloutIsOnlyForOscars() {
        assertEquals("Won 2 Oscars", CinemetaDetails.oscars("Won 2 Oscars. 126 wins & 375 nominations total"))
        assertEquals("Won 7 Oscars", CinemetaDetails.oscars(meta("cinemeta_meta_film_oscars.json", Kind.MOVIE).awards))
        assertEquals("Nominated for 1 Oscar", CinemetaDetails.oscars("Nominated for 1 Oscar. 5 wins total"))
        assertNull(CinemetaDetails.oscars("Won 3 Primetime Emmys. 20 wins total"))
        assertNull(CinemetaDetails.oscars("Nominated for 1 BAFTA Award37 wins"))
        assertNull(CinemetaDetails.oscars("27 wins & 42 nominations total"))
        assertNull(CinemetaDetails.oscars(null))
    }

    @Test
    fun storyCutsAndEntities() {
        val cut = "A".repeat(200) + " the quick brown fox jumps over the lazy dog and keeps running a" + "..."
        val text = cut.take(230) + "..."
        assertEquals(233, text.length)
        val story = CinemetaDetails.story(text)!!
        assertTrue(story.endsWith("…"))
        assertFalse(story.contains("..."))
        assertEquals("A short one...", CinemetaDetails.story("A short one..."))
        assertNull(CinemetaDetails.story("  "))
        assertEquals("Leah Sava' Jeffries", CinemetaDetails.clean("Leah Sava&apos; Jeffries"))
        assertEquals("Law & Order", CinemetaDetails.clean("Law &amp; Order"))
        val percy = meta("cinemeta_meta_show_season_not_started.json", Kind.SERIES)
        assertTrue("Leah Sava' Jeffries" in percy.cast)
    }

    @Test
    fun theTrimmedFormReadsBackTheSame() {
        for ((name, kind) in listOf(
            "cinemeta_meta_film.json" to Kind.MOVIE,
            "cinemeta_meta_film_missing_fields.json" to Kind.MOVIE,
            "cinemeta_meta_show_ended_specials.json" to Kind.SERIES,
            "cinemeta_meta_show_long_airing_cut.json" to Kind.SERIES,
            "cinemeta_meta_show_unsorted.json" to Kind.SERIES,
        )) {
            val m = meta(name, kind)
            val back = CinemetaDetails.read(JSONObject(CinemetaDetails.write(m).toString()))
            assertEquals(name, m, back)
        }
        // Much smaller than the answer (Korra: 62 KB).
        val korra = CinemetaDetails.write(meta("cinemeta_meta_show_ended_specials.json", Kind.SERIES)).toString()
        assertTrue(korra.length < Fixtures.text("cinemeta_meta_show_ended_specials.json").length / 2)
        assertNull(CinemetaDetails.read(JSONObject("""{"title":{"id":"tt1"}}""")))
    }

    @Test
    fun playTargetsMatchTheReferenceForEveryRecord() {
        val files = mapOf(
            "cinemeta_meta_show_ended_specials.json + tmdb_tv_details_appended.json" to "cinemeta_meta_show_ended_specials.json",
            "cinemeta_meta_show_long_airing_cut.json" to "cinemeta_meta_show_long_airing_cut.json",
            "cinemeta_meta_show_airing_new.json" to "cinemeta_meta_show_airing_new.json",
            "cinemeta_meta_show_season_not_started.json + tmdb_tv_details_airing.json" to "cinemeta_meta_show_season_not_started.json",
            "cinemeta_meta_show_unsorted.json" to "cinemeta_meta_show_unsorted.json",
        )
        var checked = 0
        for ((key, file) in files) {
            val episodes = meta(file, Kind.SERIES).episodes
            val cases = expected.getJSONObject("shows").getJSONObject(key).getJSONArray("play_targets")
            for (i in 0 until cases.length()) {
                val case = cases.getJSONObject(i)
                val last = case.optJSONObject("record")?.let { r ->
                    LastPlayed(
                        EpisodeRef(r.getInt("season"), r.getInt("episode")),
                        Position(r.getLong("positionMs"), r.getLong("durationMs"), NOW - 24 * 3600_000L),
                    )
                }
                val result = case.getJSONArray("result")
                val t = playTarget(episodes, last, NOW)!!
                assertEquals("$key #$i", result.getString(0), TitlePageFixtures.playLabel(t))
                assertEquals("$key #$i", result.getString(1), "${t.episode.season}:${t.episode.episode}")
                assertEquals("$key #$i", if (result.isNull(2)) null else result.getString(2), TitlePageFixtures.upToDate(t))
                checked++
            }
        }
        assertEquals(14, checked)
    }
}
