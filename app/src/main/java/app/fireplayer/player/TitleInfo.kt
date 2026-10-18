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

        // Release tags and everything after them. Needs a space before it, so the title itself survives.
        private const val JUNK = """2160p|1080p|720p|480p|4k|uhd|hdr10?\+?|web-?dl|webrip|bluray|brrip|remux|x26[45]|""" +
            """h\.?26[45]|hevc|aac|e?ac3|ddp?5\.1|dts|atmos|10bit"""
        private val junkRegex = Regex("""(?i)\s\b($JUNK)\b.*$""")

        // File names also carry words that could be part of a real title elsewhere.
        private val fileJunkRegex = Regex("""(?i)\s\b($JUNK|dv|proper|repack|internal|hdtv|amzn|nf|web)\b.*$""")

        // Single letters with dots, such as "S.W.A.T." or "E.T.", keep their dots in file names.
        private val dotsRegex = Regex("""((?<![A-Za-z0-9])(?:[A-Za-z]\.){2,})|[._]""")

        // One tag at the start: "[EN]", "|EN|" or a short code such as "EN", "4K-NF", followed by a separator.
        private val leadRegex = Regex("""^(\[[^\]]{1,20}]|\|[^|]{1,20}\||[A-Za-z0-9+]{2,6}(?:-[A-Za-z0-9+]{2,6})?)(\s*[-|:–]\s*|\s+|(?<=[\]|]))""")

        /**
         * Language, region, service and quality tags providers put in front of names. Only these are
         * removed, so titles that start with a short word in capitals ("NCIS: Los Angeles", "TRON: Legacy") stay whole.
         */
        private val TAGS = setOf(
            "EN", "ENG", "UK", "US", "USA", "CA", "AU", "IE", "FR", "DE", "GER", "ES", "SPA", "IT", "NL", "BE", "PT", "BR",
            "AR", "TR", "PL", "RU", "IN", "HI", "SE", "SW", "NO", "DK", "FI", "GR", "RO", "HU", "CZ", "AL", "EX", "LAT",
            "KR", "JP", "CN", "PH", "PK", "IR", "IL", "SA", "AF", "ARA", "MULTI", "SUB", "SUBS", "DUB", "VF", "VOST", "VOSE",
            "NF", "AMZ", "AMZN", "DSNP", "DSN", "HMAX", "MAX", "ATV", "ATVP", "HULU", "PMTP", "SKY",
            "VIP", "VOD", "TOP", "NEW", "4K", "UHD", "FHD", "HD", "SD", "HDR", "HDR10", "DV", "HEVC", "H265", "X265",
            "3D", "60FPS", "IMAX",
        )

        fun parse(rawTitle: String?, uri: Uri?): TitleInfo {
            val source = rawTitle?.takeIf { it.isNotBlank() } ?: nameFromUri(uri)
            val fileName = ' ' !in source.trim()
            var name = if (fileName) undot(source) else source.trim()
            name = stripTags(name).ifBlank { name }
            val junk = if (fileName) fileJunkRegex else junkRegex

            val ep = episodeRegex.find(name) ?: altEpisodeRegex.find(name)
            if (ep != null) {
                val show = clean(junk.replace(name.substring(0, ep.range.first), ""))
                val after = clean(junk.replace(name.substring(ep.range.last + 1), ""))
                val season = ep.groupValues[1].toInt()
                val episode = ep.groupValues[2].toInt()
                val sub = buildString {
                    append("S").append(season).append(" · E").append(episode)
                    if (after.isNotEmpty()) append(" · ").append(after)
                }
                val showTitle = show.ifEmpty { clean(junk.replace(name, "")).ifEmpty { name } }
                return TitleInfo(showTitle, sub, stripYear(showTitle), season, episode)
            }

            // In a proper title ("Blade Runner 2049") a bare number isn't a year; only "(2017)" is.
            // File names ("Dune.Part.Two.2024.2160p") have no spaces and use bare years.
            val years = if (fileName) yearRegex else bracketYearRegex
            val withoutJunk = junk.replace(name, "")
            val year = years.findAll(withoutJunk).lastOrNull()?.takeIf { it.range.first > 0 }
                ?: years.findAll(name).lastOrNull()?.takeIf { it.range.first > 0 }
            if (year != null) {
                val title = clean(junk.replace(name.substring(0, year.range.first), ""))
                if (title.isNotEmpty()) {
                    return TitleInfo("$title (${year.groupValues[1]})", null, title, null, null)
                }
            }
            val title = clean(withoutJunk).ifEmpty { clean(name) }.ifEmpty { name }
            return TitleInfo(title, null, title, null, null)
        }

        /** Removes provider tags from the front: "EN - 4K UHD - The Batman" → "The Batman". */
        private fun stripTags(input: String): String {
            var s = input.trim()
            while (true) {
                val m = leadRegex.find(s) ?: return s
                val rest = s.substring(m.range.last + 1)
                if (rest.isBlank()) return s
                val token = m.groupValues[1]
                val separated = m.groupValues[2].isNotBlank() || token.startsWith("[") || token.startsWith("|")
                // A tag followed only by a space ("EN 4K - Dune") goes when another tag follows it.
                if (!isTag(token) || (!separated && leadRegex.find(rest)?.let { isTag(it.groupValues[1]) } != true)) {
                    return s
                }
                s = rest.trim()
            }
        }

        private fun isTag(token: String): Boolean {
            if (token.startsWith("[") || token.startsWith("|")) return true
            // Providers write tags in capitals; "It - ..." is a title, "IT - ..." is Italian.
            return token == token.uppercase() && token.split('-', '+').all { it in TAGS }
        }

        /** "The.Office.S02E03.720p" → "The Office S02E03 720p", keeping "S.W.A.T." as it is. */
        private fun undot(s: String): String =
            dotsRegex.replace(s.trim()) { m -> if (m.groupValues[1].isNotEmpty()) m.value + " " else " " }
                .replace(Regex("""\s+"""), " ")
                .trim()

        private fun nameFromUri(uri: Uri?): String {
            val last = uri?.lastPathSegment ?: return "Video"
            val noExt = last.substringBeforeLast('.', last)
            // IPTV links end in a number (…/series/user/pass/12345.mkv); that isn't a useful title.
            return if (noExt.all { it.isDigit() }) "Video" else noExt
        }

        private fun stripYear(s: String) = clean(bracketYearRegex.replace(s, ""))

        private fun clean(s: String): String =
            s.replace(Regex("""\s+"""), " ")
                .trim(' ', '-', '–', '|', ':', '(', '[')
                .trim()
    }
}
