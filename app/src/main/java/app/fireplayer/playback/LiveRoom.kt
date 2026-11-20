package app.fireplayer.playback

/**
 * Memory let go of while Live TV is in front, measured on the user's Stick: 397 MB for the app with a channel
 * selected, 208 MB of it graphics, and the system down to 89 MB of swap, a pressure that itself makes live streams
 * stall. What's out of sight goes: the other tabs' decoded pictures still kept in memory (banners, wide cards and
 * posters: everything but small icons, [isLarge]), the page glass's blurred copies while the picture is
 * full screen, and the graphics Android keeps for windows that are no longer drawn. Nothing on screen goes, so
 * nothing flashes; what went is made again when it's next shown (a picture fades in from the disk cache, a glass
 * window is copied as it opens). Pure: when ([dueMs]) and what ([isLarge]), plus the one log line ([line]).
 */
object LiveRoom {
    /** Full screen this long first, so a quick look at full screen and back costs nothing. */
    const val FULL_MS = 3_000L
    /** The Live TV tab in front this long: someone settled in to watch, not passing through. */
    const val TAB_MS = 60_000L
    /**
     * Pictures in the shared memory cache larger than this go (a poster or card is 200 KB and more in RGB_565, a
     * banner megabytes). The guide's channel logos aren't in that cache (Live TV keeps its own), so none of them go;
     * small icons stay.
     */
    const val LARGE_BYTES = 64 shl 10

    fun dueMs(full: Boolean): Long = if (full) FULL_MS else TAB_MS

    fun isLarge(bytes: Int): Boolean = bytes > LARGE_BYTES

    /** The FirePlayerLive line: what was let go of (counts and sizes only). */
    fun line(full: Boolean, pictures: Int, pictureBytes: Long, glass: Boolean, graphics: Boolean): String = buildString {
        append("Live memory (").append(if (full) "full screen" else "Live TV a while").append("): ")
        append(pictures).append(" pictures out of sight (").append(pictureBytes shr 10).append(" KB)")
        if (glass) append(", page glass copies")
        append(if (graphics) ", unused graphics trimmed" else ", graphics trim not available")
    }
}
