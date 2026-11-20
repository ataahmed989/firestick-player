package app.fireplayer.live.ui

/**
 * The live picture on Home and Search (automatic picture-in-picture; Design-References/search-pip-2026-11-19/
 * DECISIONS.md, the user's answers): its rules, without Android, so they're unit-tested.
 *
 * - Leaving a playing channel on Live TV for Home or Search keeps it playing there, top right: on Home in Recent
 *   channels' place, on Search beside the page's name. Only those two pages show it ([keepsPicture]); going to
 *   Movies, TV Shows, My List or Settings stops the channel (remembered, as leaving Live TV always did), and going
 *   back to Live TV carries on with it in the guide's miniature ([whenGoingTo]). Settings › Picture-in-picture can
 *   switch it off ([keepOnLeavingLive]): leaving Live TV then stops the channel, as before.
 * - The slim strip under the picture (the channel and programme, Volume, Full screen and Close) shows only while one
 *   of its buttons has focus ([stripShown]). Focus arrives on Volume ([ARRIVES_ON]); the arrows move along the
 *   buttons, and out of the strip Left from Volume and Down from any ([step]).
 * - Back from the strip goes back to where focus was on the page; Back held anywhere on the page while the picture
 *   plays opens it full screen on Live TV ([back]).
 * - Close stops the channel; on Home, Recent channels comes back with focus on its first channel ([focusAfterClose]).
 */
object PipRules {

    /** The app's pages (the rail's tabs). */
    enum class Page { SEARCH, HOME, MOVIES, TV_SHOWS, MY_LIST, LIVE, SETTINGS }

    /** Whether [page] shows the live picture. */
    fun keepsPicture(page: Page): Boolean = page == Page.HOME || page == Page.SEARCH

    /**
     * Leaving Live TV for [to]: whether the channel carries on there in the picture. Only with the setting on
     * ([enabled]), a channel [playing] in the one live player (Multiview's pictures never follow: it ends as Live TV
     * is left) and a page that shows the picture. A channel the viewer [paused] doesn't follow either: it would sit
     * still in the corner with nothing there to start it again (the strip has no Play, and Play/Pause isn't Home's),
     * so it stops, remembered, as leaving Live TV always did.
     */
    fun keepOnLeavingLive(enabled: Boolean, to: Page, playing: Boolean, multiview: Boolean, paused: Boolean): Boolean =
        enabled && playing && !paused && !multiview && keepsPicture(to)

    /** What the picture does as the viewer goes to another page while it plays. */
    enum class Move {
        /** It goes along (Home and Search). */
        KEEP,
        /** The channel stops, remembered for Live TV (Movies, TV Shows, My List, Settings). */
        STOP,
        /** Live TV: the channel carries on in the guide's miniature, with its sound. */
        LIVE,
    }

    fun whenGoingTo(to: Page): Move = when {
        to == Page.LIVE -> Move.LIVE
        keepsPicture(to) -> Move.KEEP
        else -> Move.STOP
    }

    /** The strip's buttons, left to right. */
    enum class Button { VOLUME, FULL_SCREEN, CLOSE }

    /** Focus coming to the picture lands here (the user's answer 3). */
    val ARRIVES_ON = Button.VOLUME

    /** The strip shows only while focus is on one of its buttons (eased in and out). */
    fun stripShown(focusInStrip: Boolean): Boolean = focusInStrip

    enum class Arrow { LEFT, RIGHT, UP, DOWN }

    /** Where an arrow on one of the strip's buttons goes. */
    sealed class Step {
        /** Another of the strip's buttons. */
        data class To(val button: Button) : Step()
        /** Out of the strip to the left: back to where focus came from (the banner's Details, Search's bin). */
        object OutLeft : Step()
        /** Out of the strip downwards: the page under the picture (Home's first row; Search's results or history). */
        object OutDown : Step()
        /** Nowhere: focus stays (Up, and Right from the last button). */
        object Stay : Step()
    }

    fun step(from: Button, arrow: Arrow): Step = when (arrow) {
        Arrow.LEFT -> if (from.ordinal == 0) Step.OutLeft else Step.To(Button.entries[from.ordinal - 1])
        Arrow.RIGHT -> Button.entries.getOrNull(from.ordinal + 1)?.let { Step.To(it) } ?: Step.Stay
        Arrow.UP -> Step.Stay
        Arrow.DOWN -> Step.OutDown
    }

    /** What a Back does while the picture is on the page. */
    enum class BackAct {
        /** Not the picture's: the page's own Back (or the shell's) as usual. */
        PAGE,
        /** Focus goes back to where it was on the page before the strip. */
        RETURN_FOCUS,
        /** The picture opens full screen on Live TV. */
        FULL_SCREEN,
    }

    /**
     * A Back on a page with the picture: [held] (it has repeated, the remote's Back held down), [inStrip] (focus on
     * the strip), [playing] (the picture is on the page) and [pageInFront] (nothing over the page: no title page, See
     * all, small window or settings window, and the press didn't just close one).
     */
    fun back(held: Boolean, inStrip: Boolean, playing: Boolean, pageInFront: Boolean): BackAct = when {
        held && playing && pageInFront -> BackAct.FULL_SCREEN
        !held && inStrip && playing -> BackAct.RETURN_FOCUS
        else -> BackAct.PAGE
    }

    /** Where focus goes after Close. */
    enum class AfterClose {
        /** Home: Recent channels takes the picture's place, its first channel focused. */
        FIRST_RECENT,
        /** Search: back to where it came from (the bin), else the bar. */
        WHERE_IT_WAS,
    }

    fun focusAfterClose(page: Page): AfterClose = if (page == Page.HOME) AfterClose.FIRST_RECENT else AfterClose.WHERE_IT_WAS

    /**
     * Whether the picture shows on its page now, rather than playing on under the page's wallpaper (its sound on as it
     * was): only while its box is on screen and nothing covers it ([covered]: a title page, See all, the settings
     * window), and, where the screen can't move a picture with the page ([canFollow] false: Android 6 and older), only
     * while the page is at rest at its top ([shift] 0).
     */
    fun pictureShows(covered: Boolean, shift: Float, canFollow: Boolean): Boolean =
        !covered && (canFollow || shift == 0f)

    /** The picture starts with its sound off when Settings › Picture-in-picture › Start with sound is off. */
    fun mutedAtStart(startWithSound: Boolean): Boolean = !startWithSound

    // Settings › Picture-in-picture: its rows and their defaults.
    const val KEEP_LABEL = "Keep live TV playing on Home and Search"
    const val SOUND_LABEL = "Start with sound"
    const val KEEP_DEFAULT = true
    const val SOUND_DEFAULT = true
}
