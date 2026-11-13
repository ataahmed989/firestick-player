package app.fireplayer.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** Which settings go when downloaded subtitles are let go ([Prefs.staleSubtitleTimes]): theirs, and nothing else. */
class PrefsSweepTest {

    // Made-up links: only ever compared.
    private val film = "http://provider.example/movie/1.mkv"
    private val gone = "http://provider.example/movie/2.mkv?a=1|b"

    @Test
    fun onlyWhatWasKeptForSubtitlesThatAreGoneGoes() {
        val kept = listOf(
            // A file still kept: everything found or set for it, on any film.
            "sync|11-kept.srt|$film", "sync|11-kept.srt|$film|at", "sync|11-kept.srt|$film|track", "sync|11-kept.srt|$film|trim",
            "sync|11-kept.srt|$film|drop", "sync|11-kept.srt|$gone|at", "subdelay|11-kept.srt|$film",
            // A film's own "Sync with" choice while it has subtitles kept; a show's choice, jumps and starting delays.
            "syncwith|film:$film", "syncwith|tmdb:1399", "syncjumps|tmdb:1399", "syncstart|tmdb:1399|720p hdtv x264 avs",
            // Other settings, whatever they look like.
            "auto_sync", "sync", "synced", "subdelay", "sub_lang", "pos:$film", "pos:$gone", "player_beat",
        )
        val stale = listOf(
            // A file no longer kept: the one delay an older version saved, the delays by position, the track picked, its
            // fine-tuning, the track turned down, and a delay set by hand; also for a film whose link has a | in it.
            "sync|12-gone.srt|$film", "sync|12-gone.srt|$film|at", "sync|12-gone.srt|$film|track", "sync|12-gone.srt|$film|trim",
            "sync|12-gone.srt|$film|drop", "sync|12-gone.srt|$gone", "sync|12-gone.srt|$gone|at", "subdelay|12-gone.srt|$gone",
            // A name that only starts like a kept one, and one that was never a file.
            "sync|11-kept.srt.bak|$film|at", "sync|null|$film",
            // The "Sync with" choice of a film with no subtitles left.
            "syncwith|film:$gone",
        )
        val all = (kept + stale).shuffled(java.util.Random(1))
        assertEquals(stale.toSet(), Prefs.staleSubtitleTimes(all, setOf("11-kept.srt"), setOf(film)).toSet())
        // Nothing kept at all: every subtitle's goes, and still nothing else.
        assertEquals(
            (stale + kept.filter { it.startsWith("sync|") || it.startsWith("subdelay|") || it.startsWith("syncwith|film:") }).toSet(),
            Prefs.staleSubtitleTimes(all, emptySet(), emptySet()).toSet(),
        )
        // Nothing gone: nothing to sweep.
        assertEquals(emptyList<String>(), Prefs.staleSubtitleTimes(kept, setOf("11-kept.srt"), setOf(film)))
    }
}
