package app.fireplayer.playback

import androidx.media3.common.Format

/**
 * Finds a track picked before among the tracks of the same channel opened again (pure, unit-tested): Live TV buffer
 * reopens the channel with a new player, whose tracks are the same ones under new objects.
 */
object TrackMatch {

    /**
     * Where [wanted] is among [candidates], or -1. The kind of track, its language and (TV captions) its channel must
     * be the same; among those, the same id, then the same name, then the same sound, win.
     */
    fun best(wanted: Format, candidates: List<Format>): Int {
        var best = -1
        var bestScore = -1
        candidates.forEachIndexed { i, f ->
            if (f.sampleMimeType != wanted.sampleMimeType || f.language != wanted.language ||
                f.accessibilityChannel != wanted.accessibilityChannel
            ) return@forEachIndexed
            var score = 0
            if (wanted.id != null && f.id == wanted.id) score += 4
            if (f.label == wanted.label) score += 2
            if (f.channelCount == wanted.channelCount && f.averageBitrate == wanted.averageBitrate) score += 1
            if (score > bestScore) {
                best = i
                bestScore = score
            }
        }
        return best
    }
}
