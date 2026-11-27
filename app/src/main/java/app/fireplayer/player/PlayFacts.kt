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

    /** That HDR10+ is hidden from the decoder: the TV gets the film's HDR10 ([Hdr10PlusWatch]). */
    @Volatile var hdr10PlusKept = false

    /**
     * Why, when it is for a reason of the film's own and not the TV's or the setting's ([Hdr10PlusWatch.HYBRID],
     * [Hdr10PlusWatch.MALFORMED], [Hdr10PlusWatch.GAPS]); 0 otherwise. Kept with the copy ([MediaSpec.hdr10PlusHeld]),
     * which then counts as HDR10 on this TV.
     */
    @Volatile var hdr10PlusHeld = 0
}
