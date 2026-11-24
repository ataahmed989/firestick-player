package app.fireplayer.player

/**
 * What the film's renderer saw of the video playing, for what a play saves ([MediaSpec.of]) and Picture & sound. Set on
 * the playback thread as each decoder starts and as samples go to it ([PacedVideoRenderer]), read on the main thread.
 */
object PlayFacts {
    /** The MIME type of the video decoder playing ("video/dolby-vision", "video/hevc"); null before one starts. */
    @Volatile var decoderMime: String? = null

    /** HDR10+ dynamic metadata was seen in the picture playing (in the file: whether it reaches the TV is another matter). */
    @Volatile var hdr10PlusSeen = false
}
