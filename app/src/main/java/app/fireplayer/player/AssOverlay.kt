package app.fireplayer.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes

/**
 * libass's picture over the film, for styled (ASS/SSA) subtitles with "Keep embedded styling" on: an OpenGL TextureView
 * with its own render thread and GL context, and a full-screen buffer once it draws, which libass wakes every third
 * playback loop while it's on screen. Every film used to get one, styled track or not, so now it's put up only the first
 * time a styled track is chosen ([show], with [attach] doing it), and goes with the player. The rest of libass (reading
 * the tracks, its renderer) is set up with the player as before, so a styled track chosen later shows as it did.
 */
class AssOverlay(private val attach: () -> Unit) {
    /** Whether the overlay is up. */
    var shown = false
        private set

    /** Puts the overlay up, once: a styled track is chosen. */
    fun show() {
        if (shown) return
        shown = true
        attach()
    }

    companion object {
        /** Whether [format], the subtitle track chosen, is one libass draws (ASS/SSA, before or after it's read). */
        fun isStyled(format: Format?): Boolean =
            format != null && (format.sampleMimeType == MimeTypes.TEXT_SSA || format.codecs == MimeTypes.TEXT_SSA)
    }
}
