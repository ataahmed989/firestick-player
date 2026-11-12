package app.fireplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phineas and Ferb's first season: 26 half-hours on the provider, 47 stories on TMDB (data as TMDB and TVmaze had it). */
class JoinedEpisodesTest {

    private val tmdb = listOf(
        JoinedEpisodes.Story(1, 1, "Rollercoaster", 11),
        JoinedEpisodes.Story(1, 2, "Lawn Gnome Beach Party of Terror", 11),
        JoinedEpisodes.Story(1, 3, "Flop Starz", 11),
        JoinedEpisodes.Story(1, 4, "The Fast and the Phineas", 11),
        JoinedEpisodes.Story(1, 5, "Lights, Candace, Action!", 11),
        JoinedEpisodes.Story(1, 6, "Raging Bully", 9),
        JoinedEpisodes.Story(1, 7, "Candace Loses Her Head", 11),
        JoinedEpisodes.Story(1, 8, "I, Brobot", 11),
        JoinedEpisodes.Story(1, 9, "Run Away Runway", 11),
        JoinedEpisodes.Story(1, 10, "The Magnificent Few", 11),
        JoinedEpisodes.Story(1, 11, "S'Winter", 11),
        JoinedEpisodes.Story(1, 12, "Jerk De Soleil", 11),
        JoinedEpisodes.Story(1, 13, "Are You My Mummy?", 11),
        JoinedEpisodes.Story(1, 14, "Ready For The Bettys", 11),
        JoinedEpisodes.Story(1, 15, "I Scream, You Scream", 11),
        JoinedEpisodes.Story(1, 16, "Toy to the World", 11),
        JoinedEpisodes.Story(1, 17, "Get That Bigfoot Outa My Face!", 11),
        JoinedEpisodes.Story(1, 18, "It's a Mud, Mud, Mud, Mud World", 11),
        JoinedEpisodes.Story(1, 19, "Mom's Birthday", 11),
        JoinedEpisodes.Story(1, 20, "Journey to the Center of Candace", 22),
        JoinedEpisodes.Story(1, 21, "It's About Time!", 22),
        JoinedEpisodes.Story(1, 22, "Dude, We're Getting the Band Back Together", 11),
        JoinedEpisodes.Story(1, 23, "Tree to Get Ready", 11),
        JoinedEpisodes.Story(1, 24, "The Ballad of Badbeard", 11),
        JoinedEpisodes.Story(1, 25, "Greece Lightning", 11),
        JoinedEpisodes.Story(1, 26, "Leave the Busting to Us", 11),
        JoinedEpisodes.Story(1, 27, "Crack That Whip", 11),
        JoinedEpisodes.Story(1, 28, "The Best Lazy Day Ever", 11),
        JoinedEpisodes.Story(1, 29, "Boyfriend From 27,000 B.C.", 11),
        JoinedEpisodes.Story(1, 30, "Voyage to the Bottom of Buford", 11),
        JoinedEpisodes.Story(1, 31, "A Hard Day's Knight", 11),
        JoinedEpisodes.Story(1, 32, "Traffic Cam Caper", 11),
        JoinedEpisodes.Story(1, 33, "Bowl-R-Rama Drama", 11),
        JoinedEpisodes.Story(1, 34, "Got Game?", 11),
        JoinedEpisodes.Story(1, 35, "Comet Kermilian", 11),
        JoinedEpisodes.Story(1, 36, "Put That Putter Away", 11),
        JoinedEpisodes.Story(1, 37, "Does This Duckbill Make Me Look Fat?", 11),
        JoinedEpisodes.Story(1, 38, "The Flying Fishmonger", 22),
        JoinedEpisodes.Story(1, 39, "One Good Scare Ought to Do It!", 11),
        JoinedEpisodes.Story(1, 40, "The Monster of Phineas-n-Ferbenstein", 11),
        JoinedEpisodes.Story(1, 41, "Oil on Candace", 11),
        JoinedEpisodes.Story(1, 42, "Out of Toon", 11),
        JoinedEpisodes.Story(1, 43, "Hail Doofania!", 22),
        JoinedEpisodes.Story(1, 44, "Out to Launch", 22),
        JoinedEpisodes.Story(1, 45, "Phineas and Ferb Get Busted", 11),
        JoinedEpisodes.Story(1, 46, "Unfair Science Fair", 11),
        JoinedEpisodes.Story(1, 47, "Unfair Science Fair Redux (Another Story)", 0),
    )

    private val tvmaze = listOf(
        JoinedEpisodes.Story(1, 1, "Rollercoaster", 15),
        JoinedEpisodes.Story(1, 2, "Lawn Gnome Beach Party of Terror", 15),
        JoinedEpisodes.Story(1, 3, "Flop Starz", 15),
        JoinedEpisodes.Story(1, 4, "The Fast and the Phineas", 15),
        JoinedEpisodes.Story(1, 5, "Lights, Candace, Action!", 15),
        JoinedEpisodes.Story(1, 6, "Raging Bully", 15),
        JoinedEpisodes.Story(1, 7, "Candace Loses Her Head", 15),
        JoinedEpisodes.Story(1, 8, "I, Brobot", 15),
        JoinedEpisodes.Story(1, 9, "Run Away Runway", 15),
        JoinedEpisodes.Story(1, 10, "The Magnificent Few", 15),
        JoinedEpisodes.Story(1, 11, "S'Winter", 15),
        JoinedEpisodes.Story(1, 12, "Jerk De Soleil", 15),
        JoinedEpisodes.Story(1, 13, "Are You My Mummy?", 15),
        JoinedEpisodes.Story(1, 14, "Ready for the Bettys", 15),
        JoinedEpisodes.Story(1, 15, "I Scream, You Scream", 15),
        JoinedEpisodes.Story(1, 16, "Toy to the World", 15),
        JoinedEpisodes.Story(1, 17, "Get That Bigfoot Outta My Face!", 15),
        JoinedEpisodes.Story(1, 18, "It's a Mud, Mud, Mud, Mud World", 15),
        JoinedEpisodes.Story(1, 19, "Mom's Birthday", 15),
        JoinedEpisodes.Story(1, 20, "Journey to the Center of Candace", 15),
        JoinedEpisodes.Story(1, 21, "It's About Time!", 30),
        JoinedEpisodes.Story(1, 22, "Dude, We're Getting the Band Back Together", 30),
        JoinedEpisodes.Story(1, 23, "Tree to Get Ready", 15),
        JoinedEpisodes.Story(1, 24, "The Ballad of Badbeard", 15),
        JoinedEpisodes.Story(1, 25, "Greece Lightning", 15),
        JoinedEpisodes.Story(1, 26, "Leave the Busting to Us!", 15),
        JoinedEpisodes.Story(1, 27, "Crack That Whip", 15),
        JoinedEpisodes.Story(1, 28, "The Best Lazy Day Ever", 15),
        JoinedEpisodes.Story(1, 29, "Boyfriend from 27,000 B.C.", 15),
        JoinedEpisodes.Story(1, 30, "Voyage to the Bottom of Buford", 15),
        JoinedEpisodes.Story(1, 31, "A Hard Day's Knight", 15),
        JoinedEpisodes.Story(1, 32, "Traffic Cam Caper", 15),
        JoinedEpisodes.Story(1, 33, "Bowl-R-Ama Drama", 15),
        JoinedEpisodes.Story(1, 34, "Got Game?", 15),
        JoinedEpisodes.Story(1, 35, "Comet Kermillian", 15),
        JoinedEpisodes.Story(1, 36, "Put That Putter Away", 15),
        JoinedEpisodes.Story(1, 37, "Does This Duckbill Make Me Look Fat?", 15),
        JoinedEpisodes.Story(1, 38, "The Flying Fishmonger", 15),
        JoinedEpisodes.Story(1, 39, "One Good Scare Ought to Do It!", 30),
        JoinedEpisodes.Story(1, 40, "The Monster of Phineas n Ferbenstein", 15),
        JoinedEpisodes.Story(1, 41, "Oil on Candace", 15),
        JoinedEpisodes.Story(1, 42, "Out of Toon", 15),
        JoinedEpisodes.Story(1, 43, "Hail Doofania!", 15),
        JoinedEpisodes.Story(1, 44, "Out to Launch", 30),
        JoinedEpisodes.Story(1, 45, "Phineas and Ferb Get Busted!", 30),
        JoinedEpisodes.Story(1, 46, "Unfair Science Fair", 15),
        JoinedEpisodes.Story(1, 47, "Unfair Science Fair Redux (Another Story)", 15),
    )

    // TMDB's "Disney Channel (Double)" group, season 1: TMDB episode numbers in its order.
    private val doubleOrder = listOf(1, 7, 2, 4, 10, 11, 13, 3, 6, 5, 17, 23, 21, 12, 16, 39, 31, 8, 19, 20, 9, 15, 18, 24, 22, 14, 38, 45, 25, 26, 27, 28, 29, 30, 37, 36, 32, 33, 40, 41, 46, 47, 44, 34, 35, 42, 43)

    // Wikipedia's list of the half-hours as aired.
    private val aired = listOf(
        "Rollercoaster / Candace Loses Her Head",
        "The Fast and the Phineas / Lawn Gnome Beach Party of Terror",
        "The Magnificent Few / S'Winter",
        "Are You My Mummy? / Flop Starz",
        "Raging Bully / Lights, Candace, Action!",
        "Get That Bigfoot Outa My Face! / Tree to Get Ready",
        "It's About Time!",
        "Jerk De Soleil / Toy to the World",
        "One Good Scare Ought to Do It!",
        "A Hard Day's Knight / I, Brobot",
        "Mom's Birthday / Journey to the Center of Candace",
        "Run Away Runway / I Scream, You Scream",
        "It's a Mud, Mud, Mud, Mud World / The Ballad of Badbeard",
        "Dude, We're Getting the Band Back Together",
        "Ready for the Bettys / The Flying Fishmonger",
        "Phineas and Ferb Get Busted!",
        "Greece Lightning / Leave the Busting to Us!",
        "Crack That Whip / The Best Lazy Day Ever",
        "Boyfriend from 27,000 B.C. / Voyage to the Bottom of Buford",
        "Put That Putter Away / Does This Duckbill Make Me Look Fat?",
        "Traffic Cam Caper / Bowl-R-Ama Drama",
        "The Monster of Phineas-n-Ferbenstein / Oil on Candace",
        "Unfair Science Fair / Unfair Science Fair Redux (Another Story)",
        "Out to Launch",
        "Got Game? / Comet Kermillian",
        "Out of Toon / Hail Doofania!",
    )

    private fun key(name: String) = name.lowercase().filter { it.isLetter() }.take(4)

    private fun halves(parts: List<List<JoinedEpisodes.Story>>) = parts.map { p -> p.map { key(it.name) }.toSet() }

    @Test
    fun doubleGroupWithTvmazeLengthsGivesTheAiredHalfHours() {
        val stories = JoinedEpisodes.withLengths(tmdb, tvmaze)
        val order = JoinedEpisodes.orderIn(doubleOrder.map { 1 to it }, stories)
        assertNotNull(order)
        val pairing = JoinedEpisodes.pair(List(26) { "" }, 26, stories, listOf(order!!))!!
        assertTrue(pairing.sure)
        assertEquals(aired.map { a -> a.split(" / ").map { key(it) }.toSet() }, halves(pairing.parts))
    }

    @Test
    fun groupWinsOverTmdbOrderEvenWithTmdbLengths() {
        val order = JoinedEpisodes.orderIn(doubleOrder.map { 1 to it }, tmdb)!!
        val pairing = JoinedEpisodes.pair(List(26) { "" }, 26, tmdb, listOf(order))!!
        assertFalse(pairing.sure)
        assertEquals(setOf("roll", "cand"), pairing.parts[0].map { key(it.name) }.toSet())
    }

    @Test
    fun providerNamesHoldingBothHalvesDecide() {
        val titles = aired
        val pairing = JoinedEpisodes.pair(titles, 26, tmdb, emptyList())!!
        assertTrue(pairing.sure)
        assertEquals(listOf(1, 7), pairing.parts[0].map { it.number })
    }

    @Test
    fun seasonOfHalfHoursLooksJoined() {
        assertTrue(JoinedEpisodes.looksJoined(26, 26, 22, tmdb, titled = false))
        // Same length as TMDB's: just some episodes missing.
        assertFalse(JoinedEpisodes.looksJoined(26, 26, 11, tmdb, titled = false))
        // Numbered past what it has: missing episodes, not joined ones.
        assertFalse(JoinedEpisodes.looksJoined(26, 40, 22, tmdb, titled = false))
        // Full-length episodes on TMDB too.
        val long = tmdb.map { JoinedEpisodes.Story(it.season, it.number, it.name, 44) }
        assertFalse(JoinedEpisodes.looksJoined(26, 26, 44, long, titled = false))
    }

    @Test
    fun splitNeedsOneOrTwoEach() {
        assertEquals(null, JoinedEpisodes.split(tmdb, 20))
        assertEquals(47, JoinedEpisodes.split(tmdb, 47)!!.first.size)
    }

    @Test
    fun subtitlesNamedForEitherHalfAreKnown() {
        val names = listOf("Rollercoaster", "Candace Loses Her Head")
        assertEquals(true, app.fireplayer.subs.SubtitleRanker.namesEpisode("Candace Loses Her Head", "x", names))
        assertEquals(true, app.fireplayer.subs.SubtitleRanker.namesEpisode("", "Phineas.and.Ferb.S01E01.Rollercoaster.DSNP", names))
        assertEquals(false, app.fireplayer.subs.SubtitleRanker.namesEpisode("Flop Starz", "Phineas.and.Ferb.S01E03", names))
        assertEquals(null, app.fireplayer.subs.SubtitleRanker.namesEpisode("", "Phineas.and.Ferb.S01E03", names))
    }

    @Test
    fun providerNamesWithDashesOrSomeSinglesDecide() {
        val titles = aired.map { it.replace(" / ", " - ") }
        val pairing = JoinedEpisodes.pair(titles, 26, tmdb, emptyList())!!
        assertTrue(pairing.sure)
        assertEquals(listOf(1, 7), pairing.parts[0].map { it.number })
        // Only single names: not taken as joined by names.
        assertEquals(null, JoinedEpisodes.byTitles(listOf("Rollercoaster", "Flop Starz"), tmdb))
    }
}
