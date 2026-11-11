package app.fireplayer.player

import kotlin.math.abs

/**
 * Notices a film that should be playing but stands still on one picture, and says what to do about it.
 *
 * With the TV off (its HDMI unplugged), Fire OS's sound output on a Fire TV Stick 4K Plus once stopped taking any sound
 * at all. The film's time comes from its sound, so it stood still on its first picture for over half an hour while the
 * player said it was playing: no error, no spinner, plenty loaded ahead. Pausing and playing again got it going (Fire OS
 * restarted its sound then). Media3 has no check of its own for this: its "stuck" checks are for loading, and its stalled
 * sound check only covers older Android.
 *
 * Here a film that is meant to be playing, with plenty loaded ahead, whose time hasn't moved for [STUCK_MS] is paused and
 * played again ([Step.PAUSE_PLAY]). If that doesn't start it, the next steps follow, [STEP_WAIT_MS] apart: a jump to the
 * same spot, which starts the sound afresh ([Step.JUMP]), a new player ([Step.NEW_PLAYER]), and last a message
 * ([Step.MESSAGE]). A step that worked is remembered until a minute of film has played, so a freeze that comes back
 * soon after gets the next step instead of one that didn't hold.
 *
 * Fed from the main thread five times a second; [now] is elapsedRealtime in the app. No Android parts, so it can be tested.
 */
class FreezeWatch(private val now: () -> Long) {

    /** What to do, in the order they're tried. */
    enum class Step { PAUSE_PLAY, JUMP, NEW_PLAYER, MESSAGE }

    /** How the film is, at one moment. */
    class Sample(
        /** Media3's isPlaying: ready, meant to play, and nothing holding it back. */
        val playing: Boolean,
        /** Loading, meant to play. */
        val waiting: Boolean,
        /** The app holds the film on purpose (a jump being lined up, the pause for a TV switch, a retry, a question, an
         *  error), or something sits in front of it: not watched. */
        val held: Boolean,
        val positionMs: Long,
        val bufferedMs: Long,
        /** 0 or less when not known. */
        val durationMs: Long,
        val live: Boolean,
        val seekable: Boolean,
        /** When the TV was last asked to change refresh rate (0 = never). */
        val switchAtMs: Long,
        /** How long the player's own thread has gone without answering (see [ThreadBeat]). */
        val threadSilentMs: Long,
    )

    sealed class Event {
        /** Do [step]: the film has stood at [positionMs] for [stuckMs] with [aheadMs] loaded ahead. [threadSilent]: the
         *  player's thread stopped answering, so only a new player can help. */
        class Act(val step: Step, val positionMs: Long, val stuckMs: Long, val aheadMs: Long, val threadSilent: Boolean) : Event()

        /** The film plays again, at [positionMs], after [after]; it stood still for [stuckMs] in all. */
        class Recovered(val after: Step, val positionMs: Long, val stuckMs: Long) : Event()
    }

    // 0: nothing done; else the step last taken (its ordinal + 1).
    private var level = 0
    // A step was taken and the film hasn't played since.
    private var awaiting = false
    // When and where the film was last seen to move (or the watch began); UNSET while not watched.
    private var anchorAt = UNSET
    private var anchorPos = 0L
    // Film played since the last step or recovery.
    private var progressMs = 0L
    // When this freeze began, for the logs.
    private var frozeAt = 0L

    /** What to do now, if anything. */
    fun check(s: Sample): Event? {
        // The message is up: nothing more until it's answered.
        if (level == Step.MESSAGE.ordinal + 1) return null
        val t = now()
        val watchable = !s.held && !s.live && s.durationMs > 0 && s.positionMs < s.durationMs - END_GUARD_MS &&
            (s.switchAtMs <= 0 || t - s.switchAtMs >= SWITCH_SOUND_MS)
        val ahead = s.bufferedMs - s.positionMs
        // Pausing and jumping are messages to that same thread, so they can't help.
        if (watchable && (s.playing || s.waiting) && s.threadSilentMs >= THREAD_SILENT_MS) {
            return act(if (level < Step.NEW_PLAYER.ordinal + 1) Step.NEW_PLAYER else Step.MESSAGE, s, t, ahead, silent = true)
        }
        // Any break starts the wait again: time paused, loading, lining up a jump or in a TV switch never adds up.
        if (!watchable || !s.playing || ahead < MIN_AHEAD_MS) {
            anchorAt = UNSET
            return null
        }
        if (anchorAt == UNSET) {
            anchorAt = t
            anchorPos = s.positionMs
            return null
        }
        val moved = s.positionMs - anchorPos
        if (abs(moved) >= MOVED_MS) {
            anchorAt = t
            anchorPos = s.positionMs
            // Backwards without a jump reported isn't film played.
            if (moved > 0) progressMs += moved
            if (awaiting && progressMs >= RECOVERED_MS) {
                awaiting = false
                progressMs = 0L
                return Event.Recovered(Step.entries[level - 1], s.positionMs, t - frozeAt)
            }
            if (!awaiting && level > 0 && progressMs >= HEALTHY_MS) {
                level = 0
                progressMs = 0L
            }
            return null
        }
        if (t - anchorAt < if (awaiting) STEP_WAIT_MS else STUCK_MS) return null
        var step = Step.entries[level]
        if (step == Step.JUMP && !s.seekable) step = Step.NEW_PLAYER
        return act(step, s, t, ahead, silent = false)
    }

    private fun act(step: Step, s: Sample, t: Long, ahead: Long, silent: Boolean): Event.Act {
        // A new freeze (not the next step for the same one) is timed from when the film last moved.
        if (!awaiting) frozeAt = if (silent) t - s.threadSilentMs else anchorAt
        level = step.ordinal + 1
        awaiting = true
        anchorAt = UNSET
        progressMs = 0L
        return Event.Act(step, s.positionMs, t - frozeAt, ahead, silent)
    }

    /** The film's time starts afresh (a jump, another video, new tracks, a new player): the wait begins again. */
    fun jumped() {
        anchorAt = UNSET
    }

    /** Back to the start: the player screen was left, or the freeze message was answered. */
    fun reset() {
        level = 0
        awaiting = false
        anchorAt = UNSET
        progressMs = 0L
    }

    companion object {
        private const val UNSET = Long.MIN_VALUE

        /**
         * Less movement than this is standing still. Sound that isn't taken reports the very same time, while a film
         * playing moves 5 s in 5 s (2.5 s at half speed); movement is measured from where it was last seen to move, so
         * slow but moving playback never adds up.
         */
        const val MOVED_MS = 250L

        /**
         * How long a film meant to be playing may stand still before something is done. The longest it normally does
         * outside a TV switch: sound starting after a jump (well under a second), Bluetooth or passthrough sound setting
         * up (1-2 s), a decoder starting again on a change of track (about 1 s). Twice that and more.
         */
        const val STUCK_MS = 5_000L

        /**
         * Less than this loaded ahead is about to run dry: that's loading, the spinner's business, not a freeze. A frozen
         * film keeps loading, so it soon has more (the one seen had 75 s).
         */
        const val MIN_AHEAD_MS = 3_000L

        /** While a TV switch's sound comes back the film waits on its first picture (see [SwitchTolerantAudioSink]). */
        const val SWITCH_SOUND_MS = SwitchTolerantAudioSink.WINDOW_MS

        /** The last seconds aren't watched: sound and picture can end a few seconds apart, and a pause at the end stops there. */
        const val END_GUARD_MS = 10_000L

        /**
         * How long a step gets to start the film: a call into a stuck sound system can wait until Fire OS restarts it
         * (the film went on at once after that), which takes a few seconds; a jump decodes from the picture before.
         */
        const val STEP_WAIT_MS = 10_000L

        /** A second of film after a step: it really plays again, not a blip. */
        const val RECOVERED_MS = 1_000L

        /** After this much film has played, a freeze starts with the first step again. */
        const val HEALTHY_MS = 60_000L

        /**
         * The player's thread normally answers within a few hundredths of a second, and starting a decoder takes up to
         * about 2 s. Longer than a stuck sound call and the restart after it: only a thread that is truly blocked.
         */
        const val THREAD_SILENT_MS = 12_000L
    }
}
