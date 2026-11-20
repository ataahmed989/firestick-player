package app.fireplayer.vod.sources

import app.fireplayer.vod.Addon
import app.fireplayer.vod.Offer
import app.fireplayer.vod.PlaybackSource
import app.fireplayer.vod.Readiness
import app.fireplayer.vod.SourceFacts
import app.fireplayer.vod.SourceGroup
import kotlin.math.abs
import kotlin.math.max

/**
 * One row per file (r3_sources-organize §4.7): the same info hash + file index, or the same normalised file name with
 * the size within 1 %, is one source; across add-ons it reads "Both add-ons" (any two or more), under several hashes "×N". Facts merge:
 * HDR kinds joined, MediaFusion's exact size, Torrentio's seeders. Offers are tried ready mark first, then Torrentio.
 * Answers are merged in the order they arrived, so a row keeps its key as later answers join it.
 */
object SourceMerge {

    /** Re-uploads within this share of each other's size are the same release. */
    const val SIZE_CLOSE = 0.01

    private class Row(val first: ParsedStream, val key: String) {
        val items = ArrayList<ParsedStream>().also { it.add(first) }
        val releases = LinkedHashSet<String>().also { set -> first.release?.let { set.add(it.storeKey) } }
    }

    /** Merged rows, and the row each release (by [app.fireplayer.vod.ReleaseKey.storeKey]) went into ("×N" copies). */
    class Merged(val sources: List<PlaybackSource>, val rowOf: Map<String, String>)

    /** [answers]: each add-on's streams, in the order the add-ons answered. Unranked: [SourceRank] orders them. */
    fun merge(answers: List<List<ParsedStream>>, runtimeMin: Int?): List<PlaybackSource> = mergeKeyed(answers, runtimeMin).sources

    fun mergeKeyed(answers: List<List<ParsedStream>>, runtimeMin: Int?): Merged {
        val rows = ArrayList<Row>()
        val byRelease = HashMap<String, Row>()
        val byName = HashMap<String, MutableList<Row>>()
        val keys = HashSet<String>()
        for (answer in answers) {
            for (p in answer) {
                val row = p.release?.let { byRelease[it.storeKey] }
                    ?: p.sameName?.let { n -> byName[n]?.firstOrNull { sizeClose(it.first.facts.sizeBytes, p.facts.sizeBytes) } }
                if (row != null) {
                    row.items += p
                    p.release?.let { r ->
                        row.releases += r.storeKey
                        if (!byRelease.containsKey(r.storeKey)) byRelease[r.storeKey] = row
                    }
                    continue
                }
                val base = p.release?.storeKey
                    ?: p.sameName?.let { "n|$it|${p.facts.sizeBytes ?: 0}" }
                    ?: "o|${p.offer.addon.name}|${p.offer.text.hashCode()}"
                var key = base
                var n = 2
                while (!keys.add(key)) key = "$base#${n++}"
                val fresh = Row(p, key)
                rows += fresh
                p.release?.let { byRelease[it.storeKey] = fresh }
                p.sameName?.let { byName.getOrPut(it) { ArrayList() } += fresh }
            }
        }
        val rowOf = HashMap<String, String>()
        for (row in rows) for (r in row.releases) rowOf[r] = row.key
        return Merged(rows.map { build(it, runtimeMin) }, rowOf)
    }

    private fun build(row: Row, runtimeMin: Int?): PlaybackSource {
        val items = row.items
        // One offer per add-on: the first one marked ready, else the first; it carries its own file (×N re-uploads).
        val offers = items.groupBy { it.offer.addon }.map { (_, list) ->
            val item = list.firstOrNull { it.offer.readiness == Readiness.PROBABLY_READY } ?: list.first()
            item.offer.copy(release = item.release ?: item.offer.release)
        }.sortedWith(compareBy<Offer>({ readyOrder(it.readiness) }, { it.addon.ordinal }))
        val readiness = when {
            items.any { it.offer.readiness == Readiness.PROBABLY_READY } -> Readiness.PROBABLY_READY
            items.all { it.offer.readiness == Readiness.NOT_READY } -> Readiness.NOT_READY
            else -> Readiness.UNKNOWN
        }
        val facts = mergeFacts(items, runtimeMin)
        return PlaybackSource(
            key = row.key,
            release = items.firstNotNullOfOrNull { it.release },
            facts = facts,
            offers = offers,
            copies = max(1, row.releases.size),
            readiness = readiness,
            group = groupOf(facts, readiness, items.any { it.cinema }, items.any { it.threeD }),
        )
    }

    private fun readyOrder(r: Readiness) = when (r) {
        Readiness.PROBABLY_READY -> 0
        Readiness.UNKNOWN -> 1
        Readiness.NOT_READY -> 2
    }

    fun groupOf(facts: SourceFacts, readiness: Readiness, cinema: Boolean, threeD: Boolean): SourceGroup {
        val height = facts.height
        return when {
            readiness == Readiness.NOT_READY -> SourceGroup.NOT_ON_RD
            cinema -> SourceGroup.CINEMA
            threeD || height == null -> SourceGroup.OTHER
            height >= 2160 -> SourceGroup.UHD
            height >= 1080 -> SourceGroup.FHD
            else -> SourceGroup.LOWER
        }
    }

    private fun mergeFacts(items: List<ParsedStream>, runtimeMin: Int?): SourceFacts {
        fun <T> first(get: (SourceFacts) -> T?): T? = items.firstNotNullOfOrNull { get(it.facts) }
        val size = items.firstOrNull { it.exactSize && it.facts.sizeBytes != null }?.facts?.sizeBytes ?: first { it.sizeBytes }
        val torrentioSeeders = items.filter { it.offer.addon == Addon.TORRENTIO }.mapNotNull { it.facts.seeders }.maxOrNull()
        return SourceFacts(
            height = first { it.height },
            hdr = items.flatMapTo(LinkedHashSet()) { it.facts.hdr },
            dvProfile = first { it.dvProfile },
            origin = first { it.origin },
            codec = first { it.codec },
            bitDepth = first { it.bitDepth },
            audio = first { it.audio },
            channels = first { it.channels },
            sizeBytes = size,
            avgMbps = StreamParser.avgMbps(size, runtimeMin) ?: first { it.avgMbps },
            group = first { it.group },
            languages = items.flatMap { it.facts.languages }.distinct(),
            seeders = torrentioSeeders ?: items.mapNotNull { it.facts.seeders }.maxOrNull(),
            pack = items.any { it.facts.pack },
        )
    }

    /** Both sizes known and within [SIZE_CLOSE] of the larger. A size not known never merges by name. */
    fun sizeClose(a: Long?, b: Long?): Boolean {
        if (a == null || b == null || a <= 0 || b <= 0) return false
        return abs(a - b) <= max(a, b) * SIZE_CLOSE
    }
}
