package app.fireplayer.titles

/**
 * The picture quality a provider's name or category gives away, as the tag after a renamed title: "The Matrix (1999)
 * [4K]". Only what the provider wrote is read; the video itself is never opened to find out.
 */
object Quality {

    // Highest first: a name with both "4K" and "1080p" is 4K. Short words only in capitals, so "Hd" or "sd" in a
    // title aren't taken for tags; numbers anywhere, as in "1080pEN60fps".
    private val LEVELS = listOf(
        "4K" to Regex("""(?i)(?<![A-Za-z0-9])(?:4k|uhd|2160p?)(?![A-Za-z0-9])"""),
        "1080p" to Regex("""(?<![A-Za-z0-9])(?:FHD|[Ff]hd|1080[pPiI]?)(?![0-9])"""),
        "720p" to Regex("""(?<![A-Za-z0-9])720[pP]?(?![0-9])"""),
        "HD" to Regex("""(?<![A-Za-z0-9])HD(?![A-Za-z0-9])"""),
        "SD" to Regex("""(?<![A-Za-z0-9])(?:SD|480[pP]?|576[pP]?)(?![A-Za-z0-9])"""),
    )
    private val HDR = Regex("""(?i)(?<![A-Za-z0-9])(?:hdr(?:10)?\+?|dolby ?vision)(?![A-Za-z0-9])""")

    /** "4K", "4K HDR", "1080p", "720p", "HD" or "SD" from [name], else from its [category]; null when neither says. */
    fun of(name: String, category: String?): String? = tag(name) ?: category?.let { tag(it) }

    private fun tag(text: String): String? {
        val level = LEVELS.firstOrNull { it.second.containsMatchIn(text) }?.first ?: return null
        return if (HDR.containsMatchIn(text)) "$level HDR" else level
    }
}
