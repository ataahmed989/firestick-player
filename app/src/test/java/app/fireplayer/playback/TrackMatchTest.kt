package app.fireplayer.playback

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackMatchTest {

    private fun audio(id: String, lang: String, label: String? = null) =
        Format.Builder().setId(id).setSampleMimeType(MimeTypes.AUDIO_AAC).setLanguage(lang).setLabel(label).build()

    private fun cc(channel: Int) =
        Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_CEA608).setAccessibilityChannel(channel).build()

    @Test fun theSecondAudioTrackIsFoundAgain() {
        val before = audio("2", "es", "Spanish")
        val again = listOf(audio("1", "en", "English"), audio("2", "es", "Spanish"))
        assertEquals(1, TrackMatch.best(before, again))
    }

    @Test fun captionsKeepTheirChannel() {
        // CC3 stays CC3, not the first CEA-608 track.
        assertEquals(2, TrackMatch.best(cc(3), listOf(cc(1), cc(2), cc(3))))
    }

    @Test fun anotherLanguageOrKindIsNoMatch() {
        assertEquals(-1, TrackMatch.best(audio("2", "es"), listOf(audio("1", "en"))))
        assertEquals(-1, TrackMatch.best(cc(1), listOf(audio("1", "en"))))
    }

    @Test fun theSameIdWinsOverTheSameName() {
        val before = audio("b", "en", "Main")
        val again = listOf(audio("a", "en", "Main"), audio("b", "en", "Other"))
        assertEquals(1, TrackMatch.best(before, again))
    }
}
