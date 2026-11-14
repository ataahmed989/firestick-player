package app.fireplayer.player

/**
 * Each track's data rate, counted from the film itself as it loads: the size of every frame or sound block over the
 * stretch of film they cover. Costs a few additions per sample on the loading thread; nothing is decoded or read again.
 * A jump starts a new stretch, and only stretches count, so the gap a jump leaves isn't taken for film.
 * Read on the main thread.
 */
class TrackRates {

    private class Track {
        // Bytes and film time over all stretches so far; the stretch being loaded.
        var bytes = 0L
        var spanUs = 0L
        var runEndUs = Long.MIN_VALUE
    }

    private val tracks = HashMap<String, Track>()

    /** A sample of [size] bytes at [timeUs] for the extractor's track [id]. */
    @Synchronized
    fun add(id: String, timeUs: Long, size: Int) {
        if (timeUs < 0 || size <= 0) return
        val t = tracks.getOrPut(id) { Track() }
        val end = t.runEndUs
        // Frames come in decoding order, a little out of time order with B-frames: only a real jump starts a stretch.
        if (end == Long.MIN_VALUE || timeUs < end - JUMP_BACK_US || timeUs > end + JUMP_AHEAD_US) {
            // The first sample of a stretch has no film time behind it yet: its bytes would only overstate the rate.
            t.runEndUs = timeUs
            return
        }
        t.bytes += size
        if (timeUs > end) {
            t.spanUs += timeUs - end
            t.runEndUs = timeUs
        }
    }

    /**
     * Bits a second for the track the player calls [formatId] ("2", or "0:2" when the film is merged with subtitle
     * files), once at least [minSpanUs] of it has loaded; 0 before.
     */
    @Synchronized
    fun bps(formatId: String?, minSpanUs: Long = MIN_SPAN_US): Long {
        val t = find(formatId) ?: return 0
        if (t.spanUs < minSpanUs) return 0
        return t.bytes * 8 * 1_000_000 / t.spanUs
    }

    /** Seconds of the track's film counted so far. */
    @Synchronized
    fun seconds(formatId: String?): Long = (find(formatId)?.spanUs ?: 0) / 1_000_000

    private fun find(formatId: String?): Track? {
        val id = formatId ?: return null
        tracks[id]?.let { return it }
        return tracks.entries.firstOrNull { (k, _) -> id.endsWith(":$k") }?.value
    }

    companion object {
        /** Scenes differ a lot: less than this much film says too little about the whole. */
        const val MIN_SPAN_US = 45_000_000L
        private const val JUMP_BACK_US = 2_000_000L
        private const val JUMP_AHEAD_US = 3_000_000L
    }
}
