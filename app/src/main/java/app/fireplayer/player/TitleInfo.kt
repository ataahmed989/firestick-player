package app.fireplayer.player

import android.net.Uri

/**
 * Turns whatever name TiviMate (or a file name) gives us into
 * "Movie (2024)" or "Show" + "S1 · E4 · Episode".
 */
data class TitleInfo(
    val title: String,
    val subtitle: String?,
    val searchQuery: String,
    val season: Int?,
    val episode: Int?,
) {
    companion object {
        private val episodeRegex = Regex("""(?i)\bS(\d{1,2})\s*[ ._-]?\s*E(\d{1,3})\b""")
        private val altEpisodeRegex = Regex("""(?i)\b(\d{1,2})x(\d{2,3})\b""")
        private val yearRegex = Regex("""[(\[]?\b((?:19|20)\d{2})\b[)\]]?""")
        private val junkRegex = Regex(
            """(?i)\b(2160p|1080p|720p|480p|4k|uhd|hdr10?\+?|dv|web-?dl|webrip|bluray|brrip|remux|x26[45]|h\.?26[45]|hevc|aac|ac3|eac3|ddp?5\.1|dts|atmos|10bit|proper|repack)\b.*$""",
        )

        fun parse(rawTitle: String?, uri: Uri?): TitleInfo {
            val source = rawTitle?.takeIf { it.isNotBlank() } ?: nameFromUri(uri)
            val name = source.trim()

            val ep = episodeRegex.find(name) ?: altEpisodeRegex.find(name)
            if (ep != null) {
                val show = clean(name.substring(0, ep.range.first))
                val after = clean(junkRegex.replace(name.substring(ep.range.last + 1), ""))
                val season = ep.groupValues[1].toInt()
                val episode = ep.groupValues[2].toInt()
                val sub = buildString {
                    append("S").append(season).append(" · E").append(episode)
                    if (after.isNotEmpty()) append(" · ").append(after)
                }
                val showTitle = show.ifEmpty { name }
                return TitleInfo(showTitle, sub, stripYear(showTitle), season, episode)
            }

            val withoutJunk = junkRegex.replace(name, "")
            val year = yearRegex.findAll(withoutJunk).lastOrNull()
            if (year != null && year.range.first > 0) {
                val title = clean(withoutJunk.substring(0, year.range.first))
                if (title.isNotEmpty()) {
                    return TitleInfo("$title (${year.groupValues[1]})", null, title, null, null)
                }
            }
            val title = clean(withoutJunk).ifEmpty { name }
            return TitleInfo(title, null, title, null, null)
        }

        private fun nameFromUri(uri: Uri?): String {
            val last = uri?.lastPathSegment ?: return "Video"
            val noExt = last.substringBeforeLast('.', last)
            // IPTV links end in a number (…/series/user/pass/12345.mkv); that isn't a useful title.
            return if (noExt.all { it.isDigit() }) "Video" else noExt
        }

        private fun stripYear(s: String) = clean(yearRegex.replace(s, ""))

        private fun clean(s: String): String =
            s.replace('.', ' ').replace('_', ' ')
                .replace(Regex("""\s+"""), " ")
                .trim(' ', '-', '–', '|', ':', '(', '[')
                .trim()
    }
}
