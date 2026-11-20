package app.fireplayer.player

/**
 * Frames lost that Media3 doesn't count, since the player screen came up: added up on the playback thread by
 * [PacedVideoRenderer], read on the main thread ([PlaybackDiag]). Only ever go up.
 *
 * Media3 counts the frames it drops (too late) itself; these are the others:
 * - [shownTwice]: frames the pacer put a refresh later than the one after the frame before ([FramePacer.LATER]), so the
 *   frame before stayed on the screen for two refreshes;
 * - [sameRefresh]: frames the pacer put on the refresh of the frame before ([FramePacer.SAME]), which would only have
 *   taken its place there: skipped instead;
 * - [neverDecoded]: frames missing from the film's times that Media3 didn't drop or skip either ([FrameGaps]).
 */
class FrameCounts {
    @Volatile var shownTwice = 0L
    @Volatile var sameRefresh = 0L
    @Volatile var neverDecoded = 0L
}
