package app.fireplayer.vod.sources

import app.fireplayer.vod.PlaybackSource
import app.fireplayer.vod.ReleaseKey

/**
 * Files known by what they are, not by their row: what a play played or tried ([DefaultSourceSession]), or the copy the
 * Sources window names as playing or Best ([app.fireplayer.vod.play.SourcesModel]). Rows merge, part and change keys as
 * answers come and plays measure files, and a merged row keeps one offer per add-on, so a row holds one of these files
 * when any of its offers opens one of them ([ReleaseKey.storeKey]); for an [episode], when it has one of their info
 * hashes, one side doesn't know the file's index and no stated heights differ (a torrent holds one file of an episode at
 * a height: MediaFusion's season packs come without the index); or when its size is one of theirs to the byte
 * ([exactSize]). Not thread-safe; a few entries a play.
 */
class FileSet(private val episode: Boolean) {
    private val keys = HashSet<String>()
    /**
     * An episode's info hashes, and those of them added without a file index: each with the heights of the rows that
     * held it ([NO_HEIGHT]: not stated).
     */
    private val hashes = HashMap<String, HashSet<Int>>()
    private val loose = HashMap<String, HashSet<Int>>()
    private val sizes = HashSet<Long>()

    /** Adds [s]'s file: every release its row holds, and its size to the byte when known. */
    fun add(s: PlaybackSource): FileSet {
        val height = s.facts.height ?: NO_HEIGHT
        s.release?.let { add(it, height) }
        for (o in s.offers) o.release?.let { add(it, height) }
        exactSize(s)?.let { sizes += it }
        return this
    }

    private fun add(r: ReleaseKey, height: Int) {
        keys += r.storeKey
        if (episode) {
            hashes.getOrPut(r.infoHash) { HashSet(2) } += height
            if (r.fileIdx == null) loose.getOrPut(r.infoHash) { HashSet(2) } += height
        }
    }

    /** Whether [s] holds one of these files (any of its offers, or its size to the byte). */
    fun holds(s: PlaybackSource): Boolean {
        if (keys.isEmpty() && sizes.isEmpty()) return false
        val height = s.facts.height
        if (s.release?.let { same(it, height) } == true) return true
        for (o in s.offers) if (o.release?.let { same(it, height) } == true) return true
        return exactSize(s)?.let { it in sizes } == true
    }

    private fun same(r: ReleaseKey, height: Int?): Boolean {
        if (r.storeKey in keys) return true
        if (!episode) return false
        fun fits(heights: Set<Int>?) = heights != null && (height == null || NO_HEIGHT in heights || height in heights)
        return fits(loose[r.infoHash]) || (r.fileIdx == null && fits(hashes[r.infoHash]))
    }

    companion object {
        private const val NO_HEIGHT = 0

        /** [s]'s file alone. */
        fun of(s: PlaybackSource, episode: Boolean): FileSet = FileSet(episode).add(s)

        /** [s]'s size to the byte: as a play here measured it, else an add-on's exact one; null when only rounded. */
        fun exactSize(s: PlaybackSource): Long? =
            s.measured?.spec?.sizeBytes?.takeIf { it > 0 } ?: s.facts.sizeBytes?.takeIf { it > 0 && s.facts.sizeExact }
    }
}
