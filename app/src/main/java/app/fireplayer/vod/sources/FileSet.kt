package app.fireplayer.vod.sources

import app.fireplayer.vod.Offer
import app.fireplayer.vod.PlaybackSource
import app.fireplayer.vod.ReleaseKey

/**
 * Files known by what they are, not by their row: what a play played or tried ([DefaultSourceSession]), or the copy the
 * Sources window names as playing or Best ([app.fireplayer.vod.play.SourcesModel]). Rows merge, part and change keys as
 * answers come and plays measure files, and a merged row keeps one offer per add-on, so a row holds one of these files
 * when any of its offers opens one of them ([ReleaseKey.storeKey]); for an [episode], when one of its offers is of one
 * of their torrents, one side without the file's index (MediaFusion's season packs), and what the two say of the file
 * agrees as it does for one row ([SourceMerge.samePackFile]: their sizes to the byte, else their file names; the same
 * info hash alone is not enough); or when its size is one of theirs to the byte ([exactSize]), unless it is told apart
 * from one of them in its own torrent. [sizeNow]: a file's size to the byte as the list has it now (a play measured it
 * since it was added: the two may have turned out to be different files). Not thread-safe; a few entries a play.
 */
class FileSet(private val episode: Boolean, private val sizeNow: (ReleaseKey) -> Long? = { null }) {
    private val keys = HashSet<String>()
    private val sizes = HashSet<Long>()
    /** An episode's files by info hash, with what tells them apart in their torrent. */
    private val packs = HashMap<String, MutableList<File>>()

    /**
     * A file of an episode's torrent: its row's height, its own size to the byte, its name ([nameOf]) and whether that is
     * surely the file's own ([SourceMerge.namesFile]).
     */
    private class File(val release: ReleaseKey, val height: Int?, val size: Long?, val name: String?, val own: Boolean)

    /**
     * Adds every file [s]'s row holds (one file by what its offers say, as it was merged), and its size to the byte when
     * known.
     */
    fun add(s: PlaybackSource): FileSet {
        val own = s.release
        own?.let { add(it, s, offerOf(s, it)) }
        for (o in s.offers) o.release?.let { if (it != own) add(it, s, o) }
        exactSize(s)?.let { sizes += it }
        return this
    }

    /**
     * Adds the file [offer] opens in [s] alone: the one tried or played. Its row's other offers were one file with it by
     * what they said then; a play may measure them apart since. Every file of [s] without [offer].
     */
    fun add(s: PlaybackSource, offer: Offer?): FileSet {
        if (offer == null) return add(s)
        val r = offer.release ?: s.release ?: return this
        add(r, s, offer)
        sizeOf(s, offer)?.let { sizes += it }
        return this
    }

    private fun add(r: ReleaseKey, s: PlaybackSource, o: Offer?) {
        keys += r.storeKey
        if (episode) packs.getOrPut(r.infoHash) { ArrayList(2) } += File(r, s.facts.height, sizeOf(s, o), nameOf(o), ownName(o, r))
    }

    /** Whether [s] holds one of these files (any of its offers, or its size to the byte). */
    fun holds(s: PlaybackSource): Boolean {
        if (keys.isEmpty() && sizes.isEmpty()) return false
        var apart = false
        // One of [s]'s files: true when it is one of these; [apart] when it is told apart from one of them.
        fun check(r: ReleaseKey, o: Offer?): Boolean {
            if (r.storeKey in keys) return true
            if (!episode) return false
            val seen = packs[r.infoHash] ?: return false
            // Only for a row of the same torrent as one of these: a few a list.
            val size = sizeOf(s, o)
            val name = nameOf(o)
            val own = ownName(o, r)
            for (f in seen) {
                val same = SourceMerge.samePackFile(
                    f.release.fileIdx, r.fileIdx, f.height, s.facts.height, sizeNow(f.release) ?: f.size, size, f.name, name,
                    f.own && own,
                )
                if (same == true) return true
                if (same == false) apart = true
            }
            return false
        }
        val own = s.release
        if (own != null && check(own, offerOf(s, own))) return true
        for (o in s.offers) {
            val r = o.release ?: continue
            if (r != own && check(r, o)) return true
        }
        if (apart) return false
        return exactSize(s)?.let { it in sizes } == true
    }

    companion object {
        /** [s]'s file alone. */
        fun of(s: PlaybackSource, episode: Boolean): FileSet = FileSet(episode).add(s)

        /** [s]'s size to the byte: as a play here measured it, else an add-on's exact one; null when only rounded. */
        fun exactSize(s: PlaybackSource): Long? =
            s.measured?.spec?.sizeBytes?.takeIf { it > 0 } ?: s.facts.sizeBytes?.takeIf { it > 0 && s.facts.sizeExact }

        /**
         * The size to the byte of the file [o] opens in [s]: its own ([Offer.size]); [s]'s when it's [s]'s only offer
         * (a row of one file from one add-on); null otherwise.
         */
        fun sizeOf(s: PlaybackSource, o: Offer?): Long? =
            o?.size?.takeIf { it > 0 } ?: exactSize(s)?.takeIf { s.offers.size <= 1 }

        /** The offer of [s] that opens [r]; null when none does (a ×N row keeps one offer per add-on). */
        private fun offerOf(s: PlaybackSource, r: ReleaseKey): Offer? = s.offers.firstOrNull { (it.release ?: s.release) == r }

        /** The name of the file [o] opens, normalised ([Words.sameName]); null when the add-on gave none. */
        private fun nameOf(o: Offer?): String? = o?.filename?.let { Words.sameName(it) }

        /** Whether [o]'s name is surely the file's own ([r]'s; [SourceMerge.namesFile]), not maybe its torrent's. */
        private fun ownName(o: Offer?, r: ReleaseKey): Boolean = SourceMerge.namesFile(o?.filename, r)
    }
}
