package app.fireplayer.vod.sources

import app.fireplayer.vod.Addon
import app.fireplayer.vod.Episode
import app.fireplayer.vod.EpisodeRef
import app.fireplayer.vod.LastRelease
import app.fireplayer.vod.PlaybackLibrary
import app.fireplayer.vod.ReleaseKey
import app.fireplayer.vod.Title
import app.fireplayer.vod.countsAsWatched
import app.fireplayer.vod.data.ReleaseRow
import app.fireplayer.vod.data.VodLibrary

/**
 * What playback records, into the library on this TV ([VodLibrary], vod.db): where it stopped (Continue Watching and
 * resume points keyed by title and episode), watched by the watched rule ([countsAsWatched]), and the copy
 * last played from (info hash + file index + add-on, never a link). Any thread; listeners are called on change.
 *
 * A copy of no release known (its add-on gave no info hash) is kept by its [CopyKey] in the row's hash column, with file
 * index -1 ([ReleaseRow]): no new column, no migration. A version before this one reads that row through
 * [ReleaseKey.of], which takes only 40 hex digits: it gets no release, and its resume plays the ranking's pick. A row an
 * earlier version kept (a release) reads as it always did.
 */
class RecordedLibrary(private val library: VodLibrary) : PlaybackLibrary {

    override fun lastRelease(title: Title, episode: EpisodeRef?): LastRelease? {
        val row = library.lastRelease(title.kind, idOf(title, episode)) ?: return null
        val addon = Addon.entries.firstOrNull { it.name == row.addon } ?: return null
        if (CopyKey.isCopyKey(row.hash)) {
            return row.hash.takeIf { CopyKey.wellFormed(it) }?.let { LastRelease.ofCopy(addon, it, row.at) }
        }
        val release = ReleaseKey.of(row.hash, row.fileIdx) ?: return null
        return LastRelease(addon, release, row.at)
    }

    override fun recordRelease(title: Title, episode: EpisodeRef?, release: LastRelease) {
        val r = release.release
        val (hash, fileIdx) = when {
            r != null -> r.infoHash to (r.fileIdx ?: -1)
            CopyKey.wellFormed(release.copyKey) -> release.copyKey!! to -1
            else -> return
        }
        library.recordRelease(
            ReleaseRow(title.kind, idOf(title, episode), release.addon.name, hash, fileIdx, release.at),
        )
    }

    override fun forgetReleases(title: Title) = library.forgetReleases(title.kind, title.id)

    override fun forgetRelease(title: Title, episode: EpisodeRef?) = library.forgetRelease(title.kind, idOf(title, episode))

    /**
     * The watched rule's decision ([countsAsWatched]) is made here, with what's known of the file's end, and kept as the
     * record's watched mark: a stop it doesn't count (before known credits, or a scene after them) gets none, so the
     * library holds it open even in the last 3 minutes ([app.fireplayer.vod.Position.held]), after a restart too.
     */
    override fun recordPlayback(
        title: Title, episode: EpisodeRef?, positionMs: Long, durationMs: Long, creditsAtMs: Long?, sceneAtMs: Long?,
    ) {
        val watched = countsAsWatched(positionMs, durationMs, creditsAtMs, sceneAtMs)
        library.recordProgress(title, episode, positionMs, durationMs, watched = watched)
    }

    companion object {
        /** The film's IMDb id, or the episode's "tt…:S:E". */
        fun idOf(title: Title, episode: EpisodeRef?): String =
            episode?.let { Episode.idOf(title.id, it.season, it.episode) } ?: title.id
    }
}
