package app.fireplayer.player

/**
 * What the film's renderer and picture sampler saw of the video playing, for what a play saves ([MediaSpec.of]). Set on
 * the playback thread, read on the main thread; the renderer sets [decoderMime] as each decoder starts, the sampler
 * the rest (and clears them for a new film).
 */
object PlayFacts {
    /** The MIME type of the video decoder playing ("video/dolby-vision", "video/hevc"); null before one starts. */
    @Volatile var decoderMime: String? = null

    /** HDR10+ dynamic metadata was seen in the picture playing. */
    @Volatile var hdr10PlusSeen = false

    /** The picture's steady measured aspect ratio ([ImaxAspect.steady]); 0 when not known. */
    @Volatile var aspect = 0f
}
