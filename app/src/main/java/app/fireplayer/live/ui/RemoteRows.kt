package app.fireplayer.live.ui

import android.content.Context
import app.fireplayer.ui.SidePanel

/**
 * Remote › Channel ▲▼ (user): one row whose value is the way channels change; it opens the same small list of choices
 * as "Menu button opens" — D-pad + channel buttons, or Channel buttons only. One row and one saved value
 * ([LivePrefs.channelButtonsOnly]), under the Remote header of Live FirePlayer Settings and the Settings tab's Live TV
 * and Remote & skipping windows, always right under "Menu button opens".
 */
fun channelButtonsRow(panel: SidePanel, context: Context): Unit = with(panel) {
    val prefs = LivePrefs(context)
    val only = prefs.channelButtonsOnly
    choice(
        CHANNEL_ROW,
        listOf("D-pad + channel buttons", "Channel buttons only"),
        if (only) 1 else 0,
        // Short, so the row's value fits beside its label; the list says it in full.
        valueText = if (only) "Channel only" else "D-pad + channel",
    ) { prefs.channelButtonsOnly = it == 1 }
}

/** The row's label: the channel buttons' ▲ and ▼, as on the remote. */
const val CHANNEL_ROW = "Channel ▲▼"
