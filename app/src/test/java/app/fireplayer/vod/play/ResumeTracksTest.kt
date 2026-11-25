package app.fireplayer.vod.play

import androidx.media3.common.C
import app.fireplayer.vod.Card
import app.fireplayer.vod.EpisodeRef
import app.fireplayer.vod.FakeVodRepository
import app.fireplayer.vod.Kind
import app.fireplayer.vod.Opened
import app.fireplayer.vod.PlayOrigin
import app.fireplayer.vod.PlayRequest
import app.fireplayer.vod.SourceTarget
import app.fireplayer.vod.StartAt
import app.fireplayer.vod.play.ResumeTracks.Kept
import app.fireplayer.vod.play.ResumeTracks.Subs
import app.fireplayer.vod.play.ResumeTracks.SubsPick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [KeptTracks.Store] in memory, for tests. */
class MemoryTrackStore : KeptTracks.Store {
    val lines = LinkedHashMap<String, String>()
    override fun get(key: String): String? = lines[key]
    override fun all(): Map<String, String> = HashMap(lines)
    override fun write(put: Map<String, String>, remove: Collection<String>) {
        remove.forEach { lines.remove(it) }
        lines.putAll(put)
    }
}

/**
 * The sound and subtitles last chosen, put back on a resume ([ResumeTracks], [KeptTracks]): how they're kept, how a
 * copy's tracks are matched against them, and which plays put them back.
 */
class ResumeTracksTest {

    private fun track(
        language: String?,
        label: String? = null,
        roles: Int = 0,
        forced: Boolean = false,
        channels: Int = -1,
        copy: String? = null,
        id: String? = null,
    ) = ResumeTracks.Track(language, label, roles, forced, channels, copy, id)

    private val commentary = C.ROLE_FLAG_COMMENTARY
    private val sdh = C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND

    // ---- Kept as a line ----

    @Test
    fun keptLinesReadBackAsWritten() {
        val audio = track("en", "Director's commentary", commentary, channels = 2, copy = "${"a".repeat(40)}|0", id = "3")
        val embedded = Subs.Embedded(track("en", "English SDH", sdh, copy = "torrentio", id = "0:5"))
        listOf(
            Kept(audio, null, 5L),
            Kept(null, Subs.Off, 6L),
            Kept(audio, embedded, 7L),
            Kept(null, Subs.Downloaded("11-Film.2019.srt", "en", "vod|tt0133093|${"a".repeat(40)}|0"), 8L),
            Kept(audio, Subs.Downloaded("12-Film.srt", null, null), 9L),
            Kept(track(null), Subs.Embedded(track(null, forced = true)), 10L),
        ).forEach { assertEquals(it, ResumeTracks.decode(ResumeTracks.encode(it))) }
    }

    @Test
    fun aLineFromALaterVersionStillReadsAndOddOnesAreLeftAlone() {
        val kept = Kept(track("fr", "VF", channels = 6, copy = "c", id = "2"), Subs.Off, 42L)
        val line = ResumeTracks.encode(kept)
        // Later versions only add fields at the end.
        assertEquals(kept, ResumeTracks.decode("$line\u0001more\u0001fields"))
        assertEquals(kept, ResumeTracks.decode("2" + line.drop(1)))
        // Only the sound written (a line cut after it): the sound alone.
        val audioOnly = line.split('\u0001').take(10).joinToString("\u0001")
        assertEquals(Kept(kept.audio, null, 42L), ResumeTracks.decode(audioOnly))
        // Nothing usable: none.
        assertNull(ResumeTracks.decode(null))
        assertNull(ResumeTracks.decode(""))
        assertNull(ResumeTracks.decode("garbage"))
        assertNull(ResumeTracks.decode("x\u00015"))
        assertNull(ResumeTracks.decode(ResumeTracks.encode(Kept(null, null, 1L))))
        // A name with the separator in it doesn't shift the fields.
        val odd = Kept(track("en", "A\u0001B"), null, 3L)
        assertEquals("A B", ResumeTracks.decode(ResumeTracks.encode(odd))?.audio?.label)
        // Never a link: the kept line has only what it was given (a copy's release, a file's name, the video's key).
        assertFalse(ResumeTracks.encode(Kept(null, Subs.Downloaded("1-a.srt", "en", "vod|tt1|x|0"), 1L)).contains("://"))
    }

    // ---- Matching a copy's tracks ----

    @Test
    fun onTheSameCopyTheVeryTrack() {
        // Two English 5.1 tracks alike in every way: only the id tells them apart.
        val tracks = listOf(track("en", channels = 6, id = "2"), track("en", channels = 6, id = "3"), track("fr", channels = 6, id = "4"))
        val wish = track("en", channels = 6, copy = "c1", id = "3")
        assertEquals(1, ResumeTracks.match(wish, tracks, "c1", audio = true))
        // Its id as the file has it once subtitles are added ("0:3"), or the other way round.
        assertEquals(1, ResumeTracks.match(wish, tracks.map { it.copy(id = "0:${it.id}") }, "c1", audio = true))
        assertEquals(1, ResumeTracks.match(wish.copy(id = "0:3"), tracks, "c1", audio = true))
        // Another copy: its ids mean nothing there, so the first of the best.
        assertEquals(0, ResumeTracks.match(wish, tracks, "c2", audio = true))
        // The same copy, but that track isn't what it was: the best match instead.
        assertEquals(0, ResumeTracks.match(wish.copy(id = "4"), tracks, "c1", audio = true))
    }

    @Test
    fun onAnotherCopyTheSameLanguageThenKindThenNameThenChannels() {
        // Language first: no French there, nothing fits (today's default stays).
        val english = listOf(track("en", channels = 6), track("en", channels = 2))
        assertEquals(-1, ResumeTracks.match(track("fr", channels = 6), english, null, audio = true))
        assertEquals(1, ResumeTracks.match(track("fr"), listOf(track("en"), track("fr")), null, audio = true))
        // Main sound over commentary, whatever the channels or the name.
        val sound = listOf(track("en", "Commentary", commentary, channels = 6), track("en", "Stereo", channels = 2))
        assertEquals(1, ResumeTracks.match(track("en", "Commentary", channels = 6), sound, null, audio = true))
        assertEquals(0, ResumeTracks.match(track("en", "Director", commentary, channels = 2), sound, null, audio = true))
        // Then the name, then the channels.
        val named = listOf(track("en", "DD", channels = 6), track("en", "Atmos", channels = 8))
        assertEquals(1, ResumeTracks.match(track("en", "atmos", channels = 6), named, null, audio = true))
        val plain = listOf(track("en", channels = 2), track("en", channels = 6))
        assertEquals(1, ResumeTracks.match(track("en", channels = 6), plain, null, audio = true))
        // Subtitles have no channels to go by: the first of the kind.
        assertEquals(0, ResumeTracks.match(track("en", channels = 6), plain, null, audio = false))
        // The language written the same way first, else another way of writing it.
        assertEquals(1, ResumeTracks.match(track("en"), listOf(track("en-US"), track("EN")), null, audio = true))
        assertEquals(0, ResumeTracks.match(track("en"), listOf(track("en-US"), track("fr")), null, audio = true))
        // Undetermined matches undetermined only.
        assertEquals(1, ResumeTracks.match(track("und"), listOf(track("en"), track(null)), null, audio = true))
    }

    @Test
    fun fullForcedAndSdhSubtitlesAreToldApart() {
        val forced = track("en", "Forced", forced = true)
        val hearing = track("en", "English SDH", sdh)
        val full = track("en", "English")
        // Full ones: the full track; without one, SDH (a full text with sound notes) before forced (almost nothing).
        assertEquals(2, ResumeTracks.match(track("en"), listOf(forced, hearing, full), null, audio = false))
        assertEquals(1, ResumeTracks.match(track("en"), listOf(forced, hearing), null, audio = false))
        // Forced ones and SDH ones: their own kind.
        assertEquals(1, ResumeTracks.match(track("en", forced = true), listOf(full, forced), null, audio = false))
        assertEquals(1, ResumeTracks.match(track("en", roles = sdh), listOf(full, hearing), null, audio = false))
        // Role flags other than the kinds (main, alternate) don't count against a track.
        assertEquals(0, ResumeTracks.match(track("en"), listOf(full.copy(roleFlags = C.ROLE_FLAG_MAIN), forced), null, audio = false))
    }

    @Test
    fun subtitlesOffEmbeddedOrADownloadedFile() {
        val embedded = listOf(track("en", "Forced", forced = true), track("en", "English"), track("es", "Spanish"))
        val kept = { _: String -> true }
        val gone = { _: String -> false }
        // Off stays off, whatever the copy has.
        assertEquals(SubsPick.Off, ResumeTracks.pickSubs(Subs.Off, embedded, "c", kept))
        // One of the film's own: its match on this copy, or nothing (today's default).
        assertEquals(SubsPick.Embedded(2), ResumeTracks.pickSubs(Subs.Embedded(track("es", copy = "other")), embedded, "c", kept))
        assertEquals(SubsPick.Default, ResumeTracks.pickSubs(Subs.Embedded(track("ar")), embedded, "c", kept))
        // A downloaded file: that file again while it's kept.
        val file = Subs.Downloaded("11-Film.srt", "en", "vod|tt1|a|0")
        assertEquals(SubsPick.File("11-Film.srt"), ResumeTracks.pickSubs(file, embedded, "c", kept))
        // Gone: the film's own full subtitles in its language; none in it, or no language known, today's default.
        assertEquals(SubsPick.Embedded(1), ResumeTracks.pickSubs(file, embedded, "c", gone))
        assertEquals(SubsPick.Default, ResumeTracks.pickSubs(file.copy(language = "ar"), embedded, "c", gone))
        assertEquals(SubsPick.Default, ResumeTracks.pickSubs(file.copy(language = null), embedded, "c", gone))
        assertEquals(SubsPick.Default, ResumeTracks.pickSubs(file, emptyList(), "c", gone))
        // Only the file asked about is looked for.
        var asked: String? = null
        ResumeTracks.pickSubs(file, embedded, "c") { asked = it; true }
        assertEquals("11-Film.srt", asked)
    }

    // ---- Which plays put them back ----

    private val playHost = object : SourcePlayback.Host {
        override fun showProgress(text: String) = Unit
        override fun startSource(ready: Opened.Ready, startMs: Long, switching: Boolean) = Unit
        override fun ask(question: String, choices: List<Pair<String, () -> Unit>>, onDismiss: () -> Unit) = Unit
        override fun stopWith(message: String) = Unit
        override fun notice(text: String) = Unit
        override fun positionMs() = 0L
        override fun retryAfter(ms: Long) = Unit
        override fun decodeOnDevice() = Unit
        override fun playNext(next: PlayRequest) = Unit
    }

    @Test
    fun theResumePathsPutThemBackAndNoOtherPlayDoes() {
        val library = FakeLibrary()
        val film = SourceTarget(Fixtures.film)
        // The title page: Resume (or an episode card that resumes), a Sources row played from where it stopped.
        assertTrue(ResumeTracks.applies(VodLaunch.fromTitlePage(film, StartAt.At(600_000), null, library)))
        assertTrue(ResumeTracks.applies(VodLaunch.fromTitlePage(film, StartAt.At(600_000), null, library, chosen = Fixtures.source(1))))
        // Play from beginning, from the page or a Sources row: today's defaults.
        assertFalse(ResumeTracks.applies(VodLaunch.fromTitlePage(film, StartAt.Beginning, null, library)))
        assertFalse(ResumeTracks.applies(VodLaunch.fromTitlePage(film, StartAt.Beginning, null, library, chosen = Fixtures.source(1))))
        // A Continue card on Home (Glass Home's row makes the same play, history/VodContinue).
        val repo = FakeVodRepository()
        repo.recordProgress(Fixtures.film, null, 600_000, 7_200_000)
        val card = VodLaunch.fromContinueCard(Card(Fixtures.film), repo, library)!!
        assertEquals(PlayOrigin.CONTINUE_CARD, card.origin)
        assertTrue(ResumeTracks.applies(card))
        // A Continue card for an episode watched to its end plays the next from the beginning: not a resume.
        repo.recordProgress(Fixtures.show, EpisodeRef(1, 2), 2_690_000, 2_700_000)
        assertFalse(ResumeTracks.applies(VodLaunch.fromContinueCard(Card(Fixtures.show, episode = EpisodeRef(1, 2)), repo, library)!!))
        // The player's Episodes sheet: an episode that stopped resumes (yes); one from its beginning, or Next episode
        // (always from the beginning), keeps today's rule (the kind picked before, the show's remembered sound).
        val episode = SourceTarget(Fixtures.show, EpisodeRef(1, 1))
        val request = PlayRequest(episode, StartAt.At(60_000), PlayOrigin.CONTINUE_CARD)
        val playing = SourcePlayback(request, FakeSession(request), library, FakeTicker(), playHost)
        assertTrue(ResumeTracks.applies(playing.episodeRequest(EpisodeRef(1, 3), StartAt.At(300_000))))
        assertFalse(ResumeTracks.applies(playing.episodeRequest(EpisodeRef(1, 3), StartAt.Beginning)))
        assertFalse(ResumeTracks.applies(playing.nextRequest(EpisodeRef(1, 2))))
        assertEquals(PlayOrigin.NEXT_EPISODE, playing.nextRequest(EpisodeRef(1, 2)).origin)
    }

    // ---- Kept per film and episode ----

    @Test
    fun theLastSoundAndTheLastSubtitlesAreKeptApart() {
        val store = MemoryTrackStore()
        val kept = KeptTracks(store)
        val audio = track("en", "Commentary", commentary, channels = 2, copy = "c", id = "3")
        kept.chose(Kind.SERIES, "tt1695360:1:2", audio = audio, now = 10L)
        kept.chose(Kind.SERIES, "tt1695360:1:2", subs = Subs.Off, now = 20L)
        // The subtitles chosen after kept the sound chosen before; each title on its own line, by kind and id.
        assertEquals(Kept(audio, Subs.Off, 20L), kept.read(Kind.SERIES, "tt1695360:1:2"))
        assertEquals(setOf("series|tt1695360:1:2"), store.lines.keys)
        assertNull(kept.read(Kind.SERIES, "tt1695360:1:3"))
        assertNull(kept.read(Kind.MOVIE, "tt1695360:1:2"))
        // The last choice of each kind wins.
        val file = Subs.Downloaded("11-a.srt", "en", "vod|tt1695360:1:2|c")
        kept.chose(Kind.SERIES, "tt1695360:1:2", subs = file, now = 30L)
        kept.chose(Kind.SERIES, "tt1695360:1:2", audio = audio.copy(language = "fr"), now = 40L)
        assertEquals(Kept(audio.copy(language = "fr"), file, 40L), kept.read(Kind.SERIES, "tt1695360:1:2"))
        // Nothing chosen: nothing written.
        kept.chose(Kind.MOVIE, "tt0133093", now = 50L)
        assertNull(kept.read(Kind.MOVIE, "tt0133093"))
    }

    @Test
    fun onlyTheLatestFewHundredTitlesAreKept() {
        val store = MemoryTrackStore()
        val kept = KeptTracks(store)
        for (i in 0 until KeptTracks.KEPT) kept.chose(Kind.MOVIE, "tt$i", subs = Subs.Off, now = 1_000L + i)
        assertEquals(KeptTracks.KEPT, store.lines.size)
        // Choosing again for one kept changes nothing else.
        kept.chose(Kind.MOVIE, "tt0", audio = track("en"), now = 5_000L)
        assertEquals(KeptTracks.KEPT, store.lines.size)
        // A new one: the oldest goes (tt1 now; tt0 was chosen for since).
        kept.chose(Kind.MOVIE, "new", subs = Subs.Off, now = 6_000L)
        assertEquals(KeptTracks.KEPT, store.lines.size)
        assertNull(kept.read(Kind.MOVIE, "tt1"))
        assertEquals(Subs.Off, kept.read(Kind.MOVIE, "tt0")?.subs)
        assertEquals(Subs.Off, kept.read(Kind.MOVIE, "new")?.subs)
    }

    @Test
    fun forgottenByTitleAShowWithItsEpisodes() {
        val store = MemoryTrackStore()
        val kept = KeptTracks(store)
        kept.chose(Kind.MOVIE, "tt0133093", subs = Subs.Off, now = 1L)
        kept.chose(Kind.SERIES, "tt1695360:1:1", subs = Subs.Off, now = 2L)
        kept.chose(Kind.SERIES, "tt1695360:2:5", subs = Subs.Off, now = 3L)
        kept.chose(Kind.SERIES, "tt16953600:1:1", subs = Subs.Off, now = 4L)
        assertTrue(kept.has(Kind.SERIES, "tt1695360"))
        assertTrue(kept.has(Kind.MOVIE, "tt0133093"))
        assertFalse(kept.has(Kind.SERIES, "tt0133093"))
        kept.forget(Kind.SERIES, "tt1695360")
        // Every episode of that show, nothing of another title (a longer id that starts the same way included).
        assertFalse(kept.has(Kind.SERIES, "tt1695360"))
        assertEquals(setOf("movie|tt0133093", "series|tt16953600:1:1"), store.lines.keys)
        kept.forget(Kind.MOVIE, "tt0133093")
        assertEquals(setOf("series|tt16953600:1:1"), store.lines.keys)
    }
}
