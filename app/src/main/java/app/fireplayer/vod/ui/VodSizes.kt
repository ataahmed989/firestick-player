package app.fireplayer.vod.ui

import app.fireplayer.shell.NavRail
import app.fireplayer.shell.RailShape
import app.fireplayer.shell.TextStyle
import app.fireplayer.shell.TextStyle.Ink
import app.fireplayer.shell.TextStyle.Weight
import app.fireplayer.shell.Tokens
import app.fireplayer.ui.PageRow
import app.fireplayer.vod.RowStyle
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Films & Shows' one look holder: every size, text style, colour and motion value of the Home, Movies and TV Shows
 * pages and the title page, in dp on the 960 x 540 dp screen (design-brief.md §6.3-6.4, and the sizing page the user
 * chose them on: its 16 dp gap between the header and the live window, 24 dp right margin, 10 dp between a row's
 * title and its cards, 18 dp under each row and 3 dp white ring). The app-wide colours, faces and focus growth are in
 * [Tokens] and the surfaces in [app.fireplayer.ui.Glass]; what only Films & Shows uses is here, so a change made here
 * reaches every page that shows it. Kept without Android types, so it's unit-tested. x values are page-local (the
 * pages start right of the rail's strip) unless they say screen.
 */
internal object VodSizes {
    const val SCREEN_W = 960f
    const val SCREEN_H = 540f
    /** The tab rail's strip: the pages start here. */
    const val RAIL = NavRail.WIDTH_DP.toFloat()
    /** Where content starts on the screen (user), and in the page. */
    const val CONTENT_SCREEN_X = 94f
    const val PAGE_X = CONTENT_SCREEN_X - RAIL
    const val GAP = 14f
    const val RIGHT_MARGIN = 24f
    /** How much bigger the chosen card is (user: 16%). */
    const val GROW = 0.16f
    /** Chips (genres, seasons) grow like the app's other pills. */
    const val GROW_CHIP = Tokens.GROW_PILL - 1f
    const val POSTERS_ACROSS = 6.5f
    const val WIDE_ACROSS = 4.5f
    /**
     * The browse pages' cards (Home, Movies, TV Shows, My List, Search; the brief of 2026-11-19): Continue Watching
     * about 3.3 across (three whole cards and part of a fourth), posters larger by the same factor. The title page's
     * own rows keep the sizes above.
     */
    const val BROWSE_WIDE_ACROSS = 3.3f
    const val BROWSE_POSTERS_ACROSS = 4.8f

    /** A card's width when [n] fit across from the content's left edge to the screen's right edge, [GAP] apart. */
    fun across(n: Float): Float = (SCREEN_W - CONTENT_SCREEN_X + GAP) / n - GAP

    /** The title page's posters (More Like This): 3:4, 6.5 across: 121 x 162. */
    val POSTER_W: Float = across(POSTERS_ACROSS).roundToInt().toFloat()
    val POSTER_H: Float = (across(POSTERS_ACROSS) * 4f / 3f).roundToInt().toFloat()
    /** A show's episode stills on the title page: 16:9, 4.5 across: 182 x 102. */
    val WIDE_W: Float = across(WIDE_ACROSS).roundToInt().toFloat()
    val WIDE_H: Float = (across(WIDE_ACROSS) * 9f / 16f).roundToInt().toFloat()
    /** The browse pages' posters: 3:4, 4.8 across: 169 x 226. Top 10's too, with their numbers. */
    val BROWSE_POSTER_W: Float = across(BROWSE_POSTERS_ACROSS).roundToInt().toFloat()
    val BROWSE_POSTER_H: Float = (across(BROWSE_POSTERS_ACROSS) * 4f / 3f).roundToInt().toFloat()
    /** Continue Watching cards: 16:9, 3.3 across: 253 x 142. */
    val BROWSE_WIDE_W: Float = across(BROWSE_WIDE_ACROSS).roundToInt().toFloat()
    val BROWSE_WIDE_H: Float = (across(BROWSE_WIDE_ACROSS) * 9f / 16f).roundToInt().toFloat()

    /** Card corners, and the white ring round the chosen card (hugging its outside edge). */
    const val CARD_RADIUS = 10f
    const val RING = 3f

    // Top 10 (Glass Home's ranked shelf): fractions of the poster's height, from its 333 px poster.
    const val RANK_SLOT = 124f / 333f
    const val RANK_TEXT = 230f / 333f
    const val RANK_BASELINE = 2f / 333f
    /** The room "10" may take: the slot and 6 of its px, and the tuck. */
    const val RANK_ROOM = 130f / 333f
    /** How far the number sits behind the poster. */
    const val RANK_TUCK = 5f
    /** The rank number: solid white, a little tighter (Glass Home). */
    const val RANK_COLOR = 0xFFFFFFFF.toInt()
    const val RANK_SPACING = -0.06f

    // A row: its title, the gap, the strip of cards (as tall as a grown card) and the gap under it.
    const val ROW_TITLE = 16f
    const val ROW_TITLE_GAP = 10f
    const val ROW_GAP = 18f

    // Chips (genres; a show's seasons on the title page).
    const val CHIP_H = 36f
    const val CHIP_PAD = 16f
    const val CHIP_GAP = 10f
    /** The rim of a flat surface in the glass look (chips, See all cards), dp (at least a pixel). */
    const val FLAT_RIM = 1f
    /**
     * The season shown (not focused): the Episodes sheet's fill and ring (EpisodesSheet.stylePill), both the accent
     * ([Tokens.accent]); the fill at this alpha (40%).
     */
    const val CHIP_SELECTED_ALPHA = 0x66
    const val CHIP_SELECTED_RIM = 1.5f

    // The page: the name line, the viewport under it (which clips), the header.
    const val NAME_TOP = 12f
    const val VIEWPORT_TOP = 44f
    const val VIEWPORT_H = SCREEN_H - VIEWPORT_TOP
    /**
     * The banner (and Home's Recent channels beside it), on Home, Movies and TV Shows alike, so the rows under it sit
     * at the same height on every tab. On Home the first row under it (Continue Watching, a card grown) shows whole and
     * the next row's title still shows above the part of the screen a TV may cut off ([TV_SAFE]).
     */
    const val HEADER_H = 228f
    /** Between the header and the first row. */
    const val HEADER_GAP = 16f
    /** Home's top-right window (the live picture / recent channels), and its gap from the header card. */
    const val WINDOW_W = 290f
    const val WINDOW_GAP = 16f
    /** The bottom of the screen a TV may cut off (5%), kept clear of what must show. */
    const val TV_SAFE = 27f
    /** Where the rail's floating window ends on the screen (every tab but Live TV, whose strip is attached). */
    const val RAIL_WINDOW_END = RAIL - RailShape.FLOAT_SIDE_DP
    /**
     * The backdrop box: at most this many header heights wide, right-aligned in the card. In the classic look it sits
     * this far inside the card's edge (its border shows round it); in the glass look it fills the card and the outline
     * is drawn over it.
     */
    const val BACKDROP_ASPECT = 2.6f
    /**
     * The wide cards' (Movies, TV Shows, My List) picture box: as wide as it was with the 216 dp banner, so their dark
     * side and My List's banner stay as they were.
     */
    const val BACKDROP_W = BACKDROP_ASPECT * 216f
    /**
     * Home's narrower card has the same look (the picture in a box at its right, its left edge eased into the dark
     * side): the box is the picture's own shape (16:9), so the picture shows whole, nothing of its subject cut.
     */
    const val HOME_BACKDROP_ASPECT = 16f / 9f
    const val HEADER_ART_INSET = 1f
    /**
     * My List: its banner holds still, and its rows scroll under it from [fixedRowsTop] to the screen's bottom. So it's
     * as tall as leaves a row of posters whole under it (the row's title, the chosen poster grown and its ring), and it
     * has no buttons (it shows the chosen poster's title).
     */
    const val MY_LIST_HEADER_H = 186f
    fun fixedRowsTop(): Float = VIEWPORT_TOP + MY_LIST_HEADER_H + HEADER_GAP

    // The Search tab: its bar (the Settings tab's row, [PageRow]) where the header would be, the round trash button
    // beside it (the title page's round buttons, at the bar's height), the history list under it (Settings rows too),
    // and the wait after the last key before the results are asked for.
    const val SEARCH_BAR_H = PageRow.ROW_DP
    const val SEARCH_TRASH = PageRow.ROW_DP
    const val SEARCH_TRASH_ICON = 18f
    const val SEARCH_TRASH_GAP = 12f
    /** Room under the trash button for its name, which shows while it is focused. */
    const val SEARCH_TRASH_LABEL = 22f
    /**
     * Room the Search page's column always has under its bar, results or not: the trash button's name hangs there
     * (pushed a little lower as the focused button grows) and must never be cut.
     */
    const val SEARCH_UNDER_BAR = 44f
    /** What Search says in the history's place from the moment a search is asked for until its first answer. */
    const val SEARCHING_NOTE = "Searching\u2026"
    /** Between the bar and what's under it (the history list, the first row of results). */
    const val SEARCH_GAP = HEADER_GAP
    /** The history list's room at the screen's bottom. */
    const val SEARCH_LIST_BOTTOM = 16f
    const val SEARCH_WAIT_MS = 500L
    /** Movies & TV Shows search: how long one kind's answer waits for the other's, so the two show together in order. */
    const val SEARCH_OTHER_KIND_WAIT_MS = 1200L
    /** The bar's hint words: the row's own text colour (light, or dark on the white focused row) at this alpha. */
    const val SEARCH_HINT_ALPHA = 0x8C
    fun searchBarW(): Float = tabHeaderW() - SEARCH_TRASH_GAP - SEARCH_TRASH
    /** The bar while the live picture is in the top right ([searchPipX]): shortened so the trash button clears it. */
    fun searchBarW(pip: Boolean): Float =
        if (pip) searchPipX() - SEARCH_PIP_GAP - SEARCH_TRASH - SEARCH_TRASH_GAP - PAGE_X else searchBarW()
    /** The kind switch above the bar: two pills ("Movies & TV Shows", "Live TV"), the gap between them and under them. */
    const val SEARCH_KIND_H = 36f
    const val SEARCH_KIND_PAD_X = 16f
    const val SEARCH_KIND_SPACE = 10f
    const val SEARCH_KIND_GAP = 12f
    /** The bar and the pills above it: what the page's column holds where a header would be. */
    fun searchTopH(): Float = SEARCH_KIND_H + SEARCH_KIND_GAP + SEARCH_BAR_H
    fun searchListTop(): Float = VIEWPORT_TOP + searchTopH() + SEARCH_GAP

    // The live picture (automatic picture-in-picture, DECISIONS.md of 2026-11-19). On Home it takes Recent channels'
    // place in a window of its own shape ([PIP_WINDOW_W] x [HEADER_H] at [windowX] (pip), the user's request of
    // 2026-11-20), so a 16:9 channel fills it with no bars; the banner narrows to keep the gap before it. On Search
    // it's 16:9, 352 x 198 dp (the user's "larger" of 2026-11-20; it was 240 wide), in the top right corner beside the
    // page's name: the pills, the bar and the history beside it, the bar and the history shortened to clear it
    // ([searchBarW]) by [SEARCH_PIP_GAP] (room for the trash button's name), and the results (rows of posters, Live
    // TV's channels and programmes) under it, full width ([searchResultsTop]), where a first row of posters still
    // shows whole, its chosen card grown. Larger, the bar would drop under 400 dp and that grown card would reach on
    // into the edge a TV may cut off ([TV_SAFE]), which it only just touches now.
    const val SEARCH_PIP_W = 352f
    const val SEARCH_PIP_H = SEARCH_PIP_W * 9f / 16f
    const val SEARCH_PIP_TOP = NAME_TOP
    const val SEARCH_PIP_GAP = 24f
    /** Page-local left of Search's picture: right-aligned at the page's right margin. */
    fun searchPipX(): Float = SCREEN_W - RAIL - RIGHT_MARGIN - SEARCH_PIP_W
    /**
     * Where Search's results start (screen y): under the bar ([searchListTop]), or with the live picture there ([pip])
     * [SEARCH_GAP] under the picture. The history and "Searching…" stay under the bar, beside the picture.
     */
    fun searchResultsTop(pip: Boolean): Float =
        if (pip) maxOf(searchListTop(), SEARCH_PIP_TOP + SEARCH_PIP_H + SEARCH_GAP) else searchListTop()
    /**
     * The slim strip, shown only while the picture has focus: a pill this tall, inset this much from the box's sides
     * and bottom, over the picture's bottom edge (glass in the glass look, solid in Classic), the channel and programme
     * ([PIP_WORDS]) and three round buttons ([PIP_BUTTON], icons [PIP_ICON]). It eases in and out ([PIP_STRIP_MS])
     * rising [PIP_STRIP_RISE].
     */
    const val PIP_STRIP_H = 28f
    const val PIP_STRIP_INSET = 4f
    const val PIP_STRIP_PAD_L = 12f
    const val PIP_STRIP_PAD_R = 3f
    const val PIP_BUTTON = 22f
    const val PIP_ICON = 13f
    const val PIP_BUTTON_GAP = 6f
    const val PIP_WORDS_GAP = 8f
    const val PIP_STRIP_MS = 180L
    const val PIP_STRIP_RISE = 6f
    /** Between the channel's name and the programme on the strip. */
    const val PIP_SEPARATOR = " \u00B7 "

    /**
     * Home's live picture's window: 16:9 at the banner's height ([HEADER_H]) is 405.3 dp, kept to whole dp so its edges
     * fall on whole pixels; a 16:9 channel fills it (the 0.3 dp is within [app.fireplayer.live.ui.LivePicture.FILL_WITHIN]).
     * It ends where Recent channels ends, 24 dp from the screen's edge, and the banner narrows to end [WINDOW_GAP]
     * before it ([homeHeaderW] (pip)).
     */
    const val PIP_WINDOW_W = 405f
    /** Recent channels' contents fade in once its window is its own width again (after the picture's Close). */
    const val RECENT_FADE_IN_MS = 160L

    // Live TV search's results, under the bar: a row of channel tiles (the guide's logo plate, the name beneath), then
    // a list of programme rows (logo, title, "channel · start-end", a LIVE mark while on now).
    const val LIVE_HEAD_H = 26f
    const val LIVE_TILE_W = 120f
    const val LIVE_TILE_H = 66f
    const val LIVE_TILE_GAP = 14f
    const val LIVE_TILE_PAD = 10f
    const val LIVE_TILE_RADIUS = 12f
    const val LIVE_NAME_H = 22f
    const val LIVE_NAME_TOP = 6f
    const val LIVE_SECTION_GAP = 14f
    const val LIVE_ROW_H = 52f
    const val LIVE_ROW_GAP = 8f
    const val LIVE_ROW_LOGO_W = 58f
    const val LIVE_ROW_LOGO_H = 34f
    const val LIVE_ROW_LOGO_GAP = 12f
    const val LIVE_MARK_PAD_X = 8f
    const val LIVE_MARK_H = 18f
    const val LIVE_LOGO_PX = 120f
    /** A channel name that doesn't fit its tile starts scrolling this long after focus comes to it. */
    const val LIVE_MARQUEE_DELAY_MS = 300L
    /** A channel tile whose logo hasn't come after this long (or has none) shows the channel's name inside, quietly. */
    const val LIVE_STAND_IN_AFTER_MS = 700L
    const val LIVE_STAND_IN_ALPHA = 0.7f
    /** Its speed, dp a second, and the pause at its end before it starts over. */
    const val LIVE_MARQUEE_DP_S = 40f
    const val LIVE_MARQUEE_HOLD_MS = 1_200L

    /** The clock's fixed width beside the page's name, and the gap before it. */
    const val CLOCK_W = 300f
    const val CLOCK_GAP = 14f

    // The banner's words (tuning round 1): at the top left the title's box (its logo, or its name where it has none),
    // the facts line and two lines of story, in one column ([storyW]); at the bottom left the Watch now and Details
    // buttons; the dots centred near the bottom. One margin on the left, the top and under the buttons.
    const val HEADER_INSET = 24f
    /**
     * Home's banner reaches out towards the rail's window, its words (at [HEADER_INSET] inside it) in line with the
     * rows' titles under it: page-local x, and on the screen (19 dp from the rail's window, [RAIL_WINDOW_END]).
     */
    const val HOME_HEADER_X = PAGE_X - HEADER_INSET
    const val HOME_HEADER_SCREEN_X = RAIL + HOME_HEADER_X
    const val HEADER_TOP = 24f
    /** The title's box: the logo stands in it at the left and bottom (its visible part), or the name does. */
    const val HEADER_TITLE_H = 52f
    /** The name on one line; one too wide for the column at that size, on up to two lines of the smaller size. */
    const val HEADER_TITLE_SP = 28f
    const val HEADER_TITLE_LONG_SP = 21f
    const val HEADER_TITLE_LINES = 2
    /** The logo: at most this share of the column, and never wider than this (205 dp on Home, 240 on the wide cards). */
    const val HEADER_LOGO_SHARE = 0.7f
    const val HEADER_LOGO_MAX = 240f
    /**
     * A logo is decoded with this many times its box (logos come with wide empty margins), then cut to its visible part
     * and kept no larger than it's shown (image thread), so one in memory is at most the box's pixels.
     */
    const val HEADER_LOGO_DECODE = 2
    /** How long the title's box waits, empty, for a logo that isn't in memory yet; then it shows the name instead. */
    const val HEADER_LOGO_WAIT_MS = 500L
    const val HEADER_FACTS_SP = 12.5f
    const val HEADER_STORY_SP = 13f
    /**
     * The words' column: never wider than [HEADER_STORY_MAX], and ending at least [HEADER_CLEAR] from the card's right
     * edge, where the picture's subject is, and on the dark side ([storyW]: 293 dp on Home, 400 on the wide cards).
     */
    const val HEADER_STORY_MAX = 400f
    const val HEADER_CLEAR = 172f
    const val HEADER_STORY_LINES = 2
    const val HEADER_FACTS_GAP = 7f
    const val HEADER_STORY_GAP = 5f
    /** The banner's Watch now and Details, at the bottom left: this far up, this tall, and their pill's own sizes. */
    const val HEADER_BUTTONS_BOTTOM = 24f
    const val HEADER_BUTTON_H = 34f
    const val HEADER_BUTTON_PAD = 14f
    const val HEADER_BUTTON_GAP = 12f
    const val HEADER_BUTTON_ICON = 15f
    const val HEADER_BUTTON_ICON_GAP = 7f
    /**
     * Room kept above the buttons, inside their row, for a focused one's growth and glow (its shadow falls into the
     * margin under it; the margins left and right hold the rest).
     */
    const val HEADER_BUTTON_ROOM = 14f
    /**
     * While the page hides the card's top (a first row of posters moves the page up a little), the words stay
     * [HEADER_TOP_KEEP] under the cut: they move down, as far as leaves [HEADER_WORDS_MIN_GAP] above the buttons
     * ([headerWordsShift]).
     */
    const val HEADER_TOP_KEEP = 6f
    const val HEADER_WORDS_MIN_GAP = 8f
    /** A soft shadow under the header's words, so they read where the picture is bright: blur, drop, colour. */
    const val HEADER_TEXT_SHADOW = 3.5f
    const val HEADER_TEXT_SHADOW_DY = 1f
    const val HEADER_TEXT_SHADOW_COLOR = 0xA6000000.toInt()
    /** The rotation's dots: the box, the current one's bar, the gap between, the bottom margin. */
    const val DOTS_W = 130f
    const val DOTS_H = 5f
    const val DOT_BAR = 14f
    const val DOT_GAP = 5f
    const val DOTS_BOTTOM = 9f
    const val DOT_ON = 0xFFFFFFFF.toInt()
    const val DOT_OFF = 0x59FFFFFF

    // The See all grid: 5 posters across the content's width (to the right margin), near the rows' poster size.
    const val GRID_COLUMNS = 5
    const val GRID_GAP = 18f
    const val GRID_ROW_GAP = 30f
    val GRID_POSTER_W: Float = ((SCREEN_W - CONTENT_SCREEN_X - RIGHT_MARGIN - (GRID_COLUMNS - 1) * GRID_GAP) / GRID_COLUMNS).roundToInt().toFloat()
    val GRID_POSTER_H: Float = (GRID_POSTER_W * 4f / 3f).roundToInt().toFloat()
    /** Above the first row of posters: the chosen one's growth (about its middle) and its ring show whole under the name line. */
    val GRID_TOP: Float = ceil(GRID_POSTER_H * GROW / 2f + RING + 1f)

    // "Couldn't load titles": the message, Try again (its padding) and the gap between.
    const val TRY_PAD_X = 22f
    const val TRY_PAD_Y = 10f
    const val TRY_GAP = 18f

    // Notices ("Playback comes next"): padding, distance from the bottom, how long they stay.
    const val NOTICE_PAD_X = 16f
    const val NOTICE_PAD_Y = 10f
    const val NOTICE_BOTTOM = 30f
    const val NOTICE_MS = 3_500L

    // The small window (hold OK): kept this far inside the page; closed this long after + Add to list.
    const val HOLD_INSET = 12f
    const val LIST_CLOSE_MS = 450L

    // Cards: an episode still's progress bar ([CARD_TEXT_SIDE] from its sides, [BAR_BOTTOM] up), the slim bar, badges.
    const val CARD_TEXT_SIDE = 8f
    const val BAR_BOTTOM = 6f
    const val BAR_H = 3f
    const val BAR_RADIUS = 1.5f
    /**
     * A Continue Watching card: its name ([CARD_NAME]), caption line ([CARD_CAPTION]) and slim progress bar stand on a
     * gradient over its bottom, the picture above it left clear. The words [WIDE_TEXT_SIDE] from its sides, their
     * bottom [CARD_BOTTOM] up ([CARD_BOTTOM_BAR] above a bar, itself [WIDE_BAR_BOTTOM] up). The gradient: black, clear
     * at [WIDE_SCRIM_STOPS]' first share of the height, easing to [SCRIM_BOTTOM] at the bottom.
     */
    const val WIDE_TEXT_SIDE = 12f
    const val CARD_BOTTOM = 12f
    const val CARD_BOTTOM_BAR = 20f
    const val CARD_LINE_GAP = 2f
    const val WIDE_BAR_BOTTOM = 10f
    val WIDE_SCRIM_STOPS = floatArrayOf(0.42f, 0.62f, 0.82f, 1f)
    val WIDE_SCRIM_ALPHAS = intArrayOf(0x00, 0x45, 0x99, 0xC7)
    const val BADGE_PAD_X = 7f
    const val BADGE_PAD_Y = 2f
    const val BADGE_INSET = 6f
    const val BADGE_RADIUS = 8f
    const val HOLDER_LINES = 4
    /** The white of the focus ring (the sizing page's focus white). */
    const val RING_COLOR = 0xFFF2F4F7.toInt()
    /** The Episodes sheet's card placeholder. */
    const val HOLDER_TOP = 0xFF26313F.toInt()
    const val HOLDER_BOTTOM = 0xFF141A22.toInt()
    const val SCRIM_BOTTOM = 0xC7000000.toInt()
    const val TRACK_COLOR = 0x47FFFFFF
    const val SEE_ALL = "See all ›"

    // Home's recent channels window: as tall as the banner beside it. Its heading (the history icon and the words),
    // then a list of channels, three showing at a time: a logo tile, the name and what's on now, a fine line between
    // rows and a slim scrollbar at the right while there are more than show.
    const val RECENT_PAD_X = 16f
    const val RECENT_PAD_TOP = 14f
    const val RECENT_PAD_BOTTOM = 12f
    const val RECENT_ICON = 16f
    const val RECENT_HEAD_H = 20f
    const val RECENT_LOGO_W = 58f
    const val RECENT_LOGO_H = 34f
    const val RECENT_LOGO_PX = 84f
    const val RECENT_ROW_H = 58f
    const val RECENT_ROWS_SHOWN = 3
    /** The most channels it lists. */
    const val RECENT_MAX = 12
    /** A row reaches this far outside the heading's left edge and the text's right, so its focus shape has room. */
    const val RECENT_ROW_PAD = 8f
    const val RECENT_ICON_GAP = 8f
    const val RECENT_LINE_GAP = 2f
    const val RECENT_LOGO_GAP = 12f
    const val RECENT_FIRST_TOP = 8f
    const val RECENT_EMPTY_TOP = 14f
    /** The chosen row: a soft fill with the cards' white ring, this thick, this far inside the row's top and bottom. */
    const val RECENT_FOCUS_RING = 2f
    const val RECENT_FOCUS_INSET = 3f
    /**
     * The scrollbar: its width and its distance from the window's right edge. Its colours are the glass style's (the
     * track a row's fill, the thumb the highlight).
     */
    const val RECENT_BAR_W = 2f
    const val RECENT_BAR_RIGHT = 6f
    const val RECENT_SCROLL_MS = 160L

    // Motion.
    /** The page's and See all's glide (and the title page's): a Spring this quick, settled this close (px). */
    const val PAGE_OMEGA = 14f
    const val PAGE_SETTLE = 0.5f
    /** A header's backdrop crossfade (Glass Home's hero fade), its text fade, and the follow and warm-ahead waits. */
    const val HERO_FADE_MS = 650L
    const val HEADER_TEXT_FADE_MS = 120L
    const val FOLLOW_ART_MS = 500L
    const val WARM_AHEAD_MS = 1_500L
    /**
     * Settings › Home Header › Time on each title: how long every tab's banner (Home, Movies, TV Shows) shows a title
     * while idle, in seconds; 8 by default.
     */
    val HEADER_IDLE_SECONDS = listOf(5, 8, 12, 20)
    const val HEADER_IDLE_DEFAULT_SEC = 8
    /**
     * A banner's change to its next title takes this long (the picture's crossfade; the words fade out and in within
     * it): D-pad Up held on its buttons moves on no faster.
     */
    const val HEADER_CHANGE_MS = HERO_FADE_MS
    /** Faces sit high in most backdrops: the crop keeps a little more of the top. */
    const val ART_CROP_Y = 0.4f
    /** The backdrop's baked fade: alpha at the eased stops (clear → solid), and the step 1 header's spans. */
    val FADE_STOPS = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)
    val FADE_ALPHAS = intArrayOf(0x00, 0x24, 0x80, 0xDB, 0xFF)
    const val HEADER_FADE_LEFT_TO = 0.45f
    const val HEADER_FADE_BOTTOM_FROM = 0.65f
    /**
     * The banner's picture is sharp (decoded at the size it's shown) and whole; where its box is narrower than the
     * card (Movies, TV Shows, My List) its left edge is eased away over this share of the box (the card's own dark
     * colour over it, by [FADE_ALPHAS]), so it has no edge. The dark shading over it is separate
     * ([HEADER_SHADE_ALPHAS]).
     */
    const val BANNER_FADE_LEFT_TO = 0.40f
    /**
     * The banner's shading, drawn over the picture in the glass style's window colour, in two layers that both ease out
     * to nothing (no flat rectangle, no line where either ends):
     *
     * - Behind the words: an oval of shade round the block at the top left (the logo, the facts, the story), reaching
     *   down the left side behind the buttons. It's strongest ([HEADER_SHADE_MAX]) out to [HEADER_SHADE_HOLD] of its
     *   reach and eased out from there ([headerShadeAlpha]), gone before the picture's subject, so the picture's right
     *   and lower parts show as they are. Its centre and reach are in units of the block (its right end across, its
     *   bottom down: [headerShadeX], [headerShadeY]): the centre above and left of the card ([HEADER_SHADE_CX],
     *   [HEADER_SHADE_CY]), the reach [HEADER_SHADE_RX] across and [HEADER_SHADE_RY] down. So it follows the block on
     *   every card.
     * - Along the bottom, for the dots: rising from [HEADER_SHADE_BOTTOM_FROM] of the height to its strongest at the
     *   bottom edge ([HEADER_SHADE_BOTTOM_ALPHAS], even steps).
     */
    const val HEADER_SHADE_MAX = 0xCC
    const val HEADER_SHADE_HOLD = 0.35f
    const val HEADER_SHADE_CX = -0.25f
    const val HEADER_SHADE_CY = -0.6f
    const val HEADER_SHADE_RX = 1.86f
    const val HEADER_SHADE_RY = 3.76f
    /** The gradient's steps from its centre to its end (even; fine enough that its easing has no corner). */
    const val HEADER_SHADE_STEPS = 20
    val HEADER_SHADE_ALPHAS: IntArray = IntArray(HEADER_SHADE_STEPS + 1) { headerShadeAlpha(it / HEADER_SHADE_STEPS.toFloat()) }
    const val HEADER_SHADE_BOTTOM_FROM = 0.68f
    val HEADER_SHADE_BOTTOM_ALPHAS = intArrayOf(0x00, 0x0D, 0x29, 0x4D)

    // ---- Text styles (one place; Tokens.label makes the views) ----

    val PAGE_NAME = TextStyle(20f, Weight.BOLD)
    val CLOCK = TextStyle(13f, Weight.REGULAR, Ink.MUTED)
    val HEADER_TITLE = TextStyle(HEADER_TITLE_SP, Weight.BOLD)
    val HEADER_TITLE_LONG = TextStyle(HEADER_TITLE_LONG_SP, Weight.BOLD)
    val HEADER_BUTTON = TextStyle(13f, Weight.SEMIBOLD)
    /** The header's facts line (and the title page's): the sizing page's pale blue-grey. */
    val FACTS = TextStyle(HEADER_FACTS_SP, Weight.REGULAR, Ink.OWN, 0xFFC3CFDC.toInt())
    val STORY = TextStyle(HEADER_STORY_SP, Weight.REGULAR, Ink.MUTED, lineGap = 2f)
    /** Row titles: the sizing page's light grey-blue. */
    val ROW_TITLE_STYLE = TextStyle(ROW_TITLE, Weight.BOLD, Ink.OWN, 0xFFE3E9F0.toInt())
    val CHIP = TextStyle(14f, Weight.SEMIBOLD)
    val EMPTY = TextStyle(18f, Weight.SEMIBOLD)
    /** A page's short line where its rows would be (My List with nothing in it, Search with no history yet). */
    val NOTE = TextStyle(15f, Weight.REGULAR, Ink.MUTED)
    val TRY_AGAIN = TextStyle(15f, Weight.SEMIBOLD)
    val SEARCH_KIND = TextStyle(15f, Weight.SEMIBOLD)
    val LIVE_HEAD = TextStyle(16f, Weight.BOLD)
    val LIVE_NAME = TextStyle(13f, Weight.SEMIBOLD)
    val LIVE_MARK = TextStyle(11f, Weight.BOLD, Ink.OWN, 0xFFFFFFFF.toInt())
    val NOTICE = TextStyle(15f, Weight.REGULAR)
    /** A Continue Watching card's name and caption line. */
    val CARD_NAME = TextStyle(15.5f, Weight.BOLD, Ink.OWN, 0xFFFFFFFF.toInt())
    val CARD_CAPTION = TextStyle(12f, Weight.SEMIBOLD, Ink.OWN, 0xFFD6DEE8.toInt())
    val CARD_HOLDER = TextStyle(12.5f, Weight.SEMIBOLD, Ink.MUTED)
    val SEE_ALL_STYLE = TextStyle(14f, Weight.SEMIBOLD)
    val BADGE = TextStyle(10f, Weight.BOLD, Ink.OWN, 0xFFFFFFFF.toInt())
    /** The live picture's strip: the channel's name, the programme after it quieter ([app.fireplayer.ui.Buttons.quietTail]). */
    val PIP_WORDS = TextStyle(12.5f, Weight.SEMIBOLD)
    val RECENT_HEAD = TextStyle(14f, Weight.SEMIBOLD)
    val RECENT_NAME = TextStyle(15f, Weight.SEMIBOLD)
    val RECENT_NOW = TextStyle(12f, Weight.REGULAR, Ink.MUTED)
    val RECENT_EMPTY = TextStyle(13f, Weight.REGULAR, Ink.MUTED)

    // ---- The title page (Design-References/redesign-2026-11-19: concept 3 without its Episodes button) ----

    /** The text block: the title box, the facts row and its callout, the story, the source line (fixed, so words only redraw). */
    const val TITLE_TOP = 30f
    const val TITLE_BOX_W = 560f
    const val TITLE_BOX_H = 84f
    /** The logo fits inside this box (left and bottom aligned in the title box); decoded at most this size, px. */
    const val LOGO_W = 440f
    const val LOGO_DECODE_W = 800
    const val LOGO_DECODE_H = 180
    val TITLE_NAME = TextStyle(40f, Weight.BOLD)
    /** A name too wide for the title box at 40 sp: one line at 28 sp, cut short. */
    val TITLE_NAME_LONG = TextStyle(28f, Weight.BOLD)
    const val FACTS_GAP = 8f
    const val FACTS_ROW_H = 22f
    /**
     * The block reads logo, facts, story, source line, each a step quieter: the facts in the page headers' style
     * ([FACTS]) a size up, the story a size under them (13 sp, the page headers' [STORY]), the source line a small
     * caption.
     */
    val TITLE_FACTS = FACTS.copy(sp = 14f)
    val TITLE_STORY = STORY.copy(sp = 13f)
    val CALLOUT = TextStyle(11.5f, Weight.SEMIBOLD)
    const val CALLOUT_PAD = 9f
    const val CALLOUT_GAP = 10f
    const val STORY_GAP = 6f
    const val STORY_LINES = 2
    /** The story's measure: about 80 letters a line, clear of the picture's subject. */
    const val TITLE_TEXT_W = 480f
    /**
     * The source line: one compact line set apart from the story (a wider gap than between the story's lines and the
     * facts), smaller and quieter than it: the pods' second-line grey ([POD_META]'s).
     */
    const val SOURCE_GAP = 10f
    val SOURCE = TextStyle(11f, Weight.REGULAR, Ink.OWN, 0xFFA9B1BA.toInt())
    /** The text block's height (its lines, and about 8 dp under the source line), and the gap under it. */
    const val TITLE_HEADER_H = 216f
    const val TITLE_HEADER_GAP = 0f

    // Sources: the panel opened from the title page's Sources pill and the player, in the wide settings window (0.44 of
    // the screen, about 360 dp of row inside its padding) with the app's two-line rows.
    /**
     * What a Sources row's two lines have room for, in characters, so neither is ever cut inside a word: the main line
     * (13 sp, beside the marks on the right) and the quieter second line (10.5 sp, over its two lines).
     */
    const val SOURCES_LINE_CHARS = 40
    const val SOURCES_NOTE_CHARS = 80

    // The stage under the text block: the action column on the left; right of it a show's season dropdown, the
    // "Episodes" heading and the episode pods, or a film's More Like This.
    /**
     * The action column: Play / Resume, Play from beginning and Sources (pills as wide as it), then (+) and (⋯). As wide
     * as its widest words need at the buttons' size ("Play from beginning", "Resume · 1h 52m left") beside the icon,
     * with 16 dp or more left at each end ([pillTextW]); words wider than that have a shorter form
     * ([TitleRules.Play.compact]), so nothing is cut.
     */
    const val ACTIONS_W = 176f
    /**
     * A button's height and the gap between two (the cards' gap): four rows of the column (with a resume point) are
     * exactly as tall as an episode pod beside them, 4 x 39.5 + 3 x 14 = 200 ([podH]); whole pixels on the TV (79, 28).
     */
    const val BUTTON_H = 39.5f
    const val BUTTON_GAP = GAP
    /** A button's words: one style for every pill (the banner's too). */
    val BUTTON = TextStyle(13f, Weight.SEMIBOLD)
    /** Only for words no state should have: still wider than the column in their shorter form (a size down, not cut). */
    val BUTTON_LONG = TextStyle(11.5f, Weight.SEMIBOLD)
    /** The side padding of a pill as wide as its words (the banner's Watch now and Details): about half its height. */
    const val BUTTON_PAD = 18f
    /** A column pill's words are centred in it and never come nearer to its ends than this. */
    const val ACTIONS_PAD = 12f
    /** A pill's icon (one size: Play, Play from beginning, Sources; the banner's Watch now) and the gap to its words. */
    const val BUTTON_ICON = 14f
    const val BUTTON_ICON_GAP = 7f
    const val CIRCLE_ICON = 18f
    /**
     * A resuming pill's progress line: its height, how far it is above the pill's bottom (the words' line then has
     * about as much room under it as the line has under itself), and from the pill's ends (in the pill's straight
     * part: its ends are round, half its height in radius).
     */
    const val PILL_PROGRESS_H = 2f
    const val PILL_PROGRESS_BOTTOM = 5f
    const val PILL_PROGRESS_SIDE = 20f
    /** The circles' names hang under them while one is chosen: their room under the column. */
    const val CIRCLE_NAME_ROOM = 20f
    /** From the column to what's right of it. */
    const val STAGE_GAP = 24f
    /** The first button's top in the stage; the pods (and a film's posters) start level with it. */
    const val STAGE_ROW_TOP = 58f
    /**
     * The season dropdown and the heading beside it: their top in the stage (the source line is then as far above them
     * as the pods are below), their height, the gap between them.
     */
    const val SEASON_TOP = 8f
    const val SEASON_H = 34f
    val SEASON = TextStyle(13.5f, Weight.SEMIBOLD)
    const val SEASON_PAD = 16f
    const val SEASON_ICON = 14f
    const val SEASON_ICON_GAP = 8f
    const val SEASON_HEADING_GAP = 14f
    /** "Episodes": a heading (never a button), the row titles' colour. */
    val EPISODES_HEADING = ROW_TITLE_STYLE.copy(sp = 19f)

    // The season dropdown's list: a small glass window under the dropdown; more seasons than fit scroll inside it.
    const val SEASON_LIST_W = 176f
    const val SEASON_LIST_PAD = 8f
    const val SEASON_LIST_ROW_H = 34f
    const val SEASON_LIST_ROW_GAP = 4f
    const val SEASON_LIST_ROWS = 6
    const val SEASON_LIST_GAP = 6f
    const val SEASON_LIST_INSET = 12f
    const val SEASON_LIST_MS = 120L

    // Episode pods: a 16:9 still over an information base ("E1 · A Shadow of the Past", "1h 7m · Sep 1, 2022"), about
    // two and part of a third across beside the column; the chosen one grows a little, with the cards' white ring.
    const val POD_W = 264f
    const val POD_PICTURE_H = 148f
    const val POD_BASE_H = 52f
    const val POD_GAP = GAP
    const val POD_GROW = 0.06f
    const val POD_TEXT_SIDE = 12f
    const val POD_TEXT_TOP = 9f
    const val POD_LINE_GAP = 3f
    val POD_TITLE = TextStyle(13.5f, Weight.BOLD, Ink.OWN, 0xFFFFFFFF.toInt())
    val POD_META = TextStyle(12f, Weight.REGULAR, Ink.OWN, 0xFFA9B1BA.toInt())
    /** Room above and below the pods for the chosen one's growth and its ring. */
    const val POD_ROOM = 10f
    /**
     * Under that room, before a show's More Like This: its title then shows above the screen's bottom (a hint) while
     * its posters start just below it (so they hold no pictures until the page glides up).
     */
    const val STAGE_BOTTOM = 20f
    /** Pictures bound after the chosen pod (one fewer with focus in More Like This); posters (6 on a show). */
    const val POD_BIND_AFTER = 3
    const val POSTER_BIND_AFTER = 7

    // Episode cards as the rows draw them (CardView's EPISODE kind): a 16:9 still, two caption lines under it.
    const val EPISODE_ITEM_H = 138f
    const val EPISODE_CAPTION_GAP = 6f
    const val EPISODE_LINE_GAP = 2f
    val EPISODE_TITLE = TextStyle(11.5f, Weight.SEMIBOLD)
    /** "E5" in the Episodes sheet's blue, the line under it in its grey. */
    const val EPISODE_NUMBER = 0xFF8FB8FF.toInt()
    val EPISODE_META = TextStyle(11.5f, Weight.REGULAR, Ink.OWN, 0xFF9BA1A8.toInt())
    /** The ✓ watched badge. */
    const val WATCHED_MARK = "✓"
    const val NEW_BADGE = "NEW"

    /** Gliding up on a show keeps the season dropdown's row this far from the top (no cut line of text above it). */
    const val GLIDE_KEEP_TOP = 14f
    /** The backdrop: decoded at half the screen (px), the preview smaller; its crossfade. */
    const val TITLE_ART_W = 960
    const val TITLE_ART_H = 540
    const val TITLE_PREVIEW_W = 320
    const val TITLE_PREVIEW_H = 180
    const val TITLE_ART_FADE_MS = Tokens.FADE_MS
    /** The baked fade (fractions of the backdrop): clear → solid from the left, solid → clear towards the bottom. */
    const val TITLE_FADE_LEFT_FROM = 0.15f
    const val TITLE_FADE_LEFT_TO = 0.70f
    const val TITLE_FADE_BOTTOM_FROM = 0.36f
    const val TITLE_FADE_BOTTOM_TO = 0.60f
    /** The logo is used if it's ready this soon after opening; otherwise the name, for the whole visit. */
    const val LOGO_WAIT_MS = 300L
    /** The header's follow delay once focus rests on an episode (the Episodes sheet's 160 ms). */
    const val SETTLE_MS = 160L
    const val MLT_CARDS = 15
    /**
     * A long season's episode row (a daily show's 200) holds its pods this many at a time, and this many more once
     * focus comes within [EPISODE_AHEAD] of the last one held ([episodeWindow]).
     */
    const val EPISODE_WINDOW = 40
    const val EPISODE_AHEAD = 10

    // ---- Streaming services: the logo cards, and a service page's wordmark in its name line ----

    /** Logo cards across the page: all five show at once and stay where they are (the row never moves sideways). */
    const val LOGO_ACROSS = 5
    /**
     * How much bigger the chosen logo card is. It grows about its middle, so less than [GROW]: its growth and its ring
     * stay inside the [GAP] to the cards beside it.
     */
    const val LOGO_GROW = 0.10f
    /** A logo card's height for its width. */
    const val LOGO_SHAPE = 0.5f
    /** How much of a logo card's width and height its wordmark may take. */
    const val LOGO_FILL_W = 0.66f
    const val LOGO_FILL_H = 0.5f
    /**
     * The studio logos (res/drawable-nodpi/studio_*.png) are drawn for a card this many of their pixels wide: each
     * keeps its size against the card's width, so the five sit together as in the reference.
     */
    const val STUDIO_LOGO_CARD_PX = 258f
    /** A one-colour wordmark is drawn in this, whatever its own colour (TMDB's are often black). */
    const val LOGO_MONO = 0xFFFFFFFF.toInt()
    /** A service page's wordmark in the name line: at most this size, its bottom on the clock's baseline. */
    const val NAME_LOGO_H = 26f
    const val NAME_LOGO_W = 120f
    const val COULDNT_OPEN = "Couldn't load this title"
    /** A logo card's name, shown until its wordmark comes (and for a brand TMDB has no wordmark for). */
    val LOGO_NAME = TextStyle(16f, Weight.BOLD)

    /** A logo card's width: [LOGO_ACROSS] share the rows' width (the content's left edge to the right margin), [GAP] apart. */
    fun logoW(): Float = (tabHeaderW() - (LOGO_ACROSS - 1) * GAP) / LOGO_ACROSS

    /**
     * A row whose cards stay where they are: all of them show at once, Left and Right only move focus, and the chosen
     * one grows about its middle with nothing moving along for it.
     */
    fun still(style: RowStyle): Boolean = style == RowStyle.LOGO

    // ---- Per row style ----
    // [large]: the browse pages' cards ([BROWSE_POSTER_W], [BROWSE_WIDE_W]); else the title page's.

    fun posterW(large: Boolean): Float = if (large) BROWSE_POSTER_W else POSTER_W
    fun posterH(large: Boolean): Float = if (large) BROWSE_POSTER_H else POSTER_H

    fun cardW(style: RowStyle, large: Boolean = false): Float = when {
        style == RowStyle.LOGO -> logoW()
        style == RowStyle.EPISODE -> WIDE_W
        style == RowStyle.WIDE -> if (large) BROWSE_WIDE_W else WIDE_W
        else -> posterW(large)
    }

    /** An item's height: an episode's is its still and its two caption lines. */
    fun cardH(style: RowStyle, large: Boolean = false): Float = when (style) {
        RowStyle.WIDE -> if (large) BROWSE_WIDE_H else WIDE_H
        RowStyle.EPISODE -> EPISODE_ITEM_H
        RowStyle.GENRES, RowStyle.SEASONS -> CHIP_H
        RowStyle.LOGO -> logoW() * LOGO_SHAPE
        else -> posterH(large)
    }

    /** The picture's height in an item (an episode's still; the whole card elsewhere). */
    fun pictureH(style: RowStyle, large: Boolean = false): Float = if (style == RowStyle.EPISODE) WIDE_H else cardH(style, large)

    /** Whether a row's items are chips laid out at their own widths. */
    fun isChips(style: RowStyle): Boolean = style == RowStyle.GENRES || style == RowStyle.SEASONS

    /** The rank number's slot left of a Top 10 poster; none elsewhere. */
    fun slot(style: RowStyle, large: Boolean = false): Float = if (style == RowStyle.RANKED) posterH(large) * RANK_SLOT else 0f

    /** One item across: the slot and the card. */
    fun itemW(style: RowStyle, large: Boolean = false): Float = slot(style, large) + cardW(style, large)

    /** From one item's left edge to the next one's. */
    fun pitch(style: RowStyle, large: Boolean = false): Float = itemW(style, large) + GAP

    fun grow(style: RowStyle): Float = when {
        isChips(style) -> GROW_CHIP
        style == RowStyle.LOGO -> LOGO_GROW
        else -> GROW
    }

    /** How far the cards after the chosen one move along to make room for it as it grows (none in a [still] row). */
    fun extra(style: RowStyle, large: Boolean = false): Float = if (still(style)) 0f else cardW(style, large) * grow(style)

    /** Where an item grows from (its left edge and this height): the middle of its picture. */
    fun pivotY(style: RowStyle, large: Boolean = false): Float = pictureH(style, large) / 2f

    /** The item's top in the strip: room for its growth above the pivot. */
    fun cardTop(style: RowStyle, large: Boolean = false): Float = pivotY(style, large) * grow(style)

    /** The strip of cards: as tall as a grown item, which grows about [pivotY] (a card: centred in it). */
    fun stripH(style: RowStyle, large: Boolean = false): Float =
        cardTop(style, large) + cardH(style, large) + (cardH(style, large) - pivotY(style, large)) * grow(style)
    fun stripTop(): Float = ROW_TITLE + ROW_TITLE_GAP
    /** The strip's top in its row: under the title, or (a row without one) under room for the chosen card's ring. */
    fun stripTop(header: Boolean): Float = if (header) stripTop() else RING + 1f

    /** A row's band: title, gap, strip and the gap under it. */
    fun bandH(style: RowStyle): Float = bandH(style, header = true, gapBelow = ROW_GAP)

    /** A row's band with or without its title line ([header]), and [gapBelow] under it. */
    fun bandH(style: RowStyle, header: Boolean, gapBelow: Float, large: Boolean = false): Float =
        stripTop(header) + stripH(style, large) + gapBelow

    /** What a chosen row needs to show whole: its title, its strip (a grown card) and the card's ring just outside it. */
    fun rowShownH(style: RowStyle, header: Boolean, large: Boolean): Float =
        stripTop(header) + stripH(style, large) + RING + 1f

    /**
     * How many cards after the chosen one hold pictures in a row: those that show (the chosen one grown at the
     * content's left edge) and one ahead; never more than [most].
     */
    fun boundAfter(style: RowStyle, large: Boolean, most: Int = 8): Int {
        val room = SCREEN_W - CONTENT_SCREEN_X - itemW(style, large) - extra(style, large) - GAP
        return (ceil(room / pitch(style, large)).toInt() + 1).coerceIn(1, most)
    }

    /** Home's header card: from [HOME_HEADER_X] to the window. */
    fun homeHeaderW(): Float = homeHeaderW(pip = false)
    /** Movies' and TV Shows' header card: the content's width. */
    fun tabHeaderW(): Float = SCREEN_W - CONTENT_SCREEN_X - RIGHT_MARGIN
    fun windowX(): Float = windowX(pip = false)

    /** Home's top-right window: Recent channels ([WINDOW_W]), or the live picture's 16:9 one ([PIP_WINDOW_W]). */
    fun windowW(pip: Boolean): Float = if (pip) PIP_WINDOW_W else WINDOW_W
    /** Home's banner beside Recent channels (560 dp) or beside the live picture's window (445). */
    fun homeHeaderW(pip: Boolean): Float = SCREEN_W - HOME_HEADER_SCREEN_X - RIGHT_MARGIN - WINDOW_GAP - windowW(pip)
    /** The window's left edge (page-local): [WINDOW_GAP] past the banner. Its right edge is the same either way. */
    fun windowX(pip: Boolean): Float = HOME_HEADER_X + homeHeaderW(pip) + WINDOW_GAP

    /**
     * Home's top right [p] of the way from Recent channels' layout (0) to the live picture's (1), as the two change
     * places in one motion: the banner's right edge (page-local) and the window's left edge, which keep the
     * [WINDOW_GAP] between them all the way; the banner's left edge and the window's right edge never move.
     */
    fun homeHeaderRight(p: Float): Float = HOME_HEADER_X + mix(homeHeaderW(pip = false), homeHeaderW(pip = true), p)
    fun homeWindowLeft(p: Float): Float = homeHeaderRight(p) + WINDOW_GAP

    /** [a] moved [f] of the way to [b]. */
    fun mix(a: Float, b: Float, f: Float): Float = a + (b - a) * f

    /** Home's banner beside the live picture: narrower than any other page's banner. */
    fun beside(cardW: Float): Boolean = cardW < homeHeaderW()

    /** The first row's top in the page's column (the header above it). */
    fun firstRowTop(): Float = HEADER_H + HEADER_GAP

    /** The picture's box in a banner [cardW] wide: Home's ([HOME_BACKDROP_ASPECT]), or the wide cards' ([BACKDROP_W]). */
    fun backdropW(cardW: Float): Float =
        min(cardW, if (cardW <= homeHeaderW()) HOME_BACKDROP_ASPECT * HEADER_H else BACKDROP_W)

    /**
     * Where the picture is seen clear in a banner [cardW] wide: past its eased left edge ([BANNER_FADE_LEFT_TO]); 0
     * where it fills the card.
     */
    fun pictureClearX(cardW: Float): Float {
        val w = backdropW(cardW)
        return if (w >= cardW) 0f else cardW - w + w * BANNER_FADE_LEFT_TO
    }

    /**
     * The banner's words' column (the title's box, the facts, the story): kept clear of the picture's subject, and
     * where the picture is in a box, on the dark side and its eased edge ([pictureClearX]).
     */
    fun storyW(cardW: Float): Float {
        val clear = pictureClearX(cardW)
        val w = min(cardW - HEADER_INSET - HEADER_CLEAR, HEADER_STORY_MAX)
        // Beside the live picture the dark side is too narrow to hold them (40 dp): the words lie over the picture's
        // eased edge, on the shade, and end as far from the card's right edge as on every banner (249 dp).
        if (beside(cardW)) return w
        return if (clear > 0f) min(w, clear - HEADER_INSET) else w
    }

    /**
     * The room a banner's logo has across (its box is [HEADER_TITLE_H] tall). Beside the live picture it's Home's own
     * (205 dp, inside the 249 dp words): the logo isn't decoded again as the picture comes and goes.
     */
    fun headerLogoW(cardW: Float): Float =
        if (beside(cardW)) headerLogoW(homeHeaderW()) else min(storyW(cardW) * HEADER_LOGO_SHARE, HEADER_LOGO_MAX)

    /**
     * The rotation's dots' box, its left edge in a banner [cardW] wide (the dots are centred in it): centred in the
     * card, or beside the live picture, at its bottom right under the clear picture, clear of the buttons.
     */
    fun headerDotsX(cardW: Float): Float = if (beside(cardW)) cardW - HEADER_INSET - DOTS_W else (cardW - DOTS_W) / 2f

    /** The story's box: its lines with their gaps (Figtree's line is 1.2 x its size). */
    fun headerStoryH(): Float = HEADER_STORY_LINES * (STORY.lineSp + STORY.lineGap)

    /** The words' block: the title's box, the facts line and the story, with the gaps between. */
    fun headerWordsH(): Float = HEADER_TITLE_H + HEADER_FACTS_GAP + FACTS.lineSp + HEADER_STORY_GAP + headerStoryH()

    /** Where the words end (the story's last line), down from the card's top. */
    fun headerWordsEnd(): Float = HEADER_TOP + headerWordsH()

    /** The buttons' top, down from the top of a card [cardH] tall. */
    fun headerButtonsTop(cardH: Float = HEADER_H): Float = cardH - HEADER_BUTTONS_BOTTOM - HEADER_BUTTON_H

    /**
     * How far down the words move while the page hides the top [hidden] dp of the card: enough to stay
     * [HEADER_TOP_KEEP] under the cut, never closer than [HEADER_WORDS_MIN_GAP] to the buttons.
     */
    fun headerWordsShift(hidden: Float): Float {
        val most = (headerButtonsTop() - HEADER_WORDS_MIN_GAP - headerWordsEnd()).coerceAtLeast(0f)
        return (hidden - (HEADER_TOP - HEADER_TOP_KEEP)).coerceIn(0f, most)
    }

    /** The banner's name: [HEADER_TITLE_SP], or [HEADER_TITLE_LONG_SP] for a name [measured] wider than [room] at that size. */
    fun headerTitleSp(measured: Float, room: Float): Float = if (measured > room) HEADER_TITLE_LONG_SP else HEADER_TITLE_SP

    /**
     * The facts line as the banner shows it: as many of [line]'s facts (" · " between them), from the first, as are
     * no wider than [room] by [measure]; never a fact cut short. The first always stays.
     */
    fun headerFacts(line: String, room: Float, measure: (String) -> Float): String {
        if (measure(line) <= room) return line
        val parts = line.split(FACTS_BETWEEN)
        for (n in parts.size - 1 downTo 2) {
            val shorter = parts.subList(0, n).joinToString(FACTS_BETWEEN)
            if (measure(shorter) <= room) return shorter
        }
        return parts.first()
    }
    const val FACTS_BETWEEN = " · "

    /** The shade's strength (0..255) [t] of the way from its centre (0) to its end (1): held, then eased both ways. */
    fun headerShadeAlpha(t: Float): Int {
        val s = ((t - HEADER_SHADE_HOLD) / (1f - HEADER_SHADE_HOLD)).coerceIn(0f, 1f)
        return (HEADER_SHADE_MAX * (1f - s * s * (3f - 2f * s))).roundToInt()
    }

    /** The words' block the shade is drawn round, dp from the card's top left: its right end, its bottom. */
    fun headerShadeX(cardW: Float): Float = HEADER_INSET + storyW(cardW)
    fun headerShadeY(): Float = headerWordsEnd()

    /** How far out of the shade a point of the card is (dp from its top left): 0 at the centre, 1 where it ends. */
    fun headerShadeAt(x: Float, y: Float, cardW: Float): Float {
        val bx = headerShadeX(cardW)
        val by = headerShadeY()
        val dx = (x - HEADER_SHADE_CX * bx) / (HEADER_SHADE_RX * bx)
        val dy = (y - HEADER_SHADE_CY * by) / (HEADER_SHADE_RY * by)
        return sqrt(dx * dx + dy * dy)
    }

    /** The shade's strength there, 0..1, as the gradient draws it (straight between its steps). */
    fun headerShade(x: Float, y: Float, cardW: Float): Float {
        val t = headerShadeAt(x, y, cardW).coerceIn(0f, 1f) * HEADER_SHADE_STEPS
        val i = t.toInt().coerceAtMost(HEADER_SHADE_STEPS - 1)
        return (HEADER_SHADE_ALPHAS[i] + (HEADER_SHADE_ALPHAS[i + 1] - HEADER_SHADE_ALPHAS[i]) * (t - i)) / 255f
    }

    /** The grid: 5 posters across the content's width, rows 30 dp apart. */
    fun gridW(): Float = GRID_COLUMNS * GRID_POSTER_W + (GRID_COLUMNS - 1) * GRID_GAP
    fun gridPitchX(): Float = GRID_POSTER_W + GRID_GAP
    fun gridPitchY(): Float = GRID_POSTER_H + GRID_ROW_GAP

    /** Recent channels: the list's height (the rows that show). */
    fun recentListH(): Float = RECENT_ROWS_SHOWN * RECENT_ROW_H

    /**
     * Recent channels: the first row shown with row [at] of [n] chosen and row [first] the first shown until now. The
     * list moves only as far as the chosen row needs to show, and never past its ends.
     */
    fun recentFirstShown(at: Int, first: Int, n: Int, shown: Int = RECENT_ROWS_SHOWN): Int {
        val f = when {
            at < first -> at
            at > first + shown - 1 -> at - shown + 1
            else -> first
        }
        return f.coerceIn(0, (n - shown).coerceAtLeast(0))
    }

    // ---- The title page's layout (the page reads real view positions; these say where they come out) ----

    /** The text block's and the action column's left edge in the page: the content's x ([PAGE_X]). */
    fun titleTextX(): Float = PAGE_X

    /** The facts row's top: under the title box. */
    fun titleFactsTop(): Float = TITLE_TOP + TITLE_BOX_H + FACTS_GAP

    /** The story's top and height: two 13 sp lines with their 2 dp gaps (Figtree's line is 1.2 x its size). */
    fun titleStoryTop(): Float = titleFactsTop() + FACTS_ROW_H + STORY_GAP
    fun titleStoryH(): Float = STORY_LINES * (TITLE_STORY.lineSp + TITLE_STORY.lineGap)

    fun titleSourceTop(): Float = titleStoryTop() + titleStoryH() + SOURCE_GAP

    /** Where the stage's right part starts in the page (pods, the season dropdown, a film's posters). */
    fun stageX(): Float = PAGE_X + ACTIONS_W + STAGE_GAP

    /** The room for a pill's words in the column: inside [ACTIONS_PAD] at each end, after its icon when it has one. */
    fun pillTextW(icon: Boolean): Float = ACTIONS_W - 2 * ACTIONS_PAD - if (icon) BUTTON_ICON + BUTTON_ICON_GAP else 0f

    /** The action column with all it can hold: three pills and the circles, [BUTTON_GAP] apart. */
    fun actionsH(): Float = 4 * BUTTON_H + 3 * BUTTON_GAP

    /** A pod: its still and its base; from one pod's left edge to the next one's; how far the next ones make room. */
    fun podH(): Float = POD_PICTURE_H + POD_BASE_H
    fun podPitch(): Float = POD_W + POD_GAP
    fun podExtra(): Float = POD_W * POD_GROW

    /** The pods' band in the stage: the pods and [POD_ROOM] above and below them. */
    fun podsBandH(): Float = podH() + 2 * POD_ROOM

    /** A show's stage: down to the room under the pods, then [STAGE_BOTTOM] (More Like This starts under it). */
    fun showStageH(): Float = STAGE_ROW_TOP + podH() + POD_ROOM + STAGE_BOTTOM

    /** A film's More Like This in the stage: its band's top, so its posters start level with the first button. */
    fun filmMoreTop(): Float = (STAGE_ROW_TOP - stripTop() - cardTop(RowStyle.POSTER)).coerceAtLeast(0f)

    /** A film's stage: its More Like This band, or the whole action column and the circles' names if that's taller. */
    fun filmStageH(): Float = maxOf(filmMoreTop() + bandH(RowStyle.POSTER), STAGE_ROW_TOP + actionsH() + CIRCLE_NAME_ROOM)

    /** The season list's height with [seasons] in it: at most [SEASON_LIST_ROWS] rows show, the rest scroll. */
    fun seasonListH(seasons: Int): Float {
        val rows = seasons.coerceIn(1, SEASON_LIST_ROWS)
        return 2 * SEASON_LIST_PAD + rows * SEASON_LIST_ROW_H + (rows - 1) * SEASON_LIST_ROW_GAP
    }

    /**
     * The first row showing in the season list with row [at] chosen, [first] showing first now and [count] rows in
     * all: it moves only as far as [at] needs to be inside the [SEASON_LIST_ROWS] that show.
     */
    fun seasonListFirst(at: Int, first: Int, count: Int): Int {
        val last = (count - SEASON_LIST_ROWS).coerceAtLeast(0)
        val f = when {
            at < first -> at
            at >= first + SEASON_LIST_ROWS -> at - SEASON_LIST_ROWS + 1
            else -> first
        }
        return f.coerceIn(0, last)
    }

    /**
     * How many of a season's [total] episode pods the row holds with pod [at] chosen and [have] held now: never fewer
     * than [have], at least [EPISODE_WINDOW], and [EPISODE_WINDOW] more while [at] is within [EPISODE_AHEAD] of the end
     * (so a held D-pad adds pods in steps, not one per press); never more than [total].
     */
    fun episodeWindow(total: Int, at: Int, have: Int): Int {
        var n = maxOf(have, EPISODE_WINDOW)
        while (at + EPISODE_AHEAD >= n && n < total) n += EPISODE_WINDOW
        return minOf(n, total).coerceAtLeast(0)
    }

    /**
     * How far a show's page glides up for More Like This: enough for its band to end on screen, and never less than
     * keeping the season dropdown's row ([rowTop]) [GLIDE_KEEP_TOP] from the top (all the text above it leaves the
     * screen).
     */
    fun glide(moreBottom: Float, rowTop: Float, pageH: Float = SCREEN_H, keepTop: Float = GLIDE_KEEP_TOP): Float =
        maxOf(moreBottom - pageH, rowTop - keepTop, 0f)
}
