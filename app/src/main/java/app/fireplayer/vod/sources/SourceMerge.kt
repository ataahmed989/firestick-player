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
 * same info hash too when one add-on gave no file index (MediaFusion's season packs: its link names the season and
 * episode, and MediaFusion picks the file) and what the two say of the file agrees ([samePackFile]: their sizes to the
 * byte, else their file names). The same hash alone never is: in a pack MediaFusion numbers differently, its file for
 * the episode is another than the one the other add-on's index names. Two indexes of one torrent are never one file.
 * Across add-ons it reads "Both add-ons" (any two or more), under several hashes "×N". Facts merge: HDR kinds joined
 * (one add-on's "DV" and the other's "HDR10+"), the size measured here or an add-on's exact one, else a stated one, else
 * an estimate of a season pack's episode ([packSizes]; never a torrent's total as the file's), Torrentio's seeders.
 * Offers are tried ready mark first, then Torrentio; each keeps its own link, file and size ([Offer.size]). Answers are
 * merged in the order they arrived, so a row keeps its key as later answers join it. A film's files keep to hash +
 * index: a collection holds several films.
 */
object SourceMerge {

    /** Re-uploads within this share of each other's size are the same release. */
    const val SIZE_CLOSE = 0.01

    /**
     * [exact]: the file's size to the byte, once an item knows it; [measured]: its size as a play here measured it (looked
     * up once for each item).
     */
    private class Row(val first: ParsedStream, val key: String, var exact: Long?, var measured: Long?) {
        val items = ArrayList<ParsedStream>().also { it.add(first) }
        /** Each item's own size to the byte (measured here, else the add-on's exact one), as [items]. */
        val sizes = ArrayList<Long?>().also { it.add(exact) }
        val releases = LinkedHashSet<String>().also { set -> first.release?.let { set.add(it.storeKey) } }

        /**
         * The size the name rule compares: the file's to the byte once an item knows it ([exact]), else the first stated
         * (never an estimate: [SourceFacts.sizeEstimated]).
         */
        val size: Long? get() = exact ?: items.firstNotNullOfOrNull { statedOf(it) }

        /** The height its items state first, as its facts have it ([mergeFacts]); null when none does. */
        val height: Int? get() = items.firstNotNullOfOrNull { it.facts.height }
    }

    /** Merged rows, and the row each release (by [app.fireplayer.vod.ReleaseKey.storeKey]) went into ("×N" copies). */
    class Merged(val sources: List<PlaybackSource>, val rowOf: Map<String, String>)

    /**
     * [answers]: each add-on's streams, in the order the add-ons answered. [episode]: they're an episode's (the hash
     * rule above). [seasonEpisodes]: how many episodes the episode's season has, from what's stored of the show (asked
     * at most once, only for a season pack's size, [packSizes]); null when not known. [measuredSize]: a file's exact
     * size in bytes as a play here measured it, by release; null when it never played. Unranked: [SourceRank] orders them.
     */
    fun merge(
        answers: List<List<ParsedStream>>, runtimeMin: Int?, episode: Boolean = false, seasonEpisodes: () -> Int? = { null },
        measuredSize: (ReleaseKey) -> Long? = { null },
    ): List<PlaybackSource> = mergeKeyed(answers, runtimeMin, episode, seasonEpisodes, measuredSize).sources

    fun mergeKeyed(
        answers: List<List<ParsedStream>>, runtimeMin: Int?, episode: Boolean = false, seasonEpisodes: () -> Int? = { null },
        measuredSize: (ReleaseKey) -> Long? = { null },
    ): Merged {
        val streams = if (episode) packSizes(answers, runtimeMin, seasonEpisodes) else answers
        val rows = ArrayList<Row>()
        val byRelease = HashMap<String, Row>()
        // An episode's: the rows holding each info hash, and those holding one without a file index.
        val byHash = HashMap<String, MutableList<Row>>()
        val byLooseHash = HashMap<String, MutableList<Row>>()
        val byName = HashMap<String, MutableList<Row>>()
        val bySize = HashMap<Long, Row>()
        // An episode's MediaFusion file of one episode, by its videoSize ([ParsedStream.looseExact]): another add-on's
        // file of that size to the byte is the same file, though MediaFusion's own size isn't counted exact.
        val byLoose = HashMap<Long, Row>()
        val keys = HashSet<String>()
        fun index(row: Row, p: ParsedStream, exact: Long?) {
            p.release?.let { r ->
                row.releases += r.storeKey
                byRelease.getOrPut(r.storeKey) { row }
                if (episode) {
                    byHash.getOrPut(r.infoHash) { ArrayList(1) }.let { if (row !in it) it += row }
                    if (r.fileIdx == null) byLooseHash.getOrPut(r.infoHash) { ArrayList(1) }.let { if (row !in it) it += row }
                }
            }
            exact?.let { bySize.getOrPut(it) { row } }
            looseOf(p, exact)?.let { byLoose.getOrPut(it) { row } }
        }
        for (answer in streams) {
            for (p in answer) {
                // Looked up once a stream: the size a play here measured first (to the byte, whatever an add-on says),
                // else the add-on's own (videoSize).
                val measured = measuredOf(p, episode, measuredSize)
                val exact = measured ?: p.facts.sizeBytes?.takeIf { p.exactSize && it > 0 }
                val r = p.release
                val row = r?.let { byRelease[it.storeKey] }
                    // The same torrent, one side without the file's index: one file only when what they say of it agrees
                    // ([samePack]), never by the hash alone.
                    ?: r?.takeIf { episode }?.let { if (it.fileIdx == null) byHash[it.infoHash] else byLooseHash[it.infoHash] }
                        ?.firstOrNull { samePack(it, p, exact) == true }
                    // Never a row holding another file of its torrent ([apart]).
                    ?: exact?.let { bySize[it] ?: byLoose[it] }?.takeIf { sameFile(it, p) && !apart(it, p, exact, episode) }
                    ?: looseOf(p, exact)?.let { bySize[it] }?.takeIf { sameFile(it, p) && !apart(it, p, null, episode) }
                    ?: p.sameName?.let { n ->
                        byName[n]?.firstOrNull { sizeClose(it.size, exact ?: statedOf(p)) && !apart(it, p, exact, episode) }
                    }
                if (row != null) {
                    row.items += p
                    row.sizes += exact
                    if (row.exact == null) row.exact = exact
                    if (row.measured == null) row.measured = measured
                    index(row, p, exact)
                    continue
                }
                val base = p.release?.storeKey
                    ?: p.sameName?.let { "n|$it|${statedOf(p) ?: 0}" }
                    ?: "o|${p.offer.addon.name}|${p.offer.text.hashCode()}"
                var key = base
                var n = 2
                while (!keys.add(key)) key = "$base#${n++}"
                val fresh = Row(p, key, exact, measured)
                rows += fresh
                index(fresh, p, exact)
                p.sameName?.let { byName.getOrPut(it) { ArrayList() } += fresh }
            }
        }
        val rowOf = HashMap<String, String>()
        for (row in rows) for (r in row.releases) rowOf[r] = row.key
        return Merged(rows.map { build(it, runtimeMin) }, rowOf)
    }

    /**
     * Whether [p], of the same exact size as [row], is the same file from another add-on: not one more from an add-on
     * already in the row (its own copies of one size are told apart by name, as before), and no height that differs.
     */
    private fun sameFile(row: Row, p: ParsedStream): Boolean = row.items.none { item ->
        item.offer.addon == p.offer.addon || heightsDiffer(item.facts.height, p.facts.height)
    }

    /** Both heights stated, and not the same: never one file. */
    private fun heightsDiffer(a: Int?, b: Int?): Boolean = a != null && b != null && a != b

    /**
     * For an episode: whether [p] is [row]'s file by what [row]'s items of [p]'s torrent say ([samePackFile]): true when
     * one says so and none tells them apart, false when one tells them apart (or the heights differ), null when nothing
     * tells. [exact]: [p]'s size to the byte.
     */
    private fun samePack(row: Row, p: ParsedStream, exact: Long?): Boolean? {
        val r = p.release ?: return null
        if (heightsDiffer(row.height, p.facts.height)) return false
        var same: Boolean? = null
        for (i in row.items.indices) {
            val q = row.items[i]
            val qr = q.release ?: continue
            if (qr.infoHash != r.infoHash) continue
            when (samePackFile(qr.fileIdx, r.fileIdx, q.facts.height, p.facts.height, row.sizes[i], exact, fileName(q), fileName(p))) {
                false -> return false
                true -> same = true
                null -> Unit
            }
        }
        return same
    }

    /**
     * [row] holds another file of [p]'s torrent: one under another index, or (an episode's, one side without the index)
     * one what they say tells apart ([samePackFile]). Such a row never takes [p] by size or name either.
     */
    private fun apart(row: Row, p: ParsedStream, exact: Long?, episode: Boolean): Boolean {
        val r = p.release ?: return false
        for (i in row.items.indices) {
            val q = row.items[i]
            val qr = q.release ?: continue
            if (qr.infoHash != r.infoHash || qr == r) continue
            if (qr.fileIdx != null && r.fileIdx != null) return true
            if (episode && samePackFile(qr.fileIdx, r.fileIdx, q.facts.height, p.facts.height, row.sizes[i], exact,
                    fileName(q), fileName(p)) == false) return true
        }
        return false
    }

    /** [p]'s stated size, never an estimate ([SourceFacts.sizeEstimated]): estimates don't tell files apart or join them. */
    private fun statedOf(p: ParsedStream): Long? = p.facts.sizeBytes?.takeUnless { p.facts.sizeEstimated }

    /** MediaFusion's videoSize of [p], an episode's file of one episode ([ParsedStream.looseExact]), while no exact size is known. */
    private fun looseOf(p: ParsedStream, exact: Long?): Long? = p.looseExact?.takeIf { exact == null && !p.torrentSize }

    /** [p]'s file's name as [samePackFile] compares it ([ParsedStream.sameName] when it is the file's, not a torrent's). */
    private fun fileName(p: ParsedStream): String? = p.sameName?.takeIf { p.offer.filename != null }

    /**
     * Whether two offers of one torrent are one file by what they say, for an episode (MediaFusion's season packs come
     * without the file's index): two indexes are one file only when they're the same; heights that differ, never; then
     * their sizes to the byte when both are known (a size a play here measured, else an add-on's exact one): the same or
     * not; else their file names ([Words.sameName]) when both are known. Null when nothing tells: they're not known to
     * be one file, the same info hash alone isn't enough.
     */
    fun samePackFile(
        idxA: Int?, idxB: Int?, heightA: Int?, heightB: Int?, sizeA: Long?, sizeB: Long?, nameA: String?, nameB: String?,
    ): Boolean? {
        if (idxA != null && idxB != null) return idxA == idxB
        if (heightsDiffer(heightA, heightB)) return false
        if (sizeA != null && sizeB != null && sizeA > 0 && sizeB > 0) return sizeA == sizeB
        if (nameA != null && nameB != null) return nameA == nameB
        return null
    }

    /**
     * [p]'s file's size as a play here measured it ([measuredSize]); null when it never played. For a film's file
     * without an index (a collection's films share that key), only when it is the size the add-on gave to the byte (or
     * the add-on gave none): another size was another film's.
     */
    private fun measuredOf(p: ParsedStream, episode: Boolean, measuredSize: (ReleaseKey) -> Long?): Long? {
        val r = p.release ?: return null
        val m = measuredSize(r)?.takeIf { it > 0 } ?: return null
        val stated = p.facts.sizeBytes?.takeIf { p.exactSize && it > 0 }
        return if (!episode && r.fileIdx == null && stated != null && !sizeClose(m, stated)) null else m
    }

    private fun build(row: Row, runtimeMin: Int?): PlaybackSource {
        val items = row.items
        // One offer per add-on: the first one marked ready, else the first; it carries its own file (×N re-uploads), and
        // its own size to the byte. Its link is the add-on's own, as it came.
        val offers = items.indices.groupBy { items[it].offer.addon }.map { (_, list) ->
            val i = list.firstOrNull { items[it].offer.readiness == Readiness.PROBABLY_READY } ?: list.first()
            val item = items[i]
            item.offer.copy(release = item.release ?: item.offer.release, size = row.sizes[i])
        }.sortedWith(compareBy<Offer>({ readyOrder(it.readiness) }, { it.addon.ordinal }))
        val readiness = when {
            items.any { it.offer.readiness == Readiness.PROBABLY_READY } -> Readiness.PROBABLY_READY
            items.all { it.offer.readiness == Readiness.NOT_READY } -> Readiness.NOT_READY
            else -> Readiness.UNKNOWN
        }
        val facts = mergeFacts(items, runtimeMin, row.measured)
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

    /** [measured]: the row's file's size as a play here measured it ([Row.measured]). */
    private fun mergeFacts(items: List<ParsedStream>, runtimeMin: Int?, measured: Long?): SourceFacts {
        fun <T> first(get: (SourceFacts) -> T?): T? = items.firstNotNullOfOrNull { get(it.facts) }
        // The size measured here, else an add-on's exact one, else the first stated, else a season pack's share (never a
        // torrent's total as the file's).
        val exact = measured ?: items.firstOrNull { it.exactSize && it.facts.sizeBytes != null }?.facts?.sizeBytes
        val stated = exact ?: items.firstNotNullOfOrNull { statedOf(it) }
        val estimate = if (stated == null) items.firstOrNull { it.facts.sizeEstimated && it.facts.sizeBytes != null } else null
        val size = stated ?: estimate?.facts?.sizeBytes
        // The pack's own size, while nothing better is known of the file's: its estimate's, else the first one given.
        val pack = if (stated != null) null else estimate ?: items.firstOrNull { it.facts.packBytes != null }
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
            // No size but a torrent's total: the episode's file could be any size ([SourceRank] ranks it so).
            torrentTotal = size == null && items.any { it.torrentSize },
            sizeEstimated = estimate != null,
            packBytes = pack?.facts?.packBytes,
            packEpisodes = estimate?.facts?.packEpisodes,
            packUnclear = pack?.facts?.packUnclear == true,
        )
    }

    /**
     * An episode's MediaFusion streams whose one size may be their torrent's ([ParsedStream.oneSize]), sized by what the
     * add-ons say together. A season pack, on clear evidence only ([packEvidence], else its names: [PackWords]): its
     * size shared by the episodes a range in its name counts, else by its season's ([seasonEpisodes], asked once, for a
     * pack of one season), an estimate ranked as a stated size; with no count, none ("(pack 10.2 GB)"). One episode: its
     * size, stated. Nothing said: no size ("(10.2 GB)"). What a play here measures replaces each ([mergeFacts]).
     */
    private fun packSizes(answers: List<List<ParsedStream>>, runtimeMin: Int?, seasonEpisodes: () -> Int?): List<List<ParsedStream>> {
        if (answers.none { a -> a.any { it.oneSize != null } }) return answers
        val all = answers.flatten()
        var asked = false
        var count: Int? = null
        val season = {
            if (!asked) {
                asked = true
                count = runCatching { seasonEpisodes() }.getOrNull()
            }
            count
        }
        return answers.map { a -> a.map { p -> p.oneSize?.let { packSized(p, it, all, runtimeMin, season) } ?: p } }
    }

    private fun packSized(p: ParsedStream, one: Long, all: List<ParsedStream>, runtimeMin: Int?, season: () -> Int?): ParsedStream {
        val read = p.packRead ?: PackWords.UNSAID
        val kind = when (packEvidence(p, one, all)) {
            true -> PackWords.Kind.PACK
            false -> PackWords.Kind.SINGLE
            null -> read.kind
        }
        val f = p.facts.copy(sizeExact = false, sizeEstimated = false, packBytes = null, packEpisodes = null, packUnclear = false)
        return when (kind) {
            PackWords.Kind.SINGLE -> p.with(f.copy(sizeBytes = one, avgMbps = StreamParser.avgMbps(one, runtimeMin)), torrentSize = false)
            PackWords.Kind.PACK -> {
                // A range's count, else its season's (not for a run of seasons, or a "Complete" naming none).
                val n = (if (read.kind == PackWords.Kind.PACK) read.episodes else null)
                    ?: if (read.kind != PackWords.Kind.PACK || read.oneSeason) season() else null
                val estimate = n?.takeIf { it >= 2 }?.let { one / it }
                p.with(f.copy(sizeBytes = estimate, avgMbps = StreamParser.avgMbps(estimate, runtimeMin), sizeEstimated = estimate != null,
                    packBytes = one, packEpisodes = estimate?.let { n }), torrentSize = true)
            }
            PackWords.Kind.UNSAID -> p.with(f.copy(sizeBytes = null, avgMbps = null, packBytes = one, packUnclear = true), torrentSize = true)
        }
    }

    /**
     * What the other add-ons say of [p]'s torrent, whose one size is [one]: true, a season pack (its own file index past
     * the first; another add-on's file of the same torrent under an index, or of an exact size smaller than [one]; the
     * same file by name smaller to the byte); false, [one] is the file's (another add-on's exact size of the same torrent
     * is [one]); null when they say nothing.
     */
    private fun packEvidence(p: ParsedStream, one: Long, all: List<ParsedStream>): Boolean? {
        val r = p.release
        if ((r?.fileIdx ?: 0) > 0) return true
        val name = fileName(p)
        var single = false
        for (q in all) {
            if (q === p || q.offer.addon == p.offer.addon) continue
            val qr = q.release
            val exact = q.facts.sizeBytes?.takeIf { q.exactSize && it > 0 }
            if (r != null && qr != null && qr.infoHash == r.infoHash) {
                when {
                    exact != null && sizeClose(exact, one) -> single = true
                    exact != null && exact < one -> return true
                    // Torrentio indexes a torrent's files only when it has several; StremThru's index 0 can be a torrent's only one.
                    exact == null && qr.fileIdx != null && (q.offer.addon == Addon.TORRENTIO || qr.fileIdx > 0) -> return true
                }
            } else if (name != null && exact != null && exact < one && !sizeClose(exact, one) && fileName(q) == name) {
                return true
            }
        }
        return if (single) false else null
    }

    /** Both sizes known and within [SIZE_CLOSE] of the larger. A size not known never merges by name. */
    fun sizeClose(a: Long?, b: Long?): Boolean {
        if (a == null || b == null || a <= 0 || b <= 0) return false
        return abs(a - b) <= max(a, b) * SIZE_CLOSE
    }
}
