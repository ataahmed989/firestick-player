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
 * resume points keyed by title and episode), watched past 90 % or into the credits ([countsAsWatched]), and the release
 * last played from (info hash + file index + add-on, never a link). Any thread; listeners are called on change.
 */
class RecordedLibrary(private val library: VodLibrary) : PlaybackLibrary {

    override fun lastRelease(title: Title, episode: EpisodeRef?): LastRelease? {
        val row = library.lastRelease(title.kind, idOf(title, episode)) ?: return null
        val addon = Addon.entries.firstOrNull { it.name == row.addon } ?: return null
        val release = ReleaseKey.of(row.hash, row.fileIdx) ?: return null
        return LastRelease(addon, release, row.at)
    }

    override fun recordRelease(title: Title, episode: EpisodeRef?, release: LastRelease) {
        library.recordRelease(
            ReleaseRow(title.kind, idOf(title, episode), release.addon.name, release.release.infoHash,
                release.release.fileIdx ?: -1, release.at),
        )
    }

    override fun forgetReleases(title: Title) = library.forgetReleases(title.kind, title.id)

    override fun forgetRelease(title: Title, episode: EpisodeRef?) = library.forgetRelease(title.kind, idOf(title, episode))

    override fun recordPlayback(title: Title, episode: EpisodeRef?, positionMs: Long, durationMs: Long, creditsAtMs: Long?) {
        library.recordProgress(title, episode, positionMs, durationMs, watched = countsAsWatched(positionMs, durationMs, creditsAtMs))
    }

    companion object {
        /** The film's IMDb id, or the episode's "tt…:S:E". */
        fun idOf(title: Title, episode: EpisodeRef?): String =
            episode?.let { Episode.idOf(title.id, it.season, it.episode) } ?: title.id
    }
}
