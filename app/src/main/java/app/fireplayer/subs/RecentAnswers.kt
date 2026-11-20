package app.fireplayer.subs

/**
 * The last few answers to questions that get asked again within minutes, kept in memory: the same search isn't sent
 * twice. Matching a name asks TMDB the same thing from several places (the film's details, its copies, Skip intro's
 * times, a subtitle search), and each asking is up to four searches.
 *
 * At most [max] answers, each for [keptMs]; the one asked for longest ago goes first. Any thread.
 */
class RecentAnswers<V : Any>(
    private val max: Int,
    private val keptMs: Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Kept<V>(val value: V, val at: Long)

    private val map = object : LinkedHashMap<String, Kept<V>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Kept<V>>?): Boolean = size > max
    }

    /** The answer kept for [question], or null when there's none or it's too old. */
    @Synchronized
    fun get(question: String): V? {
        val kept = map[question] ?: return null
        if (clock() - kept.at >= keptMs) {
            map.remove(question)
            return null
        }
        return kept.value
    }

    @Synchronized
    fun put(question: String, answer: V) {
        map[question] = Kept(answer, clock())
    }

    /** The answer kept for [question], else [ask]'s, which is then kept. A failed ask (it throws) keeps nothing. */
    fun getOrAsk(question: String, ask: () -> V): V = get(question) ?: ask().also { put(question, it) }

    val size: Int @Synchronized get() = map.size
}
