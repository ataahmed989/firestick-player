package app.fireplayer.shell

/**
 * One text style of the app's own screens: its size (sp), weight and ink, with no Android types so the places that
 * hold the styles (VodSizes for Films & Shows) stay unit-tested. [Tokens.label] makes a TextView in it,
 * [Tokens.colorOf] and [Tokens.faceOf] give a paint its colour and face. Changing a style here changes every view
 * made from it.
 */
data class TextStyle(
    val sp: Float,
    val weight: Weight = Weight.REGULAR,
    val ink: Ink = Ink.TEXT,
    /** The colour for [Ink.OWN] (ARGB); ignored otherwise. */
    val color: Int = 0,
    /** Extra room between lines, dp (a story's +2 dp). */
    val lineGap: Float = 0f,
) {
    /** Figtree 500, 600 or 700. */
    enum class Weight { REGULAR, SEMIBOLD, BOLD }

    /** The shared colours ([Tokens]), or the style's [color]. */
    enum class Ink { TEXT, MUTED, ACCENT, OWN }

    /** A single line's height with no font padding, in sp: Figtree's ascent and descent are 950 and 250 per 1000. */
    val lineSp: Float get() = sp * LINE

    companion object {
        const val LINE = 1.2f
    }
}
