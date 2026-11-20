package app.fireplayer.ui

import kotlin.math.abs
import kotlin.math.floor

/**
 * A row of cards that glides like the Episodes sheet's ([EpisodesSheet]): the chosen card stays at the row's left
 * edge and grows from its left edge, the cards after it move along to make room, and the row slides behind it. Two
 * critically damped [Spring]s drive it: where the row is, as a card number (between two while it glides), and how far
 * the chosen card is grown (0 when focus is elsewhere). Each frame only translations and scales change ([Host]);
 * nothing is laid out. A held D-pad sends it on one card per repeat and it carries its speed from press to press.
 */
class RowMotion(private val host: Host) {

    /** The row it moves: its items' places (known without a layout pass) and what each frame sets. */
    interface Host {
        val itemCount: Int

        /** Item [i]'s left edge in the strip, px. */
        fun itemLeft(i: Int): Float

        /** The strip moved left by [offset] px. */
        fun place(offset: Float)

        /** Item [i]: grown by [share] of the growth (0..1), moved along by [shift] px. */
        fun item(i: Int, share: Float, shift: Float)
    }

    /** How far the cards after the chosen one move along (its growth), px. */
    var extra = 0f

    private val at = Spring(omega = 14f, settle = 0.001f) { apply() }
    private val grown = Spring(omega = 16f, settle = 0.001f) { apply() }

    val moving: Boolean get() = at.moving || grown.moving

    /** Glides to card [i], grown. */
    fun choose(i: Int) {
        at.glideTo(i.toFloat())
        grown.glideTo(1f)
    }

    /** Focus left the row: it stays where it is and the chosen card shrinks back. */
    fun shrink() = grown.glideTo(0f)

    /** Straight to card [i], grown or not: a page opening, a row coming back. */
    fun snap(i: Int, isGrown: Boolean) {
        at.snap(i.toFloat())
        grown.snap(if (isGrown) 1f else 0f)
    }

    /** Stops both springs where they're heading (a hidden page posts no frames). */
    fun settle() {
        if (at.moving) at.snap(at.target)
        if (grown.moving) grown.snap(grown.target)
    }

    /** Places every item and the strip for where the springs are now. */
    fun apply() {
        val n = host.itemCount
        if (n == 0) {
            host.place(0f)
            return
        }
        val f = at.value.coerceIn(0f, (n - 1).toFloat())
        val g = grown.value.coerceIn(0f, 1f)
        val i0 = floor(f).toInt()
        val frac = f - i0
        val l0 = host.itemLeft(i0)
        val l1 = host.itemLeft(minOf(i0 + 1, n - 1))
        // Float offsets: the slide is drawn between pixels too.
        host.place(l0 + (l1 - l0) * frac)
        for (i in 0 until n) {
            val share = (1f - abs(i - f)).coerceIn(0f, 1f) * g
            host.item(i, share, extra * g * (i - f).coerceIn(0f, 1f))
        }
    }
}
