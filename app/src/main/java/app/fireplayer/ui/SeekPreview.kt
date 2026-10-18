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
    /** Called when the card appears (true) and goes away (false), so subtitles can make room. */
    private val onShowing: (Boolean) -> Unit,
) : Player.Listener {

    private val handler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var itemKey: String? = null
    private var item: MediaItem? = null
    private var requestedMs = 0L
    // The target of the last seek sent to the player, which the next picture belongs to.
    private var issuedMs = 0L
    // A seek was sent and its picture isn't ready yet. Only one is sent at a time, so holding a key (a new target
    // every 150 ms) still gets pictures: each new seek goes to wherever skipping has got to by then.
    private var inFlight = false
    // Counts rounds of skipping, so a picture asked for in an earlier round isn't shown in this one.
    private var round = 0
    private var issuedRound = 0
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
        // While a picture is on its way, the next seek waits for it. The picture and time on show stay together.
        if (!inFlight) {
            handler.removeCallbacks(seekRunnable)
            handler.post(seekRunnable)
        }
    }

    /** Hides the preview; the player is let go a little later in case skipping resumes. */
    fun hide() {
        if (!active) return
        active = false
        round++
        // A picture still on its way is let in (and not shown), so the next round's first seek isn't held up.
        handler.removeCallbacks(seekRunnable)
        card.animate().cancel()
        card.animate().alpha(0f).setDuration(140).start()
        setShown(false)
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
        inFlight = false
        card.animate().cancel()
        card.alpha = 0f
        setShown(false)
    }

    /** Puts the card's centre over [centerX] and its bottom edge at [bottomY], both in the parent's coordinates. */
    fun place(centerX: Float, bottomY: Float, minLeft: Float, maxRight: Float) {
        val width = card.width.toFloat()
        val left = (centerX - width / 2f).coerceIn(minLeft, (maxRight - width).coerceAtLeast(minLeft))
        card.translationX = left - card.left
        card.translationY = bottomY - card.height - card.top
    }

    private fun setShown(value: Boolean) {
        if (shown == value) return
        shown = value
        onShowing(value)
    }

    private val seekRunnable = Runnable {
        if (!active || inFlight) return@Runnable
        val target = requestedMs
        val current = player
        if (current != null) {
            current.seekTo(target)
        } else {
            val mediaItem = item ?: return@Runnable
            var created: ExoPlayer? = null
            try {
                created = create()
                created.addListener(this)
                created.setVideoTextureView(texture)
                created.setMediaItem(mediaItem, target)
                created.prepare()
                player = created
            } catch (e: Exception) {
                // A link the player can't open (it would fail for the film too): no previews for it.
                created?.removeListener(this)
                created?.release()
                failedKey = itemKey
                hide()
                return@Runnable
            }
        }
        issuedMs = target
        issuedRound = round
        inFlight = true
    }

    private val releaseRunnable = Runnable { release() }

    // After a seek the player reports ready once the new picture is out; give the view a frame to draw it.
    private val revealRunnable = Runnable { reveal() }

    override fun onPlaybackStateChanged(playbackState: Int) {
        // Ended: the target was past the last picture; there's nothing to show, but the next seek can go.
        if (playbackState == Player.STATE_READY || playbackState == Player.STATE_ENDED) {
            handler.removeCallbacks(revealRunnable)
            handler.postDelayed(revealRunnable, 40)
        }
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
        val p = player ?: return
        val state = p.playbackState
        if (!inFlight || (state != Player.STATE_READY && state != Player.STATE_ENDED)) return
        inFlight = false
        // Skipping has moved on since this seek was sent (or it was for an earlier round): fetch where it is now.
        if (active && (requestedMs != issuedMs || issuedRound != round)) handler.post(seekRunnable)
        // Not a picture asked for in an earlier round of skipping.
        if (!active || issuedRound != round || state != Player.STATE_READY) return
        // Popping up only to vanish with the jump a moment later would just flicker.
        if (!shown && SystemClock.uptimeMillis() > jumpAt - 300) return
        time.text = format(issuedMs)
        card.animate().cancel()
        if (!shown) {
            card.scaleX = 0.94f
            card.scaleY = 0.94f
            card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160).start()
            setShown(true)
        } else {
            card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(120).start()
        }
    }
}
