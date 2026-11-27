package app.fireplayer.vod.sources

import app.fireplayer.vod.Addon
import app.fireplayer.vod.LookupWhy

/**
 * How many stream lists FirePlayer asks for (design-brief §6.4, r3_prefetch §2.6), over a rolling hour of wall-clock
 * time, kept in a small store so a restart doesn't reset it:
 * - MediaFusion (ElfHosted allows 100 per home connection, refilling 1 a minute): at most [MF_EARLY] early look-ups
 *   (a title page's, or one for a link checked ahead: [LookupWhy.AHEAD]) an hour; no automatic look-up once FirePlayer has sent [MF_ALL] in the hour, counting the user's;
 *   after "busy" (🐢 or HTTP 429) no early look-up for 10 minutes and none at all for 2.
 * - Torrentio and StremThru Torz: at most [TIO_AUTO] automatic look-ups an hour each (a guard against bugs); after
 *   HTTP 429 none for 2 minutes.
 * The user's own requests (Play, Sources) are only held back by a "busy" pause. Main thread.
 */
class Budget(private val store: Store, private val clock: () -> Long) {

    /** Where the times are kept between runs (a preference), as one line of text. */
    interface Store {
        fun read(): String?
        fun write(text: String)
    }

    private class Sent(val addon: Addon, val at: Long, val early: Boolean, val automatic: Boolean)

    private val sent = ArrayList<Sent>()
    private val noEarlyUntil = HashMap<Addon, Long>()
    private val skipUntil = HashMap<Addon, Long>()

    init {
        load()
    }

    /** Whether [addon] may be asked now for [why]. */
    fun mayAsk(addon: Addon, why: LookupWhy): Boolean {
        val now = clock()
        prune(now)
        if (now < (skipUntil[addon] ?: 0L)) return false
        if (!why.automatic) return true
        val early = early(why)
        return when (addon) {
            Addon.MEDIAFUSION -> {
                if (early && now < (noEarlyUntil[addon] ?: 0L)) return false
                if (early && sent.count { it.addon == addon && it.early } >= MF_EARLY) return false
                sent.count { it.addon == addon } < MF_ALL
            }
            Addon.TORRENTIO, Addon.TORZ -> sent.count { it.addon == addon && it.automatic } < TIO_AUTO
        }
    }

    /** Until when (wall clock) [addon] is skipped after it said busy; 0 when it isn't. */
    fun busyUntil(addon: Addon): Long = (skipUntil[addon] ?: 0L).takeIf { it > clock() } ?: 0L

    /** A request to [addon] was sent for [why]. */
    fun asked(addon: Addon, why: LookupWhy) {
        val now = clock()
        prune(now)
        sent += Sent(addon, now, early(why), why.automatic)
        save()
    }

    /** An early look-up: a title page's, or one for a link checked ahead as the viewer rests on what OK plays. */
    private fun early(why: LookupWhy): Boolean = why == LookupWhy.PAGE || why == LookupWhy.PAGE_SOON || why == LookupWhy.AHEAD

    /** [addon] said it is busy (🐢 or HTTP 429). */
    fun busy(addon: Addon) {
        val now = clock()
        skipUntil[addon] = now + SKIP_MS
        if (addon == Addon.MEDIAFUSION) noEarlyUntil[addon] = now + NO_EARLY_MS
        save()
    }

    private fun prune(now: Long) {
        sent.removeAll { now - it.at >= HOUR_MS || it.at > now + HOUR_MS }
    }

    // One line: "b:<addon>:<skip>:<noEarly>;s:<addon>:<at>:<flags>;…". Times only, never a link.
    private fun save() {
        val parts = ArrayList<String>()
        for (a in Addon.entries) {
            val skip = skipUntil[a] ?: 0L
            val early = noEarlyUntil[a] ?: 0L
            if (skip > 0 || early > 0) parts += "b:${a.name}:$skip:$early"
        }
        for (s in sent) parts += "s:${s.addon.name}:${s.at}:${(if (s.early) 1 else 0) + (if (s.automatic) 2 else 0)}"
        runCatching { store.write(parts.joinToString(";")) }
    }

    private fun load() {
        val text = runCatching { store.read() }.getOrNull() ?: return
        for (part in text.split(';')) {
            val f = part.split(':')
            if (f.size != 4) continue
            val addon = Addon.entries.firstOrNull { it.name == f[1] } ?: continue
            val a = f[2].toLongOrNull() ?: continue
            val b = f[3].toLongOrNull() ?: continue
            when (f[0]) {
                "b" -> {
                    skipUntil[addon] = a
                    noEarlyUntil[addon] = b
                }
                "s" -> sent += Sent(addon, a, (b and 1L) != 0L, (b and 2L) != 0L)
            }
        }
        prune(clock())
    }

    companion object {
        const val HOUR_MS = 3600_000L
        const val MF_EARLY = 20
        const val MF_ALL = 40
        const val TIO_AUTO = 60
        const val SKIP_MS = 2 * 60_000L
        const val NO_EARLY_MS = 10 * 60_000L
    }
}
