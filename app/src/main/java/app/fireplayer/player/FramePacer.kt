package app.fireplayer.player

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * [PacedVideoRenderer]'s timing, apart from Media3 so it can be tested: each frame goes to the TV's refresh after the one
 * before, the refreshes timed from when frames reached the screen.
 *
 * It also checks those times against the refresh measured. On a TV at the measured rate, frames reach the screen a whole
 * number of refreshes apart. Once, after the TV came on at 59.94 Hz in the middle of a film, frames went on being paced
 * for the 23.976 Hz measured before: they reached the screen 1, 4 or 5 of its refreshes apart instead of 2 and 3 in turn,
 * and some never showed. When a third of the latest frames fall between the refreshes measured, frames go out on
 * Media3's own times instead, until a different refresh, or another mode, is measured, or the screen agrees again: frames
 * sent on Media3's times still reach the screen on its refreshes, so they're judged all the same, and when nearly all of
 * them fit the refresh measured again (the TV was off or reconnecting for a moment, on the same mode) they're paced again.
 *
 * [mode] in the calls below counts the modes measured in turn ([VsyncFix.modes]): a different count means the TV has
 * been on another mode since.
 *
 * All on the playback thread.
 */
class FramePacer {

    private var refreshNs = 0L   // when a frame last reached the screen: one of the TV's refreshes
    private var lastTargetNs = 0L
    private var previousNs = 0L
    private var judgedPeriodNs = 0L
    private var judgedMode = 0
    private var fits = 0
    private var misfits = 0

    /** A refresh length the screen disagreed with: frames go out on Media3's times while it's the one measured. */
    var offGridPeriodNs = 0L
        private set
    private var offGridMode = 0

    private fun offGrid(periodNs: Long, mode: Int) = periodNs == offGridPeriodNs && mode == offGridMode

    /** A new decoder: nothing known of the screen yet. */
    fun reset() {
        refreshNs = 0L
        lastTargetNs = 0L
        previousNs = 0L
        judgedPeriodNs = 0L
        fits = 0
        misfits = 0
        offGridPeriodNs = 0L
    }

    /**
     * A frame reached the screen at [nanoTime]. Returns true when, from now on, the screen disagrees with [periodNs]
     * (the refresh measured while [unaligned]). When it disagreed before and fits again, [offGridPeriodNs] is cleared.
     */
    fun rendered(nanoTime: Long, unaligned: Boolean, periodNs: Long, mode: Int): Boolean {
        refreshNs = nanoTime
        val previous = previousNs
        previousNs = nanoTime
        if (!unaligned || periodNs <= 0L || previous == 0L) return false
        val d = nanoTime - previous
        // Not after a pause or a jump.
        if (d <= 0L || d > JUDGED_GAP * periodNs) return false
        if (periodNs != judgedPeriodNs || mode != judgedMode) {
            judgedPeriodNs = periodNs
            judgedMode = mode
            fits = 0
            misfits = 0
        }
        // How far from a whole number of refreshes apart.
        val off = abs(Math.floorMod(d + periodNs / 2, periodNs) - periodNs / 2)
        if (off > periodNs / 8) misfits++ else fits++
        if (fits + misfits < JUDGED) return false
        val disagrees = misfits >= MISFITS
        val agrees = misfits <= AGREES
        fits = 0
        misfits = 0
        if (offGrid(periodNs, mode)) {
            if (agrees) offGridPeriodNs = 0L
            return false
        }
        if (disagrees) {
            offGridPeriodNs = periodNs
            offGridMode = mode
        }
        return disagrees
    }

    /** When to send out a frame Media3 would send at [releaseTimeNs]. */
    fun paced(releaseTimeNs: Long, unaligned: Boolean, periodNs: Long, mode: Int): Long {
        val refresh = refreshNs
        if (!unaligned || periodNs <= 0L || refresh == 0L || offGrid(periodNs, mode)) {
            lastTargetNs = 0L
            return releaseTimeNs
        }
        val last = lastTargetNs
        var target: Long
        if (last == 0L || abs(releaseTimeNs - last) > 3 * periodNs) {
            // Starting, or after a jump or a pause: the refresh nearest the film's time
            target = refresh + ((releaseTimeNs - refresh).toDouble() / periodNs).roundToLong() * periodNs
        } else {
            // Kept on the TV's refreshes, where the screen says they are
            val off = Math.floorMod(last - refresh, periodNs)
            val onGrid = last - if (off > periodNs / 2) off - periodNs else off
            target = onGrid + periodNs
            val behind = releaseTimeNs - target
            if (behind > SLACK * periodNs) target += periodNs
            else if (behind < -SLACK * periodNs) target -= periodNs
        }
        lastTargetNs = target
        // Half a refresh early: the TV shows it at that refresh, whichever way the timing wobbles
        return target - periodNs / 2
    }

    companion object {
        const val SLACK = 0.6
        /** Frames judged at a time. */
        const val JUDGED = 24
        /** Of those, how many between refreshes means the screen disagrees: a third, so a stray late frame can't. */
        const val MISFITS = 8
        /**
         * After the screen disagreed, at most this many between refreshes means it agrees again: half of [MISFITS], so a
         * screen near the line isn't paced and let go in turn.
         */
        const val AGREES = 4
        /** Frames further apart than this many refreshes (a pause, a jump) aren't judged. */
        const val JUDGED_GAP = 8
    }
}
