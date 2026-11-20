package app.fireplayer.live.ui

import android.content.Context
import app.fireplayer.playback.PlayerHost
import app.fireplayer.player.LiveBuffer
import app.fireplayer.ui.SidePanel

/**
 * Live TV buffer (user): Small, Medium or Large ([LiveBuffer]). One row and one saved value ([LivePrefs.liveBuffer]),
 * drawn in the Settings tab's Live TV and Playback windows and in Live FirePlayer Settings. The channel playing takes
 * a new size at once, with one short reload ([PlayerHost.bufferChanged]); otherwise the next channel tuned does.
 */
fun liveBufferRow(panel: SidePanel, context: Context): Unit = with(panel) {
    val prefs = LivePrefs(context)
    val sizes = LiveBuffer.entries
    choice("Live TV buffer", sizes.map { it.label }, sizes.indexOf(prefs.liveBuffer)) { i ->
        prefs.liveBuffer = sizes[i]
        PlayerHost.peek()?.bufferChanged()
    }
}
