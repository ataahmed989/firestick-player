package app.fireplayer.vod.sources

import app.fireplayer.vod.Addon
import app.fireplayer.vod.Offer
import app.fireplayer.vod.PlaybackSource
import app.fireplayer.vod.Readiness
import app.fireplayer.vod.ReleaseKey
import app.fireplayer.vod.SourceFacts
import app.fireplayer.vod.SourceGroup
import kotlin.math.abs
import kotlin.math.max

/**
 * One row per file (r3_sources-organize §4.7): the same info hash + file index, the same exact size in bytes from two
 * add-ons (both known exactly: what a play here measured of the file, else an add-on's videoSize), or the same normalised
 * file name with the size within 1 % (the row's best-known size: exact when known), is one source; for an episode, the
 * same info hash too when one add-on gave no file index (a torrent holds one file of an episode: MediaFusion's season
 * packs come without one). Across add-ons it reads "Both add-ons" (any two or more), under several hashes "×N". Facts
 * merge: HDR kinds joined (one add-on's "DV" and the other's "HDR10+"), the size measured here or an add-on's exact one
 * (never a torrent's total: [ParsedStream.torrentSize] has none), Torrentio's seeders. Offers are tried ready mark
 * first, then Torrentio. Answers are merged in the order they arrived, so a row keeps its key as later answers join it.
 * A film's files keep to hash + index: a collection holds several films.
 */
object SourceMerge {

    /** Re-uploads within this share of each other's size are the same release. */
    const val SIZE_CLOSE = 0.01

    private class Row(val first: ParsedStream, val key: String, var exact: Long?) {
        val items = ArrayList<ParsedStream>().also { it.add(first) }
        val releases = LinkedHashSet<String>().also { set -> first.release?.let { set.add(it.storeKey) } }

        /** The size the name rule compares: the file's to the byte once an item knows it ([exact]), else the first stated. */
        val size: Long? get() = exact ?: items.firstNotNullOfOrNull { it.facts.sizeBytes }
    }

    /** Merged rows, and the row each release (by [app.fireplayer.vod.ReleaseKey.storeKey]) went into ("×N" copies). */
    class Merged(val sources: List<PlaybackSource>, val rowOf: Map<String, String>)

    /**
     * [answers]: each add-on's streams, in the order the add-ons answered. [episode]: they're an episode's (the hash
     * rule above). [measuredSize]: a file's exact size in bytes as a play here measured it, by release; null when it
     * never played. Unranked: [SourceRank] orders them.
     */
    fun merge(
        answers: List<List<ParsedStream>>, runtimeMin: Int?, episode: Boolean = false, measuredSize: (ReleaseKey) -> Long? = { null },
    ): List<PlaybackSource> = mergeKeyed(answers, runtimeMin, episode, measuredSize).sources

    fun mergeKeyed(
        answers: List<List<ParsedStream>>, runtimeMin: Int?, episode: Boolean = false, measuredSize: (ReleaseKey) -> Long? = { null },
    ): Merged {
        val rows = ArrayList<Row>()
        val byRelease = HashMap<String, Row>()
        // An episode's: the row holding each info hash, and the row holding one without a file index.
        val byHash = HashMap<String, Row>()
        val byLooseHash = HashMap<String, Row>()
        val byName = HashMap<String, MutableList<Row>>()
        val bySize = HashMap<Long, Row>()
        val keys = HashSet<String>()
        fun index(row: Row, p: ParsedStream, exact: Long?) {
            p.release?.let { r ->
                row.releases += r.storeKey
                byRelease.getOrPut(r.storeKey) { row }
                if (episode) {
                    byHash.getOrPut(r.infoHash) { row }
                    if (r.fileIdx == null) byLooseHash.getOrPut(r.infoHash) { row }
                }
            }
            exact?.let { bySize.getOrPut(it) { row } }
        }
        for (answer in answers) {
            for (p in answer) {
                val exact = exactSize(p, measuredSize)
                val r = p.release
                val row = r?.let { byRelease[it.storeKey] }
                    // The same torrent, one side without the file's index: an episode's one file in it.
                    ?: r?.takeIf { episode }?.let { if (it.fileIdx == null) byHash[it.infoHash] else byLooseHash[it.infoHash] }
                    ?: exact?.let { bySize[it] }?.takeIf { sameFile(it, p) }
                    ?: p.sameName?.let { n -> byName[n]?.firstOrNull { sizeClose(it.size, exact ?: p.facts.sizeBytes) } }
                if (row != null) {
                    row.items += p
                    if (row.exact == null) row.exact = exact
                    index(row, p, exact)
                    continue
                }
                val base = p.release?.storeKey
                    ?: p.sameName?.let { "n|$it|${p.facts.sizeBytes ?: 0}" }
                    ?: "o|${p.offer.addon.name}|${p.offer.text.hashCode()}"
                var key = base
                var n = 2
                while (!keys.add(key)) key = "$base#${n++}"
                val fresh = Row(p, key, exact)
                rows += fresh
                index(fresh, p, exact)
                p.sameName?.let { byName.getOrPut(it) { ArrayList() } += fresh }
            }
        }
        val rowOf = HashMap<String, String>()
        for (row in rows) for (r in row.releases) rowOf[r] = row.key
        return Merged(rows.map { build(it, runtimeMin, measuredSize) }, rowOf)
    }

    /**
     * Whether [p], of the same exact size as [row], is the same file from another add-on: not one more from an add-on
     * already in the row (its own copies of one size are told apart by name, as before), and no height that differs.
     */
    private fun sameFile(row: Row, p: ParsedStream): Boolean = row.items.none { item ->
        item.offer.addon == p.offer.addon || (item.facts.height != null && p.facts.height != null && item.facts.height != p.facts.height)
    }

    /**
     * A file's exact size: what a play here measured of its release first (to the byte, whatever an add-on says), else
     * the add-on's own (videoSize); null otherwise.
     */
    private fun exactSize(p: ParsedStream, measuredSize: (ReleaseKey) -> Long?): Long? {
        p.release?.let { measuredSize(it) }?.takeIf { it > 0 }?.let { return it }
        return if (p.exactSize) p.facts.sizeBytes?.takeIf { it > 0 } else null
    }

    private fun build(row: Row, runtimeMin: Int?, measuredSize: (ReleaseKey) -> Long?): PlaybackSource {
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
        val facts = mergeFacts(items, runtimeMin, measuredSize)
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
            threeD -> SourceGroup.THREE_D
            height == null -> SourceGroup.OTHER
            height >= 2160 -> SourceGroup.UHD
            height >= 1080 -> SourceGroup.FHD
            else -> SourceGroup.LOWER
        }
    }

    private fun mergeFacts(items: List<ParsedStream>, runtimeMin: Int?, measuredSize: (ReleaseKey) -> Long?): SourceFacts {
        fun <T> first(get: (SourceFacts) -> T?): T? = items.firstNotNullOfOrNull { get(it.facts) }
        // The size measured here, else an add-on's exact one, else the first stated (never a torrent's total: it has none).
        val exact = items.firstNotNullOfOrNull { p -> p.release?.let(measuredSize)?.takeIf { it > 0 } }
            ?: items.firstOrNull { it.exactSize && it.facts.sizeBytes != null }?.facts?.sizeBytes
        val size = exact ?: first { it.sizeBytes }
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
            subs = items.flatMap { it.facts.subs }.distinct(),
            seeders = torrentioSeeders ?: items.mapNotNull { it.facts.seeders }.maxOrNull(),
            pack = items.any { it.facts.pack },
            sizeExact = exact != null,
        )
    }

    /** Both sizes known and within [SIZE_CLOSE] of the larger. A size not known never merges by name. */
    fun sizeClose(a: Long?, b: Long?): Boolean {
        if (a == null || b == null || a <= 0 || b <= 0) return false
        return abs(a - b) <= max(a, b) * SIZE_CLOSE
    }
}
