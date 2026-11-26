package app.fireplayer.vod.sources

import app.fireplayer.vod.EpisodeRef
import app.fireplayer.vod.Opened
import app.fireplayer.vod.PlaybackSource
import app.fireplayer.vod.Readiness
import java.util.Locale

/**
 * The one log line a film's or episode's start writes (tag FirePlayer), so the user can see on the TV where it streams
 * from: the add-on, whether it was a cached source, and the stream's host cut to its site ("real-debrid.com",
 * "tb-cdn.st", with the service's name when known). Never the link, a path, a query or a key: only [SecretUrl.host],
 * and of that only the last two labels (a host's first labels can carry a user name, "name.elfhosted.com"); keys only
 * as fingerprints ([fp]). Pure.
 */
object SourceLog {

    /** The player's own tag: the start's line and the engine's are read together. */
    const val TAG = "FirePlayer"

    fun line(ready: Opened.Ready): String {
        val cached = when (ready.offer.readiness) {
            Readiness.PROBABLY_READY -> "cached"
            Readiness.NOT_READY -> "not cached (the debrid service fetches it)"
            Readiness.UNKNOWN -> "cache not stated"
        }
        return "Source: ${ready.offer.addon.label} · $cached · streams from ${site(ready.link.host)}"
    }

    /** "abc.download.real-debrid.com" → "real-debrid.com (Real-Debrid)"; an address in numbers → "an IP address". */
    fun site(host: String?): String {
        val h = host?.lowercase(Locale.ROOT)?.trim('.', ' ')?.takeIf { it.isNotEmpty() } ?: return "an unknown host"
        if (IPV4.matches(h) || ':' in h || h.startsWith("[")) return "an IP address"
        val labels = h.split('.').filter { it.isNotEmpty() }
        val keep = if (labels.size >= 3 && labels[labels.size - 2] in SECOND_LEVEL && labels.last().length == 2) 3 else 2
        val site = labels.takeLast(keep).joinToString(".")
        val name = SERVICES.entries.firstOrNull { (key, _) -> site == key }?.value
            ?: if ("stremthru" in h) "StremThru" else null
        return if (name != null) "$site ($name)" else site
    }

    /**
     * An 8-hex fingerprint of [key] (a record's or a row's key) for the log: the same key always reads the same, and the
     * key itself (its info hash names the torrent) is never written. "none" for none.
     */
    fun fp(key: String?): String = key?.let { String.format(Locale.ROOT, "%08x", it.hashCode()) } ?: "none"

    /**
     * A rank asked for by a play's figures ([DefaultSourcesRepository.rerank]), [episode]'s: one line for each row of
     * [ranked] holding a file of no index (MediaFusion's season packs), at most [MAX_RANK_LINES]: the keys its record was
     * looked for under ([app.fireplayer.vod.ReleaseKey.measuredKey]) and the one [found], as fingerprints ([fp]).
     */
    fun rankLines(ranked: List<PlaybackSource>, episode: EpisodeRef, found: (String) -> Boolean): List<String> =
        ranked.asSequence().mapNotNull { s ->
            val files = (listOfNotNull(s.release) + s.offers.mapNotNull { it.release }).distinct()
            if (files.none { it.fileIdx == null }) return@mapNotNull null
            val tried = files.map { it.measuredKey(episode) }.distinct()
            "rank: row ${fp(s.key)} measured=${fp(tried.firstOrNull(found))} tried=${tried.joinToString(",") { fp(it) }}"
        }.take(MAX_RANK_LINES).toList()

    private const val MAX_RANK_LINES = 12

    private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")

    /** "co.uk"-style endings: the site is the last three labels. */
    private val SECOND_LEVEL = setOf("co", "com", "net", "org", "ac", "gov")

    /** Sites the debrid services ([Debrids.SITES]) and the add-ons' hosts stream from. */
    private val SERVICES = Debrids.SITES + mapOf(
        "elfhosted.com" to "ElfHosted",
        "strem.fun" to "Torrentio",
    )
}
