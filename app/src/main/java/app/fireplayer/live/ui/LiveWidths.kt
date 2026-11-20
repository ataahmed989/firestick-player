package app.fireplayer.live.ui

import app.fireplayer.shell.NavRail
import app.fireplayer.ui.GlassStyle

/**
 * The Live TV page's widths across the screen (dp on the 960 x 540 dp screen), kept together so they're unit-tested
 * together: with the groups column open, the tab rail's strip, the column, the miniature and the details beside it fit
 * the screen; the guide under them keeps room for its channel names and the programmes, open and in the grid.
 */
internal object LiveWidths {
    const val SCREEN = 960
    /** The tab rail's slim strip at the screen's left edge, beside the groups column. */
    const val RAIL = NavRail.WIDTH_DP
    /** The groups column: names at 16 sp as long as "USA | Documentaries" (about 153 dp) fit whole in its 164 dp. */
    const val COLUMN = 224
    /** The miniature (368 x 207 dp, user: kept this size). */
    const val MINI = 368
    /** The content's margin at the screen's edges, and between the groups column and the content. */
    const val GAP = 12
    /** Between the miniature and its details. */
    const val DETAILS_GAP = 18

    /**
     * The details' top line: the selected channel's logo on a large tile (the user's concept 5: about 2 to 1, half as
     * tall again as a guide row's; the logo is fitted inside with its shape kept), then its name, with the favorite
     * star at the line's end.
     */
    const val DETAIL_LOGO_W = 82
    const val DETAIL_LOGO_H = 42
    /** The room round the logo inside that tile. */
    const val DETAIL_LOGO_PAD_X = 7f
    const val DETAIL_LOGO_PAD_Y = 5f
    /** Between the tile and the channel's name. */
    const val DETAIL_NAME_GAP = 12
    /** Kept free at the line's end for the star (22 dp and the space before it). */
    const val DETAIL_STAR = 34
    /** Between the top line and the programme's title. */
    const val DETAIL_TITLE_GAP = 6

    /**
     * The guide's channel column beside the groups column: "USA Cinemax Action West" at 15.5 sp (about 186 dp in
     * Figtree) fits beside a three-digit number and the logo. In the grid it's wider, so long names show more.
     */
    const val CHANNELS_OPEN = 288
    const val CHANNELS_GRID = 344
    /** The guide's time scale. */
    const val DP_PER_MIN = 4.6f

    /** Where the content starts with the groups column open. */
    fun openInset(): Int = RAIL + COLUMN + GAP

    /** The details' width with the groups column open: what the screen leaves beside the miniature. */
    fun detailsOpen(): Int = SCREEN - openInset() - MINI - DETAILS_GAP - GAP

    /**
     * The room for the channel's name beside its logo with the groups column open (the narrowest it gets): a name as
     * long as "USA HBO Comedy*" at 16 sp (about 125 dp) fits on one line; a longer one takes a second.
     */
    fun detailNameOpen(): Int = detailsOpen() - DETAIL_LOGO_W - DETAIL_NAME_GAP - DETAIL_STAR

    /** The guide's width with the groups column open, and in the grid. */
    fun guideOpen(): Int = SCREEN - openInset() - GAP
    fun guideGrid(): Int = SCREEN - 2 * GAP

    /** The minutes of programmes a guide [guide] wide shows beside a channel column [channels] wide. */
    fun minutesShown(guide: Int, channels: Int): Float = (guide - channels) / DP_PER_MIN

    // ---- Multiview (inside the full-screen Live TV player; the layout itself is [MultiviewLayout]) ---------------------

    /** Screen height, for Multiview's layout on the 960 x 540 dp screen. */
    const val SCREEN_H = 540
    /** Between the pictures and round the screen's edge: room for the selected picture's white ring (VodSizes.RING). */
    const val MV_GAP = 8f
    /** A picture's channel name: on the logos' neutral dark plate (Tokens.LOGO_BADGE_PLATE) at its bottom left. */
    const val MV_NAME_SP = 13f
    const val MV_NAME_PAD_X = 8
    const val MV_NAME_PAD_Y = 4
    const val MV_NAME_INSET = 8
    const val MV_NAME_RADIUS = 7f
    /** A picture that can't play: its plain message, in its middle, shadowed as the buffering symbol's words are. */
    const val MV_MESSAGE_SP = 14f
    const val MV_MESSAGE_PAD_X = 16
    const val MV_MESSAGE_SHADOW = 4f
    const val MV_MESSAGE_SHADOW_COLOR = 0xCC000000.toInt()
    /** "Add a channel" in the grid's free cell, under its + (the guide's empty miniature panel). */
    const val MV_ADD_SP = 15f
    const val MV_ADD_ICON = 30
    const val MV_ADD_GAP = 10
    /** The pictures moving to a new layout (translation and scale only), and the channel picker's dark backdrop. */
    const val MV_MOVE_MS = 260L
    /**
     * The search page over Multiview and its ready picture: see-through, so the pictures show under it (the user's
     * TiviMate photo 3). Classic: as dark as before.
     */
    const val MV_PICKER_SCRIM = 0xB806080B.toInt()
    const val MV_PICKER_SCRIM_CLASSIC = 0xEB06080B.toInt()
    const val MV_PICKER_TITLE_SP = 18f
    /** Every Multiview picture's corners (and its ring's inside edge): the miniature's. */
    const val MV_RADIUS = 12f

    // ---- The miniature, and Multiview's ready picture -----------------------------------------------------------------

    /** The miniature's height (368 x 207 dp, the TV's shape) and its corners. */
    const val MINI_H = 207
    const val MINI_RADIUS = 12f

    // ---- The channel list over full screen and Multiview ([LiveOverlay]) ---------------------------------------------

    /**
     * Its columns, left to right: the playing playlist's groups (as wide as the guide's groups column), the channels
     * (number, logo, name over the programme on now, its progress line), the focused channel's programmes. Beside the
     * groups, the channels column ends at 534 dp: clear of Multiview's ready picture at the top right (580 dp on).
     */
    const val OV_GROUPS_W = COLUMN
    const val OV_CHANNELS_W = 310
    const val OV_PROGRAMMES_W = 250
    /**
     * A column's header (the playlist, its group, or the focused channel: its logo beside its number and name over its
     * playlist) and the room above it. One height for every column, so the rules under them line up; the same room is
     * left under the last whole row.
     */
    const val OV_TOP = 10
    const val OV_HEAD_H = 52
    /** Channel rows: this many fill the column under its header (TiviMate shows eight or nine). */
    const val OV_CHANNEL_ROWS = 9
    /** A channel row's name line and programme line, as shares of its height (with the progress line, centred in it). */
    const val OV_NAME_AT = 0.27f
    const val OV_SUB_AT = 0.57f
    /** A group's row (the guide's groups column's) and a programme's. */
    const val OV_GROUP_ROW = 39
    const val OV_PROGRAMME_ROW = 36
    /** Inside a column: its sides, and a row's content inside the white pill. */
    const val OV_PAD_X = 10
    const val OV_ROW_PAD_X = 10
    /** A channel row: its number (centred in this width, or in what the list's longest number needs), the logo (the guide's), the gaps. */
    const val OV_NUMBER_W = 30
    const val OV_LOGO_W = 50
    const val OV_LOGO_H = 28
    const val OV_GAP = 9
    /** Its progress line under the name and programme, and the gap above it. */
    const val OV_PROGRESS_H = 2.5f
    const val OV_PROGRESS_GAP = 5
    /** The header over the programmes: the channel's logo on its plate. */
    const val OV_HEAD_LOGO_W = 64
    const val OV_HEAD_LOGO_H = 34
    /** The programme column: the start time's width before the title (more when the times need it). */
    const val OV_TIME_W = 74
    /** Text: names and groups as the guide's, the programme lines a little smaller. */
    const val OV_NAME_SP = 15.5f
    const val OV_SUB_SP = 14f
    const val OV_GROUP_SP = 16f
    const val OV_HEAD_SP = 15f
    const val OV_PROGRAMME_SP = 15f
    /** The white pill's corners (the groups column's). */
    const val OV_PILL_RADIUS = 11f
    /**
     * The glass over the picture, darkest at the screen's edge (groups) and lightest beside the picture (programmes):
     * dark enough that the picture only hints through, so a column is one surface from top to bottom over a film's
     * bright picture and its black bars alike. Classic: solid dark.
     */
    const val OV_TINT_GROUPS = 0xF50C0E12.toInt()
    const val OV_TINT_CHANNELS = 0xF20C0E12.toInt()
    const val OV_TINT_PROGRAMMES = 0xF20C0E12.toInt()
    const val OV_CLASSIC_GROUPS = 0xFF0F1216.toInt()
    const val OV_CLASSIC_CHANNELS = 0xFF161A1F.toInt()
    const val OV_CLASSIC_PROGRAMMES = 0xFF1B2026.toInt()
    /** The focused programme's details at the top right, in their own glass card. */
    const val OV_DETAILS_GAP = 12
    const val OV_DETAILS_PAD = 14
    const val OV_DETAILS_TITLE_SP = 19f
    const val OV_DETAILS_SP = 14.5f
    const val OV_DETAILS_LINES = 4
    /** Its time line: the times, the progress line, the minutes left. */
    const val OV_DETAILS_TIME_W = 152
    const val OV_DETAILS_BAR_W = 80
    const val OV_DETAILS_BAR_H = 10
    const val OV_DETAILS_LINE_GAP = 6
    const val OV_DETAILS_RADIUS = GlassStyle.RADIUS_POPUP
    const val OV_DETAILS_TINT = 0xF20C0E12.toInt()
    const val OV_DETAILS_CLASSIC = 0xFF161A1F.toInt()
    /** The columns sliding and fading as Left and Right step between them (translation and alpha only). */
    const val OV_SLIDE_MS = 180L
    /** Opening and closing: the list fades and slides in from this far to the left. */
    const val OV_ENTER_DX = 24
    /** Up and Down: the rows scroll and the white pill glides as the guide's do (a held key's moves are quicker). */
    const val OV_MOVE_MS = 150L
    const val OV_REPEAT_MS = 80L
    /** A move further than a screen glides only this many rows (the rest is a jump), as the guide's. */
    const val OV_LONG_GLIDE_ROWS = 3
    /** The programme on now is read for the rows on screen and this many before and after them. */
    const val OV_NOW_AROUND = 12
    /** The rows' programmes and progress lines are looked at again this often while the list shows. */
    const val OV_TICK_MS = 30_000L
    /** Programme lists kept for channels focused lately (moving back and forth shows them at once). */
    const val OV_KEPT_LISTS = 24
    /** Track under a row's progress line, on the glass and on the white pill. */
    const val OV_TRACK = 0x40FFFFFF
    const val OV_TRACK_ON_PILL = 0x26000000
    /** Past programmes and "No information". */
    const val OV_TEXT_DIM = 0x99F4F2EE.toInt()
    /** A group's channels show this long after the selection rests on it (the guide's groups column's pause). */
    const val OV_PREVIEW_MS = 350L
    /** The focused channel's programmes are read once the selection has rested on it this long. */
    const val OV_PROGRAMMES_AFTER_MS = 180L
    /** The programme column reads from this long before now to this long after. */
    const val OV_PROGRAMMES_BEFORE_MS = 3 * 60 * 60_000L
    const val OV_PROGRAMMES_AHEAD_MS = 12 * 60 * 60_000L
}
