package app.fireplayer.live.ui

import android.view.KeyEvent
import app.fireplayer.R
import app.fireplayer.ui.ListEntry
import app.fireplayer.ui.LongList
import app.fireplayer.ui.SidePanel

/**
 * A list the viewer puts in order ([Reorder]) as a window's rows (Reorder playlists, Reorder groups, Settings › Rows):
 * OK picks an entry up (it says "Moving"), Up/Down carry it, OK puts it down; the button beside each moves it to the
 * top. Every move is saved as it happens ([save]). [note] heads the list ([emptyNote] when there's nothing in it). A
 * long list: only the rows on screen exist, and a move only shows the rows it changed again, so it's instant with 350
 * groups.
 */
fun <T> reorderList(
    p: SidePanel,
    r: Reorder<T>,
    note: String,
    emptyNote: String,
    name: (T) -> String,
    key: (T) -> String,
    save: (List<T>) -> Unit,
) {
    p.longList(object : LongList {
        override val size: Int get() = r.items.size + 1

        override fun entry(index: Int): ListEntry =
            if (index == 0) ListEntry("note", if (r.items.isEmpty()) emptyNote else note, kind = ListEntry.Kind.NOTE)
            else r.items[index - 1].let { item ->
                ListEntry("i|" + key(item), name(item), r.value(index - 1), button = R.drawable.ic_move_top, buttonLabel = "Move to top")
            }

        override fun onClick(index: Int) {
            val before = r.press(index - 1)
            if (before >= 0 && before != index - 1) p.changed(before + 1)
            p.changed(index)
        }

        override fun onButton(index: Int) {
            val i = index - 1
            val before = r.toTop(i)
            if (i > 0) save(r.items.toList())
            // Every row down to where it was now says another place; one picked up further down is put down.
            p.changed(1, i + 1)
            if (before > i) p.changed(before + 1)
            p.focusEntry(1)
        }

        override fun onKey(index: Int, keyCode: Int, event: KeyEvent): Boolean {
            val step = when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> -1
                KeyEvent.KEYCODE_DPAD_DOWN -> 1
                else -> return false
            }
            if (r.moving != index - 1) return false
            if (event.action == KeyEvent.ACTION_DOWN) {
                val to = r.carry(step)
                if (to >= 0) {
                    save(r.items.toList())
                    p.changed(index)
                    p.changed(to + 1)
                    // Focus follows the entry being moved (the list glides along with it).
                    p.focusEntry(to + 1)
                }
            }
            return true
        }
    })
}
