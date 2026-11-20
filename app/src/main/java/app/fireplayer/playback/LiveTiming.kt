package app.fireplayer.playback

/**
 * How long a channel takes to show, for the log (never a link or a name: only times and the kind of stream):
 * from the key press to the stream being asked for, to the player having enough to start, and to the first frame.
 * Where the time between went shows too, when it's known: when the stream's tracks were known (its server had
 * answered and the first of it was read) and how long the picture's decoder took to start.
 * Pure: times are passed in (uptime ms).
 */
class LiveTiming {
    private var pressedAt = NONE
    private var openedAt = NONE
    private var readyAt = NONE
    private var tracksAt = NONE
    private var decoderMs = NONE
    private var kind = ""
    private var reused = false

    /** A stream is being opened at [now] for a key pressed at [pressed] (0 or less: not known, counted from [now]). */
    fun opened(now: Long, pressed: Long, kind: String, reusedPlayer: Boolean) {
        openedAt = now
        pressedAt = if (pressed in 1..now) pressed else now
        readyAt = NONE
        tracksAt = NONE
        decoderMs = NONE
        this.kind = kind
        reused = reusedPlayer
    }

    /** The player has enough loaded to start. */
    fun ready(now: Long) {
        if (openedAt != NONE && readyAt == NONE) readyAt = now
    }

    /** The stream's tracks are known (the first time counts): its server answered and the first of it was read. */
    fun tracks(now: Long) {
        if (openedAt != NONE && tracksAt == NONE) tracksAt = now
    }

    /** The picture's decoder started, taking [ms] (the stream's first one counts). */
    fun decoder(ms: Long) {
        if (openedAt != NONE && decoderMs == NONE && ms >= 0) decoderMs = ms
    }

    /** The first frame is on screen: the line to log, once per opened stream (null after that). */
    fun firstFrame(now: Long): String? {
        if (openedAt == NONE) return null
        val total = now - pressedAt
        val toOpen = openedAt - pressedAt
        val toReady = (if (readyAt == NONE) now else readyAt) - pressedAt
        openedAt = NONE
        val where = (if (tracksAt == NONE) "" else "; tracks at ${tracksAt - pressedAt} ms") +
            (if (decoderMs == NONE) "" else "; video decoder $decoderMs ms")
        return "Channel shown in $total ms (key to open $toOpen ms, ready at $toReady ms; $kind; player ${if (reused) "reused" else "new"}$where)"
    }

    /** Nothing is being timed (the stream closed before it showed). */
    fun clear() {
        openedAt = NONE
    }

    companion object {
        private const val NONE = Long.MIN_VALUE

        /** The kind of stream by its address's ending, for the log: never the address itself. */
        fun kindOf(lastPathSegment: String?): String {
            val p = lastPathSegment?.lowercase() ?: return "stream"
            return when {
                p.endsWith(".m3u8") || p.endsWith(".m3u") -> "HLS"
                p.endsWith(".mpd") -> "DASH"
                p.endsWith(".ts") -> "TS"
                else -> "stream"
            }
        }
    }
}
