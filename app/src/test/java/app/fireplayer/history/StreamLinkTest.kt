package app.fireplayer.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Made-up providers and logins.
class StreamLinkTest {

    @Test
    fun filmsAndEpisodesByTheirIds() {
        val film = StreamLink.parse("http://provider.example:8080/movie/USER/PASS/123.mkv")!!
        assertEquals(StreamLink.Kind.MOVIE, film.kind)
        assertEquals("123", film.id)
        assertEquals("mkv", film.extension)
        assertEquals("provider.example:8080", film.server)
        assertNull(film.proxied)

        val episode = StreamLink.parse("https://Provider.Example/series/USER/PASS/456.mp4")!!
        assertEquals(StreamLink.Kind.EPISODE, episode.kind)
        assertEquals("456", episode.id)
        assertEquals("provider.example:443", episode.server)
    }

    @Test
    fun throughTheTitleServerItIsTheSameServer() {
        val direct = StreamLink.parse("http://provider.example/series/USER/PASS/456.mkv")!!
        val proxied = StreamLink.parse("http://127.0.0.1:8000/provider.example:80/series/USER/PASS/456.mkv")!!
        assertEquals("provider.example:80", proxied.proxied)
        assertEquals(direct.server, proxied.server)
        assertEquals(direct.id, proxied.id)
        assertEquals(StreamLink.Kind.EPISODE, proxied.kind)
    }

    @Test
    fun liveAndCatchUpAreLive() {
        assertTrue(StreamLink.parse("http://provider.example/live/USER/PASS/77.ts")!!.isLive)
        assertTrue(StreamLink.parse("http://provider.example/USER/PASS/77")!!.isLive)
        assertTrue(StreamLink.parse("http://provider.example/timeshift/USER/PASS/60/2025-01-01:20-00/77.ts")!!.isLive)
        assertTrue(StreamLink.parse("http://provider.example/streaming/timeshift.php?username=USER&stream=77")!!.isLive)
        assertTrue(StreamLink.parse("http://127.0.0.1:8000/provider.example:80/live/USER/PASS/77.m3u8")!!.isLive)
        assertFalse(StreamLink.parse("http://provider.example/movie/USER/PASS/1.mkv")!!.isLive)
    }

    @Test
    fun otherLinks() {
        val other = StreamLink.parse("https://cdn.example/films/a/b/c/d/trailer.mp4")!!
        assertEquals(StreamLink.Kind.OTHER, other.kind)
        assertNull(other.id)
        assertNull(StreamLink.parse("file:///sdcard/Movies/film.mkv"))
        assertNull(StreamLink.parse("content://media/external/video/1"))
    }

    @Test
    fun aLoginInTheAddressIsLeftOut() {
        val link = StreamLink.parse("http://USER:PASS@provider.example:8080/movie/USER/PASS/1.mkv")!!
        assertEquals("provider.example:8080", link.server)
    }

    @Test
    fun theSourceIsTheProvidersAddressWithoutTheLogin() {
        assertEquals("tv.example.com:8080", StreamLink.parse("http://tv.example.com:8080/series/USER/PASS/456.mkv")!!.source)
        val withLogin = StreamLink.parse("http://USER:PASS@tv.example.com:8080/movie/USER/PASS/1.mkv")!!.source!!
        assertEquals("tv.example.com:8080", withLogin)
        assertFalse(withLogin.contains("USER") || withLogin.contains("PASS"))
        // Through the title server: the provider it stands for, not the title server.
        assertEquals("tv.example.com:8080", StreamLink.parse("http://127.0.0.1:8000/tv.example.com:8080/series/USER/PASS/456.mkv")!!.source)
        // As the address reads: no port when it has none, its letters as written.
        assertEquals("tv.example.com", StreamLink.parse("https://tv.example.com/movie/USER/PASS/1.mp4")!!.source)
        assertEquals("TV.example.com:8080", StreamLink.parse("http://TV.example.com:8080/movie/USER/PASS/1.mp4")!!.source)
        assertEquals("tv.example.com:8080", StreamLink.parse("http://tv.example.com:8080/live/USER/PASS/7.ts?token=x")!!.source)
    }

    @Test
    fun onlyAProvidersEpisodeByItsNumberListsItsShow() {
        assertTrue(StreamLink.parse("http://tv.example.com:8080/series/USER/PASS/456.mkv")!!.listsEpisodes)
        assertTrue(StreamLink.parse("http://127.0.0.1:8000/tv.example.com:8080/series/USER/PASS/456.mkv")!!.listsEpisodes)
        assertFalse(StreamLink.parse("http://tv.example.com:8080/movie/USER/PASS/456.mkv")!!.listsEpisodes)
        assertFalse(StreamLink.parse("http://tv.example.com:8080/live/USER/PASS/456.ts")!!.listsEpisodes)
        assertFalse(StreamLink.parse("http://tv.example.com:8080/series/a/USER/PASS/456.mkv")!!.listsEpisodes)
        assertFalse(StreamLink.parse("http://tv.example.com:8080/series/USER/PASS/abc.mkv")!!.listsEpisodes)
    }
}
