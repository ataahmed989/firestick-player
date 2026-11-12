package app.fireplayer.player

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * Seasons a provider lists as half-hours where TMDB lists each short story on its own (Phineas and Ferb's first
 * season: 26 half-hours, 47 stories on TMDB). Each of the provider's episodes is one or two of TMDB's, in order:
 * "Rollercoaster / Candace Loses Her Head".
 *
 * Which stories go together comes, best first, from the provider's own names when they hold both ("A / B"), else from
 * a TMDB episode group made for the joined order (Phineas and Ferb's "Disney Channel (Double)"), else TMDB's own
 * order; and which ones stand alone from their lengths (a half-hour special alone, two short ones together), TVmaze's
 * when it has them, as TMDB's are often wrong for these.
 */
object JoinedEpisodes {

    /** One of TMDB's episodes, as far as the pairing needs it: its numbers, name and length in minutes (0: not known). */
    class Story(val season: Int, val number: Int, val name: String, val minutes: Int)

    /** The provider's episodes of a season (in its order) are each these stories; [sure] when nothing was guessed. */
    class Pairing(val parts: List<List<Story>>, val sure: Boolean)

    // A story this long or more fills a half-hour on its own.
    private const val LONG_MIN = 18

    /**
     * Whether the provider's season of [count] episodes (numbered up to [highest], lasting [providerMin] minutes as
     * the middle one goes; 0 when not known) is TMDB's season of [stories] joined in pairs.
     */
    fun looksJoined(count: Int, highest: Int, providerMin: Int, stories: List<Story>, titled: Boolean): Boolean {
        val m = stories.size
        if (count < 2 || m <= count || m > 2 * count) return false
        // Numbered 1 to N: not just some of TMDB's episodes missing.
        if (highest != count) return false
        if (titled) return true
        val short = stories.map { it.minutes }.filter { it in 1 until LONG_MIN }.sorted()
        if (providerMin <= 0 || short.size < m / 2) return false
        return providerMin * 10 >= short[short.size / 2] * 16
    }

    /**
     * The stories each of the provider's episodes holds, by their names ([titles], the episodes' own parts of the
     * provider's names, in order): only when every one names stories of [stories], each used once. Null otherwise.
     */
    fun byTitles(titles: List<String>, stories: List<Story>): List<List<Story>>? {
        if (titles.isEmpty()) return null
        val used = HashSet<Story>()
        fun find(n: String) = stories.firstOrNull { it !in used && ShowGuide.sameName(n, it.name) }
            ?: stories.firstOrNull { it !in used && nearly(n, it.name) }
        var joined = 0
        val parts = titles.map { title ->
            // Both halves' names ("A / B", "A - B", "A & B"), else one story's whole name.
            val halves = SPLITS.asSequence().map { title.split(it, limit = 2).map { p -> p.trim() } }
                .filter { it.size == 2 && it.all { p -> p.isNotEmpty() } }
                .mapNotNull { (a, b) ->
                    val x = find(a) ?: return@mapNotNull null
                    used += x
                    val y = find(b)
                    used -= x
                    if (y != null) listOf(x, y) else null
                }.firstOrNull()
            val got = halves ?: listOfNotNull(find(title.trim())).takeIf { it.isNotEmpty() } ?: return null
            if (got.size == 2) joined++
            used += got
            got
        }
        // Named this way only when some name both halves.
        return parts.takeIf { joined > 0 }
    }

    private val SPLITS = listOf(Regex("""\s*/\s*"""), Regex("""\s+[-–—]\s+"""), Regex("""\s+&\s+"""), Regex("""\s+\+\s+"""), Regex("""\s*;\s*"""))

    /** The same name but for a letter or two ("Bowl-R-Ama Drama", "Bowl-R-Rama Drama"). */
    fun nearly(a: String, b: String): Boolean {
        val x = ShowGuide.simple(a)
        val y = ShowGuide.simple(b)
        if (x.length < 6 || y.length < 6) return false
        return distance(x, y) * 10 <= maxOf(x.length, y.length)
    }

    private fun distance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return prev[b.length]
    }

    /**
     * [order] split into [count] runs of one or two stories, in order: a long story alone and two short ones together
     * as far as their lengths allow. Null when it can't be split so ([order] more than twice [count], or fewer).
     * The second value is how many lengths didn't fit (0: all did).
     */
    fun split(order: List<Story>, count: Int): Pair<List<List<Story>>, Double>? {
        val m = order.size
        if (count <= 0 || m < count || m > 2 * count) return null
        val inf = Double.MAX_VALUE / 4
        // best[i][k]: least misfit splitting order from i on into k runs.
        val best = Array(m + 1) { DoubleArray(count + 1) { inf } }
        val take = Array(m + 1) { IntArray(count + 1) }
        best[m][0] = 0.0
        for (i in m - 1 downTo 0) {
            for (k in 1..count) {
                val alone = best[i + 1][k - 1] + aloneCost(order[i])
                var pick = 1
                var cost = alone
                if (i + 1 < m) {
                    val together = best[i + 2][k - 1] + longCost(order[i]) + longCost(order[i + 1])
                    // Ties go to a pair, the usual case.
                    if (together <= cost) { cost = together; pick = 2 }
                }
                best[i][k] = cost
                take[i][k] = pick
            }
        }
        if (best[0][count] >= inf) return null
        val parts = ArrayList<List<Story>>()
        var i = 0
        var k = count
        while (k > 0) {
            val n = take[i][k]
            parts += order.subList(i, i + n).toList()
            i += n
            k--
        }
        return parts to best[0][count]
    }

    private fun aloneCost(s: Story) = when {
        s.minutes >= LONG_MIN -> 0.0
        s.minutes <= 0 -> 0.5
        else -> 1.0
    }

    private fun longCost(s: Story) = if (s.minutes >= LONG_MIN) 1.0 else 0.0

    /** Words in an episode group's name or notes that say it joins episodes the way a channel aired them. */
    private val JOINED_GROUP = Regex("""(?i)\b(double|doubles|combined|paired|joined|half[- ]?hours?|segments?|22[- ]?min)""")

    fun isJoinedGroup(name: String, description: String) = JOINED_GROUP.containsMatchIn(name) || JOINED_GROUP.containsMatchIn(description)

    /**
     * The order of [stories] as a group part lists them ([group]: TMDB season and episode numbers), when the part holds
     * them all; null when it doesn't (another season, or only some of them).
     */
    fun orderIn(group: List<Pair<Int, Int>>, stories: List<Story>): List<Story>? {
        val byNumbers = stories.associateBy { it.season to it.number }
        val listed = group.mapNotNull { byNumbers[it] }.distinct()
        return listed.takeIf { it.size == stories.size }
    }

    /** The provider's [count] episodes as TMDB's [stories] (TMDB's order), with [groups]' orders when one fits. */
    fun pair(titles: List<String>, count: Int, stories: List<Story>, groups: List<List<Story>>): Pairing? {
        byTitles(titles, stories)?.let { return Pairing(it, sure = true) }
        // A group made for the joined order wins over TMDB's own, even where lengths don't all fit (TMDB's often
        // aren't right); of several, the one whose lengths fit best.
        val fromGroups = groups.mapNotNull { order -> split(order, count) }
        val (parts, misfit) = fromGroups.minByOrNull { it.second } ?: split(stories, count) ?: return null
        return Pairing(parts, sure = misfit == 0.0 && groups.isNotEmpty())
    }

    /**
     * TVmaze's lengths for a show's episodes (no key needed), by the show's IMDb id: season, episode, name and
     * minutes. Empty when TVmaze doesn't know the show. Blocks.
     */
    fun tvmazeStories(imdb: String): List<Story> {
        val show = get("https://api.tvmaze.com/lookup/shows?imdb=$imdb") ?: return emptyList()
        val id = Regex("\"id\"\\s*:\\s*(\\d+)").find(show)?.groupValues?.get(1) ?: return emptyList()
        val text = get("https://api.tvmaze.com/shows/$id/episodes") ?: return emptyList()
        val list = JSONArray(text)
        return (0 until list.length()).mapNotNull { list.optJSONObject(it) }.map { e ->
            Story(e.optInt("season"), e.optInt("number"), e.optString("name"), e.optInt("runtime"))
        }
    }

    /** [stories] with TVmaze's length for each, by name, where it has one. */
    fun withLengths(stories: List<Story>, tvmaze: List<Story>): List<Story> {
        if (tvmaze.isEmpty()) return stories
        return stories.map { s ->
            val other = tvmaze.firstOrNull { it.season == s.season && ShowGuide.sameName(it.name, s.name) }
                ?: tvmaze.firstOrNull { ShowGuide.sameName(it.name, s.name) }
            if (other != null && other.minutes > 0) Story(s.season, s.number, s.name, other.minutes) else s
        }
    }

    private fun get(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("Accept", "application/json")
        return try {
            if (conn.responseCode == 404) null
            else if (conn.responseCode !in 200..299) throw java.io.IOException("TVmaze error ${conn.responseCode}")
            else conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
