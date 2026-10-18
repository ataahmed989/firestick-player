package app.fireplayer.ui

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.TextureView
import android.view.View
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout

/**
 * The picture above the time bar while skipping: a still from where the jump will land.
 *
 * A second, video-only player seeks to the keyframe nearest each new target and shows it. It starts
 * on the first press and is released a few seconds after the last, so its connection to the server
 * isn't kept open. If it can't play the video (an old server, a busy decoder), previews quietly stop
 * for that video and skipping works as before.
 */
@OptIn(UnstableApi::class)
class SeekPreview(
    private val card: View,
    private val frame: AspectRatioFrameLayout,
    private val texture: TextureView,
    private val time: TextView,
    private val format: (Long) -> String,
    private val create: () -> ExoPlayer,
) : Player.Listener {

    private val handler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var itemKey: String? = null
    private var item: MediaItem? = null
    private var requestedMs = 0L
    private var jumpAt = 0L
    // The card is on screen with a picture from this round of skipping.
    private var shown = false
    private var failedKey: String? = null
    private var active = false

    init {
        // Rounded corners clip the picture too, and the card stays drawn (just transparent) so the
        // TextureView keeps its surface ready.
        card.clipToOutline = true
        card.alpha = 0f
    }

    /**
     * Shows (or moves) the preview for a new jump target in the video [key]. [jumpAt] is when the
     * player will jump there (SystemClock.uptimeMillis); a picture that arrives just before that isn't shown.
     */
    fun update(key: String, mediaItem: MediaItem, targetMs: Long, jumpAt: Long) {
        if (key == failedKey) return
        if (key != itemKey) {
            release()
            itemKey = key
            item = mediaItem
        }
        active = true
        requestedMs = targetMs
        this.jumpAt = jumpAt
        handler.removeCallbacks(releaseRunnable)
        handler.removeCallbacks(seekRunnable)
        // Quick presses add up; only the target where they settle is fetched.
        handler.postDelayed(seekRunnable, if (player == null) 0L else 140L)
        time.text = format(targetMs)
        // The picture on show is for an earlier target: soften it until the new one arrives.
        // One left from an earlier round of skipping stays hidden until a fresh one is ready.
        if (shown) card.animate().alpha(0.82f).setDuration(120).start()
    }

    /** Hides the preview; the player is let go a little later in case skipping resumes. */
    fun hide() {
        if (!active) return
        active = false
        shown = false
        handler.removeCallbacks(seekRunnable)
        handler.removeCallbacks(revealRunnable)
        card.animate().cancel()
        card.animate().alpha(0f).setDuration(140).start()
        handler.removeCallbacks(releaseRunnable)
        handler.postDelayed(releaseRunnable, 8_000)
    }

    fun release() {
        handler.removeCallbacks(seekRunnable)
        handler.removeCallbacks(releaseRunnable)
        handler.removeCallbacks(revealRunnable)
        player?.let {
            it.removeListener(this)
            it.release()
        }
        player = null
        shown = false
        card.animate().cancel()
        card.alpha = 0f
    }

    /** Puts the card's centre over [centerX] and its bottom edge at [bottomY], both in the parent's coordinates. */
    fun place(centerX: Float, bottomY: Float, minLeft: Float, maxRight: Float) {
        val width = card.width.toFloat()
        val left = (centerX - width / 2f).coerceIn(minLeft, (maxRight - width).coerceAtLeast(minLeft))
        card.translationX = left - card.left
        card.translationY = bottomY - card.height - card.top
    }

    private val seekRunnable = Runnable {
        val current = player
        val targetMs = requestedMs
        if (current == null) {
            val mediaItem = item ?: return@Runnable
            val created = runCatching { create() }.getOrNull() ?: return@Runnable
            player = created
            created.addListener(this)
            created.setVideoTextureView(texture)
            created.setMediaItem(mediaItem, targetMs)
            created.prepare()
        } else {
            current.seekTo(targetMs)
        }
    }

    private val releaseRunnable = Runnable { release() }

    // The first frame after a seek is drawn just after the player says it's ready.
    private val revealRunnable = Runnable { reveal() }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_READY) {
            handler.removeCallbacks(revealRunnable)
            handler.postDelayed(revealRunnable, 60)
        }
    }

    override fun onRenderedFirstFrame() {
        handler.removeCallbacks(revealRunnable)
        reveal()
    }

    override fun onVideoSizeChanged(videoSize: VideoSize) {
        if (videoSize.width > 0 && videoSize.height > 0) {
            frame.setAspectRatio(videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height)
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        // This video can't be previewed; leave skipping exactly as it was.
        failedKey = itemKey
        hide()
        release()
    }

    private fun reveal() {
        if (!active || player == null) return
        // Popping up only to vanish with the jump a moment later would just flicker.
        if (!shown && SystemClock.uptimeMillis() > jumpAt - 300) return
        card.animate().cancel()
        if (!shown) {
            shown = true
            card.scaleX = 0.94f
            card.scaleY = 0.94f
            card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160).start()
        } else {
            card.animate().alpha(1f).setDuration(120).start()
        }
    }
}
