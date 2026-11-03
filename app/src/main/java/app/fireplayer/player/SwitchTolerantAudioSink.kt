package app.fireplayer.player

import android.media.AudioTrack
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer

/** When the TV was last asked to change refresh rate (elapsedRealtime), set on the main thread, read on the playback thread. */
class DisplaySwitch {
    @Volatile var atMs = 0L

    fun recent(windowMs: Long): Boolean = atMs > 0 && SystemClock.elapsedRealtime() - atMs < windowMs
}

/**
 * Surround sound passed through to the TV (Dolby, DTS), waiting out a refresh-rate switch instead of failing.
 *
 * While the TV switches refresh rate, its sound output is gone for a second or two (Fire OS reports HDMI audio
 * disconnected, then connected again). A film starting then couldn't set up passthrough, failed, and was started over a
 * few seconds later, downloading again: some 3 to 5 seconds before the picture came, on every film that switches the
 * TV (measured on a Fire TV Stick 4K Plus). Here the setting up is tried again as sound comes in, every so often, until
 * the TV's sound is back; the film waits on its first picture meanwhile. Outside a switch, or if the sound isn't back
 * after [WINDOW_MS], it fails as before.
 */
@OptIn(UnstableApi::class)
class SwitchTolerantAudioSink(sink: AudioSink, private val displaySwitch: DisplaySwitch) : ForwardingAudioSink(sink) {

    private class Pending(val format: Format, val bufferSize: Int, val channels: IntArray?, val error: AudioSink.ConfigurationException)

    // On the playback thread.
    private var pending: Pending? = null
    private var triedAtMs = 0L

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        try {
            super.configure(inputFormat, specifiedBufferSize, outputChannels)
            pending = null
        } catch (e: AudioSink.ConfigurationException) {
            if (!displaySwitch.recent(WINDOW_MS)) throw e
            pending = Pending(inputFormat, specifiedBufferSize, outputChannels, e)
            triedAtMs = SystemClock.elapsedRealtime()
        }
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        val p = pending
        if (p != null) {
            val now = SystemClock.elapsedRealtime()
            if (now - triedAtMs < RETRY_MS) return false
            triedAtMs = now
            try {
                super.configure(p.format, p.bufferSize, p.channels)
                pending = null
            } catch (e: AudioSink.ConfigurationException) {
                if (displaySwitch.recent(WINDOW_MS)) return false
                pending = null
                // The same failure as before, now that the TV has had its time.
                throw AudioSink.InitializationException(
                    AudioTrack.STATE_UNINITIALIZED, p.format.sampleRate, 0, 0, 0, p.format, /* isRecoverable= */ false, e,
                )
            }
        }
        return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
    }

    override fun hasPendingData(): Boolean = pending == null && super.hasPendingData()

    override fun reset() {
        pending = null
        super.reset()
    }

    private companion object {
        // The TV's sound came back 1 to 2.1 s after a switch began (measured); room to spare.
        const val WINDOW_MS = 6_000L
        const val RETRY_MS = 100L
    }
}
