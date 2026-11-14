package app.fireplayer.player

/**
 * Stretches of a film the player went past because the file couldn't be read there (see the MKV reader's recovery),
 * so the viewer can be told when playback reaches one. Added on the loading thread, read on the main thread.
 */
class DamageSkips {

    /** The film at [uri] plays [fromMs] and then goes on at [toMs]; what's between couldn't be read. */
    class Skip(val uri: String?, val fromMs: Long, val toMs: Long) {
        // Told already, until the film is back before this stretch.
        @Volatile var told = false
    }

    private val skips = ArrayList<Skip>()

    /** On the loading thread. The same stretch found again (after a jump back) is kept once. */
    fun add(uri: String?, fromUs: Long, toUs: Long) {
        val fromMs = fromUs / 1000
        val toMs = toUs / 1000
        if (toMs <= fromMs) return
        synchronized(skips) {
            if (skips.any { it.uri == uri && it.toMs == toMs }) return
            if (skips.size >= MAX) skips.removeAt(0)
            skips += Skip(uri, fromMs, toMs)
        }
    }

    /**
     * On the main thread, a few times a second: the stretch the film playing from [uri] has just been taken past at
     * [positionMs], once each time it's played through, or null.
     */
    fun reached(uri: String?, positionMs: Long): Skip? = synchronized(skips) {
        var found: Skip? = null
        for (s in skips) {
            if (s.uri != uri) continue
            if (positionMs < s.fromMs) s.told = false
            else if (!s.told && positionMs >= s.toMs - EARLY_MS && positionMs < s.toMs + LATE_MS) found = s
        }
        found?.also { it.told = true }
    }

    fun clear() = synchronized(skips) { skips.clear() }

    private companion object {
        const val MAX = 50
        // Playback lands on the place found a moment either side of it.
        const val EARLY_MS = 1_500L
        const val LATE_MS = 8_000L
    }
}
