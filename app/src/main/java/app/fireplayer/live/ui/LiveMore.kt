package app.fireplayer.live.ui

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.format.DateFormat
import android.view.View
import app.fireplayer.PlayerActivity
import app.fireplayer.data.Dimmer
import app.fireplayer.data.Prefs
import app.fireplayer.playback.PlayerHost
import app.fireplayer.ui.DimmerWindow
import app.fireplayer.ui.SidePanel
import java.util.Date

/**
 * The windows the full-screen extra row opens, in the settings window on the right, made like the VOD player's own
 * and keeping the same saved values: Audio (the channel's audio tracks, and the delay), Audio delay, Display size
 * ([Prefs.displayMode], the VOD player's choices), Screen dimmer ([DimmerWindow], the VOD player's customs) and Sleep
 * timer; and Captions, from its button beside Favorite. Also the values shown under those buttons, and the sleep timer
 * itself, which keeps running in the guide.
 *
 * Settings (Live FirePlayer Settings) and Multiview ([Multiview]) are opened by the page itself; External PiP isn't
 * built yet.
 */
class LiveMore(
    private val activity: Activity,
    private val panel: SidePanel,
    private val player: PlayerHost,
    private val controls: LiveControls,
    private val callbacks: Callbacks,
) {

    interface Callbacks {
        /** A window closed: focus goes back to [from], the button it was opened from. */
        fun windowClosed(from: View)
        /** Display size or the dimmer changed: the full-screen picture is laid out and dimmed again. */
        fun pictureChanged()
        /** When the programme on the playing channel now ends (wall clock), or null when the guide doesn't say. */
        fun programmeEnd(): Long?
        /** The sleep timer ran out. */
        fun sleepNow()
    }

    private val prefs = Prefs(activity)
    private val dimmer = Dimmer(activity)
    private val main = Handler(Looper.getMainLooper())

    /** When the sleep timer runs out (elapsedRealtime), 0 for off. */
    private var sleepEndsAt = 0L
    /** The minutes picked in Sleep timer while it runs (ticked there), 0 for none. */
    private var sleepChosenMin = 0
    private var sleepAtProgrammeEnd = false
    /** What was left of the timer when the page stopped; it carries on from there when the page is back. */
    private var sleepRemainingMs = 0L
    /** "End of this programme": when it ends, by the wall clock. */
    private var sleepStopWall = 0L

    /** Opens the window for extra-row button [key] ([LiveControls.AUDIO], …) from [from]. */
    fun open(key: String, from: View) {
        when (key) {
            LiveControls.AUDIO -> openAudio(from)
            LiveControls.AUDIO_DELAY -> openAudioDelay(from)
            LiveControls.DISPLAY -> openDisplay(from)
            LiveControls.DIM -> openDim(from)
            LiveControls.SLEEP -> openSleep(from)
        }
    }

    private fun window(title: String, from: View, render: SidePanel.() -> Unit) {
        panel.open(title, onClose = { callbacks.windowClosed(from) }, render = render)
    }

    /** The values under the extra row's buttons (Audio follows the tracks, in [LiveControls.setStatus]). */
    fun refreshValues() {
        val modes = PlayerActivity.DISPLAY_MODES
        controls.setMoreValue(LiveControls.AUDIO_DELAY, PlayerActivity.formatDelay(player.audioDelayUs))
        controls.setMoreValue(LiveControls.DISPLAY, modes.getOrElse(prefs.displayMode) { modes[0] })
        controls.setMoreValue(LiveControls.DIM, dimmer.label())
        controls.setMoreValue(LiveControls.SLEEP, LiveSleep.label(sleepEndsAt, SystemClock.elapsedRealtime()))
    }

    // ---- Audio ----------------------------------------------------------------------------------------------------

    /** The channel's audio tracks, the one playing ticked, and the delay, as the VOD player's Audio window. */
    private fun openAudio(from: View) = window("Audio", from) {
        val tracks = player.status().audioTracks
        header("On this channel")
        tracks.forEach { track ->
            row(track.label, checked = track.selected) {
                player.selectAudio(track)
                // The player takes a moment to switch; then the tick moves.
                main.postDelayed({ refresh() }, 150)
            }
        }
        if (tracks.size <= 1) message("No other audio tracks.")
        spacer(6)
        delayStepper(this)
    }

    /**
     * Captions: the channel's caption tracks, the one on ticked, then Off, as the VOD player's Subtitles window lists
     * its own. Only opened for a channel that has some.
     */
    fun openCaptions(from: View) = window("Captions", from) {
        val tracks = player.status().captions
        header("On this channel")
        tracks.forEach { track ->
            row(track.label, checked = track.selected) {
                player.selectCaption(track)
                // The player takes a moment to switch; then the tick moves.
                main.postDelayed({ refresh() }, 150)
            }
        }
        row("Off", checked = tracks.none { it.selected }) {
            player.selectCaption(null)
            main.postDelayed({ refresh() }, 150)
        }
    }

    private fun openAudioDelay(from: View) = window("Audio delay", from) {
        delayStepper(this)
        row("Reset") { player.audioDelayUs = 0L; refreshValues(); refresh() }
    }

    /** The VOD player's delay arrows: 50 ms a press, from −2 s to +450 ms. */
    private fun delayStepper(p: SidePanel) = p.stepper("Delay", PlayerActivity.formatDelay(player.audioDelayUs)) { dir ->
        player.audioDelayUs = (player.audioDelayUs + dir * 50_000L)
            .coerceIn(PlayerActivity.MIN_AUDIO_DELAY_US, PlayerActivity.MAX_AUDIO_DELAY_US)
        refreshValues()
    }

    // ---- Display size and dimmer ----------------------------------------------------------------------------------

    /** The VOD player's Display size choices, one saved value for both; the guide's miniature always fits. */
    private fun openDisplay(from: View) = window("Display size", from) {
        val mode = prefs.displayMode
        PlayerActivity.DISPLAY_MODES.forEachIndexed { i, name ->
            row(name, checked = i == mode) {
                prefs.displayMode = i
                callbacks.pictureChanged()
                refreshValues()
                refresh()
            }
        }
    }

    private fun openDim(from: View) = window("Screen dimmer", from) {
        DimmerWindow.render(this, activity, dimmer, applyDim = { callbacks.pictureChanged() }, refreshLabels = { refreshValues() })
    }

    // ---- Sleep timer ----------------------------------------------------------------------------------------------

    /** As the VOD player's, with "End of this programme" (from the guide) in place of "End of this video". */
    private fun openSleep(from: View) = window("Sleep timer", from) {
        row("Off", checked = sleepEndsAt == 0L) { setSleep(LiveSleep.OFF); refresh() }
        LiveSleep.MINUTES.forEach { min ->
            // The time picked is ticked while it runs, as the choice is in every other window of choices.
            row("$min min", checked = sleepEndsAt > 0 && !sleepAtProgrammeEnd && sleepChosenMin == min) { setSleep(min); refresh() }
        }
        val stop = callbacks.programmeEnd()?.takeIf { it > System.currentTimeMillis() }
        row(
            "End of this programme",
            value = stop?.let { DateFormat.getTimeFormat(activity).format(Date(it)) } ?: "Not in the guide",
            checked = sleepAtProgrammeEnd && sleepEndsAt > 0,
            enabled = stop != null,
        ) { setSleep(LiveSleep.END_OF_PROGRAMME); refresh() }
        if (sleepEndsAt > 0) message("Live TV stops in ${LiveSleep.label(sleepEndsAt, SystemClock.elapsedRealtime())}.")
    }

    private fun setSleep(choice: Int) {
        val now = SystemClock.elapsedRealtime()
        sleepEndsAt = LiveSleep.endsAt(choice, now, System.currentTimeMillis(), callbacks.programmeEnd())
        sleepAtProgrammeEnd = choice == LiveSleep.END_OF_PROGRAMME && sleepEndsAt > 0
        sleepChosenMin = if (choice > 0 && sleepEndsAt > 0) choice else 0
        // The programme ends at a time of day, whatever happens meanwhile (the page away, a pause).
        sleepStopWall = if (sleepAtProgrammeEnd) System.currentTimeMillis() + (sleepEndsAt - now) else 0L
        sleepRemainingMs = 0
        main.removeCallbacks(sleepTick)
        if (sleepEndsAt > 0) main.postDelayed(sleepTick, LiveSleep.nextCheckMs(sleepEndsAt, now))
        refreshValues()
    }

    /** Counts the minutes down under the button, and stops live playback when the time is up. */
    private val sleepTick = object : Runnable {
        override fun run() {
            if (sleepEndsAt <= 0L) return
            val now = SystemClock.elapsedRealtime()
            if (now >= sleepEndsAt) {
                sleepEndsAt = 0L
                sleepAtProgrammeEnd = false
                sleepChosenMin = 0
                refreshValues()
                callbacks.sleepNow()
                return
            }
            refreshValues()
            main.postDelayed(this, LiveSleep.nextCheckMs(sleepEndsAt, now))
        }
    }

    /** The page stopped (another app or tab in front): the timer waits, as the VOD player's does while it's away. */
    fun pause() {
        main.removeCallbacks(sleepTick)
        if (sleepEndsAt > 0L) {
            sleepRemainingMs = (sleepEndsAt - SystemClock.elapsedRealtime()).coerceAtLeast(1L)
            sleepEndsAt = 0L
        }
    }

    /**
     * The page is back: a timer that was waiting carries on; "End of this programme" still ends when the programme
     * does (and is over if it already has).
     */
    fun resume() {
        if (sleepRemainingMs > 0L && sleepAtProgrammeEnd) {
            val left = sleepStopWall - System.currentTimeMillis()
            sleepRemainingMs = if (left > 0) left else 0L
            if (left <= 0) { sleepAtProgrammeEnd = false; sleepStopWall = 0L; sleepChosenMin = 0 }
        }
        if (sleepRemainingMs > 0L) {
            val now = SystemClock.elapsedRealtime()
            sleepEndsAt = now + sleepRemainingMs
            sleepRemainingMs = 0L
            main.removeCallbacks(sleepTick)
            main.postDelayed(sleepTick, LiveSleep.nextCheckMs(sleepEndsAt, now))
        }
        refreshValues()
    }

    fun release() {
        main.removeCallbacksAndMessages(null)
        sleepEndsAt = 0L
        sleepRemainingMs = 0L
        sleepChosenMin = 0
    }
}
