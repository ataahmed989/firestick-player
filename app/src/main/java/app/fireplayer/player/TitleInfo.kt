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
        private val bracketYearRegex = Regex("""[(\[]((?:19|20)\d{2})[)\]]""")

        // Needs a space before it, so a tag at the very start can't wipe out the whole title.
        private val junkRegex = Regex(
            """(?i)\s\b(2160p|1080p|720p|480p|4k|uhd|hdr10?\+?|dv|web-?dl|webrip|bluray|brrip|remux|x26[45]|h\.?26[45]|hevc|aac|ac3|eac3|ddp?5\.1|dts|atmos|10bit|proper|repack)\b.*$""",
        )

        // IPTV provider prefixes such as "EN - ", "EN | ", "4K-NF - ", "[EN] ".
        private val prefixRegex = Regex("""^\s*(?:\[[^\]]{1,12}]\s*|\|?(?=[A-Z0-9]{0,3}[A-Z])[A-Z0-9]{2,4}(?:[-+][A-Z0-9]{2,5})?\|?\s*[-|:–]\s+)""")

        fun parse(rawTitle: String?, uri: Uri?): TitleInfo {
            val source = rawTitle?.takeIf { it.isNotBlank() } ?: nameFromUri(uri)
            val name = prefixRegex.replace(source.trim(), "").ifBlank { source.trim() }

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
            // In a proper title ("Blade Runner 2049") a bare number isn't a year; only "(2017)" is.
            // File names ("Dune.Part.Two.2024.2160p") have no spaces and use bare years.
            val year = (if (' ' in name) bracketYearRegex else yearRegex).findAll(withoutJunk).lastOrNull()
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

        private fun stripYear(s: String) = clean(bracketYearRegex.replace(s, ""))

        private fun clean(s: String): String =
            // Dots and underscores are separators only in file names; "Mr. Robot" keeps its dot.
            (if (' ' in s.trim()) s else s.replace('.', ' ').replace('_', ' '))
                .replace(Regex("""\s+"""), " ")
                .trim(' ', '-', '–', '|', ':', '(', '[')
                .trim()
    }
}
