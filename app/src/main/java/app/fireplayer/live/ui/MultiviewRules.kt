package app.fireplayer.live.ui

import kotlin.math.abs

/** Settings › Live TV's Multiview rows (labels, as App settings' categories list them). */
const val MULTIVIEW_PICTURES = "Multiview pictures"
const val MULTIVIEW_LAYOUT = "Multiview with 3 or 4"

/**
 * Where Multiview's pictures go (dp on the 960 x 540 dp screen), and where the D-pad moves between them. Pure, so the
 * maths is unit-tested. The user's design: 2 side by side; 3 and 4 in a 2 x 2 grid (with 3, the fourth cell is "Add");
 * OK on a picture makes it the large one on top with the others small in a row underneath.
 */
object MultiviewLayout {

    /** A rectangle in dp. */
    data class Box(val left: Float, val top: Float, val width: Float, val height: Float) {
        val right: Float get() = left + width
        val bottom: Float get() = top + height
        val centerX: Float get() = left + width / 2f
        val centerY: Float get() = top + height / 2f
    }

    /** Where each picture goes ([tiles], in the pictures' order) and the free cell for "Add", when there's one. */
    data class Layout(val tiles: List<Box>, val add: Box?) {
        /** Every place the D-pad can stop: the pictures, then "Add". */
        val stops: List<Box> get() = if (add == null) tiles else tiles + add
    }

    enum class Dir { LEFT, RIGHT, UP, DOWN }

    /** Pictures keep the TV's shape. */
    const val ASPECT = 16f / 9f
    /** The large picture's row of small ones holds this many across (the most there can be under it). */
    private const val SMALL_ACROSS = 3

    /** Whether [count] pictures laid out as [featured] leave the grid's fourth cell for "Add" ([most] allowed). */
    fun hasAdd(count: Int, featured: Int?, most: Int): Boolean = count == 3 && featured == null && count < most

    /**
     * The layout of [count] pictures on a [w] x [h] screen with [gap] between them and round the edges: [featured] (a
     * picture's index) is the large one on top, null lays them out equally. One picture fills the screen.
     */
    fun of(count: Int, featured: Int?, w: Float, h: Float, gap: Float, most: Int = MultiviewRules.MOST): Layout {
        if (count <= 0) return Layout(emptyList(), null)
        if (count == 1) return Layout(listOf(Box(0f, 0f, w, h)), null)
        if (featured != null && featured in 0 until count) return withLarge(count, featured, w, h, gap)
        if (count == 2) return sideBySide(w, h, gap)
        val cells = grid(w, h, gap)
        return Layout(cells.take(count), if (hasAdd(count, null, most)) cells[3] else null)
    }

    /** Two pictures side by side, as wide as the screen allows, in the middle of it. */
    private fun sideBySide(w: Float, h: Float, gap: Float): Layout {
        var cw = (w - 3 * gap) / 2f
        var ch = cw / ASPECT
        if (ch > h - 2 * gap) { ch = h - 2 * gap; cw = ch * ASPECT }
        val left = (w - (2 * cw + gap)) / 2f
        val top = (h - ch) / 2f
        return Layout(listOf(Box(left, top, cw, ch), Box(left + cw + gap, top, cw, ch)), null)
    }

    /** The 2 x 2 grid's four cells, in reading order, as tall as the screen allows, in the middle of it. */
    private fun grid(w: Float, h: Float, gap: Float): List<Box> {
        var ch = (h - 3 * gap) / 2f
        var cw = ch * ASPECT
        if (2 * cw + 3 * gap > w) { cw = (w - 3 * gap) / 2f; ch = cw / ASPECT }
        val left = (w - (2 * cw + gap)) / 2f
        val top = (h - (2 * ch + gap)) / 2f
        return listOf(
            Box(left, top, cw, ch), Box(left + cw + gap, top, cw, ch),
            Box(left, top + ch + gap, cw, ch), Box(left + cw + gap, top + ch + gap, cw, ch),
        )
    }

    /**
     * [featured] large on top, the others small in a row underneath (in their order), both centred. The small ones are
     * always the size three across would be, so a picture keeps its size as others come and go; the large one takes
     * the height that's left.
     */
    private fun withLarge(count: Int, featured: Int, w: Float, h: Float, gap: Float): Layout {
        val sw = (w - 2 * gap - (SMALL_ACROSS - 1) * gap) / SMALL_ACROSS
        val sh = sw / ASPECT
        var lh = h - sh - 3 * gap
        var lw = lh * ASPECT
        if (lw > w - 2 * gap) { lw = w - 2 * gap; lh = lw / ASPECT }
        val large = Box((w - lw) / 2f, gap, lw, lh)
        val small = count - 1
        val rowTop = large.bottom + gap
        val rowLeft = (w - (small * sw + (small - 1) * gap)) / 2f
        var n = 0
        val tiles = List(count) { i ->
            if (i == featured) large else Box(rowLeft + (n++) * (sw + gap), rowTop, sw, sh)
        }
        return Layout(tiles, null)
    }

    /**
     * Where the D-pad goes from stop [from] towards [dir]: the nearest stop whose middle lies that way, sideways
     * distance counting double (so Down from the large picture lands on the small one under it). [from] when there's
     * none that way.
     */
    fun move(stops: List<Box>, from: Int, dir: Dir): Int {
        val o = stops.getOrNull(from) ?: return from
        var best = from
        var bestScore = Float.MAX_VALUE
        for ((i, b) in stops.withIndex()) {
            if (i == from) continue
            val dx = b.centerX - o.centerX
            val dy = b.centerY - o.centerY
            val (ahead, aside) = when (dir) {
                Dir.LEFT -> -dx to abs(dy)
                Dir.RIGHT -> dx to abs(dy)
                Dir.UP -> -dy to abs(dx)
                Dir.DOWN -> dy to abs(dx)
            }
            if (ahead <= 1f) continue
            val score = ahead + 2f * aside
            if (score < bestScore) { bestScore = score; best = i }
        }
        return best
    }
}

/**
 * Multiview's other rules, pure and unit-tested: what a held picture offers, how the large picture and the selection
 * follow a picture added or removed, where leaving goes, the provider's allowance, the per-picture memory and video
 * size, and the plain words for a picture that can't play.
 */
object MultiviewRules {
    /** At most this many pictures at once (the user's 2 to 4; Settings › Live TV can lower it). */
    const val MOST = 4
    const val LEAST = 2

    /** The most pictures the setting allows (2, 3 or 4). */
    fun most(setting: Int): Int = setting.coerceIn(LEAST, MOST)

    /** A held picture's small window, in the user's order: remove, add (search), change (search), full screen. */
    enum class Choice(val label: String) {
        REMOVE("Remove"),
        ADD("Add channel"),
        CHANGE("Change channel"),
        FULL_SCREEN("Full screen"),
    }

    /** The choices for a held picture with [count] showing and [most] allowed: Add only while there's room. */
    fun menu(count: Int, most: Int): List<Choice> = buildList {
        add(Choice.REMOVE)
        if (count < most) add(Choice.ADD)
        add(Choice.CHANGE)
        add(Choice.FULL_SCREEN)
    }

    /** OK on picture [at]: it becomes the large one; OK on the large one: back to equal (null). */
    fun select(featured: Int?, at: Int): Int? = if (featured == at) null else at

    /** A picture was added as [index], making [countAfter]: the large one stays; with [largeFor3Plus], 3+ open large. */
    fun featuredAfterAdd(featured: Int?, countAfter: Int, index: Int, largeFor3Plus: Boolean): Int? =
        featured ?: if (largeFor3Plus && countAfter >= 3) index else null

    /** Picture [removed] went, leaving [countAfter]: the large one renumbered, or equal when it was the one removed. */
    fun featuredAfterRemove(featured: Int?, removed: Int, countAfter: Int): Int? = when {
        featured == null || featured == removed || countAfter < 2 -> null
        featured > removed -> featured - 1
        else -> featured
    }

    /** A place ([at]: a picture's index, or the "Add" cell's) after picture [removed] went, leaving [countAfter]. */
    fun indexAfterRemove(at: Int, removed: Int, countAfter: Int): Int = when {
        countAfter <= 0 -> 0
        at > removed -> at - 1
        at == removed -> minOf(removed, countAfter - 1)
        else -> at
    }

    /** After a removal: the one picture left, to go full screen on; null while two or more stay in Multiview. */
    fun <T> leaveAfterRemove(left: List<T>): T? = left.singleOrNull()

    /** The channel picker closed without a pick: with one picture (Multiview's start), back to full screen on it. */
    fun leaveWithoutPick(count: Int): Boolean = count < 2

    /** How many streams an account allows: the provider's max_connections, or one when it doesn't say (0). */
    fun allowed(limit: Int): Int = if (limit <= 0) 1 else limit

    /** Whether one more stream fits on an account with [inUse] open of its allowance [limit]. */
    fun fits(inUse: Int, limit: Int): Boolean = inUse < allowed(limit)

    /** Said instead of opening a picture the provider's allowance has no room for. */
    fun limitMessage(limit: Int): String = when {
        limit <= 0 -> "This playlist plays one channel at a time (its provider doesn't say it allows more)"
        limit == 1 -> "This playlist allows one channel at a time"
        limit == 2 -> "This playlist allows 2 channels at a time, and both are in use"
        else -> "This playlist allows $limit channels at a time, and all $limit are in use"
    }

    /** A picture already showing isn't opened twice (it would only use another connection). */
    fun alreadyMessage(name: String): String = "$name is already in Multiview"

    /** A picture whose decoder couldn't be made: the TV's video decoders are all in use. */
    const val NO_DECODER = "This TV can't play another picture at once"
    /** Any other failure that gave up. */
    const val CANT_PLAY = "This channel couldn't be played"

    /**
     * How far each picture's buffer is held from the start (LiveCushion's memory steps): 24 MiB each for two, 12 MiB
     * each for three or four, so four pictures hold less than one channel may on its own (64 MiB).
     */
    fun memoryStep(count: Int): Int = if (count <= 2) 1 else 2

    /** A picture's part in the layout, for the most video it asks for. */
    enum class Role { LARGE, EQUAL, SMALL }

    fun role(index: Int, count: Int, featured: Int?): Role = when {
        count <= 1 || featured == index -> Role.LARGE
        featured == null -> Role.EQUAL
        else -> Role.SMALL
    }

    /**
     * The largest video (width, height) a picture asks for: a channel offering several sizes plays the one that fits,
     * so no picture ever needs a 4K decoder and the small ones need small ones.
     */
    fun maxVideo(role: Role): IntArray = when (role) {
        Role.LARGE -> intArrayOf(1920, 1080)
        Role.EQUAL -> intArrayOf(1280, 720)
        Role.SMALL -> intArrayOf(960, 540)
    }
}

/**
 * Where the D-pad and the large picture are among Multiview's pictures ([T]: one per picture), pure so the moves and
 * the leave rules are unit-tested. [selected] is a picture, or the "Add" cell (index [count]); [audible] is the
 * picture whose sound plays (the one selected last).
 */
class MultiviewModel<T>(most: Int) {
    var most = most
        private set
    val tiles = ArrayList<T>()
    var featured: Int? = null
        private set
    var selected = 0
        private set
    var audible = 0
        private set
    val count: Int get() = tiles.size
    val onAdd: Boolean get() = selected == count && hasAdd()

    fun hasAdd(): Boolean = MultiviewLayout.hasAdd(count, featured, most)

    fun layout(w: Float, h: Float, gap: Float): MultiviewLayout.Layout = MultiviewLayout.of(count, featured, w, h, gap, most)

    /** [t] joins as the last picture, selected and heard. Its index. */
    fun add(t: T, largeFor3Plus: Boolean): Int {
        tiles += t
        val i = count - 1
        featured = MultiviewRules.featuredAfterAdd(featured, count, i, largeFor3Plus)
        selected = i
        audible = i
        return i
    }

    /** Picture [i] goes. The one picture left, to leave to full screen on, when that's all that's left. */
    fun remove(i: Int): T? {
        if (i !in 0 until count) return null
        tiles.removeAt(i)
        featured = MultiviewRules.featuredAfterRemove(featured, i, count)
        audible = MultiviewRules.indexAfterRemove(audible, i, count)
        selected = MultiviewRules.indexAfterRemove(selected, i, count)
        if (selected == count && !hasAdd()) selected = (count - 1).coerceAtLeast(0)
        return MultiviewRules.leaveAfterRemove(tiles)
    }

    /** Picture [i] shows [t] now, in its place. */
    fun replace(i: Int, t: T) {
        if (i in 0 until count) tiles[i] = t
    }

    /** The D-pad towards [dir] in [layout]: true when the selection moved (the sound follows it to a picture). */
    fun move(dir: MultiviewLayout.Dir, layout: MultiviewLayout.Layout): Boolean {
        val next = MultiviewLayout.move(layout.stops, selected, dir)
        if (next == selected) return false
        selected = next
        if (next < count) audible = next
        return true
    }

    /** OK on the selected picture: it becomes the large one, or back to equal. False on "Add" (that opens the picker). */
    fun ok(): Boolean {
        if (selected !in 0 until count) return false
        featured = MultiviewRules.select(featured, selected)
        return true
    }

    /** Back: full screen on the picture heard. */
    fun backTarget(): T? = tiles.getOrNull(audible)

    /** Settings › Live TV lowered the most: the layout follows (never fewer than are showing). */
    fun limitTo(m: Int) {
        most = m
        if (selected == count && !hasAdd()) selected = (count - 1).coerceAtLeast(0)
    }

    fun clear() {
        tiles.clear()
        featured = null
        selected = 0
        audible = 0
    }
}
