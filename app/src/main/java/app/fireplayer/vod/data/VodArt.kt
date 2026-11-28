package app.fireplayer.vod.data

/**
 * Picture addresses for titles, at the sizes chosen for the 960 x 540 dp screen (1 dp = 2 px at 1080p):
 * - posters: metahub `poster/small` (300 x 450; a 121 x 162 dp card grown 16% is 282 x 376 px);
 * - [app.fireplayer.vod.Title.background]: the header's backdrop, metahub `background/medium` (1280 or 1920 wide),
 *   or TMDB `w1280` for titles from TMDB's trending lists (about 160 KB instead of up to 1 MB);
 * - wide cards (Continue Watching, 182 x 102 dp, 422 x 238 px grown): [backgroundAt] with [Size.SMALL], which is metahub
 *   `background/small` (480 or 780 wide) or TMDB `w780`.
 *
 * Metahub answers any size name other than small, medium and large with the original picture (a 1.25 MB poster), so
 * only those are ever written.
 */
object VodArt {

    const val METAHUB = "https://images.metahub.space"
    const val TMDB_IMAGES = "https://image.tmdb.org/t/p"

    /** A backdrop size: metahub's name for it and TMDB's width. */
    enum class Size(val metahub: String, val tmdb: String) {
        /** Wide cards. */
        SMALL("small", "w780"),
        /** The header. */
        MEDIUM("medium", "w1280"),
    }

    fun poster(id: String) = "$METAHUB/poster/small/$id/img"
    fun background(id: String) = "$METAHUB/background/medium/$id/img"
    fun logo(id: String) = "$METAHUB/logo/medium/$id/img"

    /** A TMDB backdrop ("/abc.jpg") at the header's size. */
    fun tmdbBackdrop(path: String) = "$TMDB_IMAGES/${Size.MEDIUM.tmdb}$path"

    /**
     * TMDB's poster size for a More Like This card whose IMDb id isn't known here (the brief's `w185`, 185 x 278); once
     * the IMDb id is known the card uses [poster], metahub's, like every row (one poster per IMDb id).
     */
    const val TMDB_POSTER = "w185"

    /** A TMDB poster ("/abc.jpg") at [TMDB_POSTER]. */
    fun tmdbPoster(path: String) = "$TMDB_IMAGES/$TMDB_POSTER$path"

    /** A cast member's TMDB portrait ("/abc.jpg") at `w185` (185 x 278), for Details' small round photos. */
    fun tmdbProfile(path: String) = "$TMDB_IMAGES/w185$path"

    /**
     * A title's [background] at [size]: metahub's size name or TMDB's width swapped in. Any other address is given back
     * as it is, and null stays null.
     */
    fun backgroundAt(background: String?, size: Size): String? {
        if (background == null) return null
        METAHUB_ART.matchEntire(background)?.let { m ->
            if (m.groupValues[2] != "background") return background
            return "${m.groupValues[1]}/background/${size.metahub}/${m.groupValues[4]}"
        }
        TMDB_ART.matchEntire(background)?.let { m -> return "$TMDB_IMAGES/${size.tmdb}${m.groupValues[2]}" }
        return background
    }

    /**
     * Cinemeta's picture address with a size metahub knows: posters at small, backgrounds and logos at medium, whatever
     * size name came in. Other addresses as they are.
     */
    internal fun normalized(url: String?): String? {
        if (url == null) return null
        val m = METAHUB_ART.matchEntire(url) ?: return url
        val size = when (m.groupValues[2]) {
            "poster" -> "small"
            else -> "medium"
        }
        if (m.groupValues[3] == size) return url
        return "${m.groupValues[1]}/${m.groupValues[2]}/$size/${m.groupValues[4]}"
    }

    // https://images.metahub.space/poster/small/tt123/img, also on live.metahub.space after a redirect.
    private val METAHUB_ART = Regex("""(https?://(?:images|live)\.metahub\.space)/(poster|background|logo)/([A-Za-z0-9_-]+)/(.+)""")
    private val TMDB_ART = Regex("""https?://image\.tmdb\.org/t/p/([a-z0-9]+)(/.+)""")
}
