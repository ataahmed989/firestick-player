package app.fireplayer.live.ui

import app.fireplayer.live.ui.LivePicture.Placement

/**
 * The live picture moving smoothly from where it is to where it's put (the guide's miniature growing to full screen,
 * full screen shrinking back), worked out frame by frame for HomeActivity. Each frame has where the picture is (the
 * surface, fitted to the picture's shape at both ends by [LivePicture.place], in between the two), where the
 * wallpaper's hole is and how round its corners are (the miniature's radius at one end, square at full screen's), and
 * how much of full screen shows (the screen dimmer fades with it).
 *
 * HomeActivity shows the picture there with the surface's view transforms, never by resizing the surface frame by
 * frame: [transform] gives the scale and translation that show a surface laid out at one place at another. A new
 * move starts from wherever everything is on screen, so a move that interrupts another never jumps.
 *
 * Pure arithmetic over objects made once (nothing is allocated per frame), so it's unit-tested.
 */
class PictureMotion {

    /** A rectangle in screen pixels, with fractions; changed in place. */
    class Box(var left: Float = 0f, var top: Float = 0f, var right: Float = 0f, var bottom: Float = 0f) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top

        fun set(left: Float, top: Float, right: Float, bottom: Float): Box {
            this.left = left
            this.top = top
            this.right = right
            this.bottom = bottom
            return this
        }

        fun set(b: Box): Box = set(b.left, b.top, b.right, b.bottom)

        fun set(p: Placement): Box = set(p.left.toFloat(), p.top.toFloat(), p.right.toFloat(), p.bottom.toFloat())

        /** [from] moved [f] of the way to [to]: [from] at 0, [to] at 1. */
        fun lerp(from: Box, to: Box, f: Float): Box =
            set(mix(from.left, to.left, f), mix(from.top, to.top, f), mix(from.right, to.right, f), mix(from.bottom, to.bottom, f))

        fun same(left: Float, top: Float, right: Float, bottom: Float): Boolean =
            this.left == left && this.top == top && this.right == right && this.bottom == bottom

        fun same(b: Box): Boolean = same(b.left, b.top, b.right, b.bottom)

        fun same(p: Placement): Boolean = same(p.left.toFloat(), p.top.toFloat(), p.right.toFloat(), p.bottom.toFloat())

        override fun equals(other: Any?): Boolean = other is Box && same(other)

        override fun hashCode(): Int = ((left.hashCode() * 31 + top.hashCode()) * 31 + right.hashCode()) * 31 + bottom.hashCode()

        override fun toString(): String = "Box($left, $top, $right, $bottom)"
    }

    /** Where the picture (the fitted surface) is now. */
    val picture = Box()

    /** Where the wallpaper's hole is now. */
    val hole = Box()

    /** The hole's corner radius now. */
    var radius = 0f
        private set

    /** How much of full screen shows now: 0 none (the miniature), 1 all; in between while moving to or from it. */
    var fullness = 0f
        private set

    private val fromPicture = Box()
    private val fromHole = Box()
    private var fromRadius = 0f
    private var fromFullness = 0f
    private val toPicture = Box()
    private val toHole = Box()
    private var toRadius = 0f
    private var toFullness = 0f

    /** Everything at once at this end: placed without a move, or a move ended. */
    fun settle(picture: Placement, hole: Box, radius: Float, full: Boolean) {
        this.picture.set(picture)
        this.hole.set(hole)
        this.radius = radius
        fullness = if (full) 1f else 0f
        toPicture.set(this.picture)
        toHole.set(hole)
        toRadius = radius
        toFullness = fullness
    }

    /** Nothing shown (the picture hidden): nothing of full screen either. */
    fun clear() {
        fullness = 0f
        toFullness = 0f
    }

    /** Whether everything is at this end now (a move there would go nowhere). */
    fun isAt(picture: Placement, hole: Box, radius: Float, full: Boolean): Boolean =
        this.picture.same(picture) && this.hole.same(hole) && this.radius == radius && fullness == (if (full) 1f else 0f)

    /** Whether the last move (or placement) was to this end. */
    fun endsAt(picture: Placement, hole: Box, radius: Float, full: Boolean): Boolean =
        toPicture.same(picture) && toHole.same(hole) && toRadius == radius && toFullness == (if (full) 1f else 0f)

    /** A move from wherever everything is now to this end; [at] takes it along, [at] (0) is where it starts. */
    fun start(picture: Placement, hole: Box, radius: Float, full: Boolean) {
        fromPicture.set(this.picture)
        fromHole.set(this.hole)
        fromRadius = this.radius
        fromFullness = fullness
        toPicture.set(picture)
        toHole.set(hole)
        toRadius = radius
        toFullness = if (full) 1f else 0f
    }

    /** The move [f] of the way along (already eased: 0 where it started, 1 at its end). */
    fun at(f: Float) {
        picture.lerp(fromPicture, toPicture, f)
        hole.lerp(fromHole, toHole, f)
        radius = mix(fromRadius, toRadius, f)
        fullness = mix(fromFullness, toFullness, f)
    }

    companion object {
        /** The shortest time a moving picture takes to head somewhere new when it's put there without a time of its own. */
        const val MIN_RETARGET_MS = 120L

        /** [a] moved [f] of the way to [b]. */
        fun mix(a: Float, b: Float, f: Float): Float = a + (b - a) * f

        /**
         * The view transform that shows a view laid out at [laid] at [shown] instead, with its pivot at its top left
         * (0, 0): scaleX, scaleY, translationX and translationY, in that order, into [out]. The identity when [shown]
         * is where it's laid out.
         */
        fun transform(laid: Placement, shown: Box, out: FloatArray) {
            out[0] = if (laid.width > 0) shown.width / laid.width else 1f
            out[1] = if (laid.height > 0) shown.height / laid.height else 1f
            out[2] = shown.left - laid.left
            out[3] = shown.top - laid.top
        }
    }
}
