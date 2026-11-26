package app.fireplayer.vod.sources

import app.fireplayer.vod.LastRelease
import app.fireplayer.vod.Offer
import app.fireplayer.vod.PlaybackSource

/**
 * The copy a film or episode was left on ([LastRelease]) as the rows of a list offer it. A release: any add-on's offer of
 * that file ([LastRelease.openedBy]). A copy of no release known (MediaFusion's of no info hash, by its [CopyKey]): the
 * offer of that very key, and the same file from another add-on, as rows merge ([SourceMerge]): an offer of its size to
 * the byte as a play here measured it (Torz's of MediaFusion's file), and an offer of the release such an offer opens
 * (Torrentio's of Torz's file, in their row). So when its own add-on fails, is slow or lists it not cached, a resume still
 * plays that file from another add-on ([DefaultSourceSession.findPreferred]), and the title page's Last used names the
 * row it plays ([DefaultSourcesRepository]). Pure.
 */
internal object CopyLeft {

    /**
     * The size to the byte of the file of [p], a copy of no release known for the film or episode [streamId] (its key
     * names its own: another's file is never this one's), as a play here measured it ([measured]: "quality_seen" under
     * its key); null otherwise (a release, another film's or episode's copy, or never measured): only its own key then
     * finds it.
     */
    fun bytes(p: LastRelease?, streamId: String, measured: (String) -> Long?): Long? {
        if (p == null || p.release != null) return null
        val c = p.copyKey?.takeIf { it.endsWith("|$streamId") } ?: return null
        return measured(c)?.takeIf { it > 0 }
    }

    /**
     * Whether [o], an offer of [s], is another add-on's offer of [p]'s file, [bytes] long to the byte: of that size itself
     * ([FileSet.sizeOf]), or of the release such an offer of [s] opens. Not [p]'s own copy: [LastRelease.openedBy] says
     * that.
     */
    fun sameFile(p: LastRelease, s: PlaybackSource, o: Offer, bytes: Long): Boolean {
        if (p.copyKey == null || bytes <= 0) return false
        if (sized(p, s, o, bytes)) return true
        val r = o.release ?: return false
        return s.offers.any { it !== o && it.release == r && sized(p, s, it, bytes) }
    }

    /** [o] is another add-on's than [p]'s, of [bytes] to the byte: as rows merge, one add-on's copies of a size aren't one. */
    private fun sized(p: LastRelease, s: PlaybackSource, o: Offer, bytes: Long): Boolean =
        o.addon != p.addon && FileSet.sizeOf(s, o) == bytes
}
