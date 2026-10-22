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
    /** False for an episode whose name is only its number ("S01E03 - Pilot"): [title] then isn't the show's name. */
    val named: Boolean = true,
) {
    /** This episode under the show's name, as the provider lists the show ("EN - Breaking Bad (2008)"). */
    fun withShow(show: String): TitleInfo {
        val s = parse(show, null)
        return copy(title = s.title, searchQuery = s.searchQuery, named = true)
    }

    companion object {
        /** The title of a video that came with no name. */
        const val NO_NAME = "Video"

        private val episodeRegex = Regex("""(?i)\bS(\d{1,2})\s*[ ._-]?\s*E(\d{1,3})\b""")
        private val altEpisodeRegex = Regex("""(?i)\b(\d{1,2})x(\d{2,3})\b""")
        private val yearRegex = Regex("""[(\[]?\b((?:19|20)\d{2})\b[)\]]?""")
        private val bracketYearRegex = Regex("""[(\[]((?:19|20)\d{2})[)\]]""")
        private val dashYearRegex = Regex("""\s+[-–]\s+((?:19|20)\d{2})\s*$""")
        // As app.fireplayer.titles.Quality writes it.
        private val qualityEndRegex = Regex("""\s*\[(?:4K|1080p|720p|HD|SD)(?: HDR)?]\s*$""")

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
            // Words joined by dots or underscores inside a name that also has spaces ("BLANK1.BLANK2-eng S01E03"); a
            // lone "Dr." or "2.0" stays as it is.
            val dotted = !fileName && joinedRegex.containsMatchIn(source)
            var name = when {
                fileName -> undot(source)
                dotted -> source.trim().split(' ').joinToString(" ") { if (joinedRegex.containsMatchIn(it)) undot(it) else it }
                    .replace(Regex("""\s+"""), " ").trim()
                else -> source.trim()
            }
            // Where dots or underscores were the spaces, a tag after one is a tag whatever its case ("BLANK_fr").
            val anyCase = fileName || dotted
            name = stripTags(name).ifBlank { name }
            // The quality TiviMate's names now end with ("The Matrix (1999) [4K]"), and a year after a dash ("K.O. - 2025").
            name = name.replace(qualityEndRegex, "").ifBlank { name }.replace(dashYearRegex) { " (${it.groupValues[1]})" }
            val junk = if (fileName) fileJunkRegex else junkRegex

            val ep = episodeRegex.find(name) ?: altEpisodeRegex.find(name)
            if (ep != null) {
                val show = stripEndTags(clean(junk.replace(name.substring(0, ep.range.first), "")), anyCase)
                val after = stripEndTags(clean(junk.replace(name.substring(ep.range.last + 1), "")), anyCase)
                val season = ep.groupValues[1].toInt()
                val episode = ep.groupValues[2].toInt()
                val sub = buildString {
                    append("S").append(season).append(" · E").append(episode)
                    if (after.isNotEmpty()) append(" · ").append(after)
                }
                val showTitle = show.ifEmpty { clean(junk.replace(name, "")).ifEmpty { name } }
                return TitleInfo(showTitle, sub, stripYear(showTitle), season, episode, named = show.isNotEmpty())
            }

            // In a proper title ("Blade Runner 2049") a bare number isn't a year; only "(2017)" is.
            // File names ("Dune.Part.Two.2024.2160p") have no spaces and use bare years.
            val years = if (fileName) yearRegex else bracketYearRegex
            val withoutJunk = junk.replace(name, "")
            val year = years.findAll(withoutJunk).lastOrNull()?.takeIf { it.range.first > 0 }
                ?: years.findAll(name).lastOrNull()?.takeIf { it.range.first > 0 }
            if (year != null) {
                val title = stripEndTags(clean(junk.replace(name.substring(0, year.range.first), "")), anyCase)
                if (title.isNotEmpty()) {
                    return TitleInfo("$title (${year.groupValues[1]})", null, title, null, null)
                }
            }
            val title = stripEndTags(clean(withoutJunk).ifEmpty { clean(name) }.ifEmpty { name }, anyCase)
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

        // A tag joined on at the end ("BLANK-eng", "Dark [EN]", "Show (ENG)", "Show ENG"), maybe of a few parts
        // ("-pt-br", "[EN+FR]"), and a year in brackets after it.
        private val trailRegex = Regex("""(\s*[-_|–—]\s*|\s+|\s*[(\[])([A-Za-z]{2,10})[)\]]?\s*$""")
        private val trailPartsRegex =
            Regex("""(\s*[-_|–—]\s*|\s+|\s*[(\[])([A-Za-z]{2,10}(?:\s*[-+,/]\s*[A-Za-z]{2,10})+)[)\]]?\s*$""")
        private val yearAtEndRegex = Regex("""\s*[(\[](?:19|20)\d{2}[)\]]\s*$""")

        // A dot or underscore between two words, at least one of them letters (not "2.0").
        private val joinedRegex = Regex("""(?<=[A-Za-z])[._](?=[A-Za-z0-9])|(?<=[0-9])[._](?=[A-Za-z])|(?<=[0-9])_(?=[0-9])""")

        /**
         * Language tags providers put after names; only these go, so a title ending in a real word stays whole. Codes
         * that are also words or numerals ("it", "no", "hi", "he", "pan", "chi", "dan", "ko", "vi") aren't here.
         */
        private val END_TAGS = setOf(
            "EN", "ENG", "ENGLISH", "AR", "ARA", "ARABIC", "FR", "FRE", "FRA", "FRENCH", "DE", "GER", "DEU", "GERMAN",
            "ES", "SPA", "ESP", "SPANISH", "LATINO", "LATAM", "CASTELLANO", "ITA", "ITALIAN", "PT", "POR", "PORTUGUESE",
            "NL", "DUT", "NLD", "DUTCH", "RU", "RUS", "RUSSIAN", "TR", "TUR", "TURKISH", "PL", "POL", "POLISH",
            "JA", "JP", "JPN", "JAPANESE", "KR", "KOR", "KOREAN", "ZH", "CN", "ZHO", "CHN", "CHINESE", "MANDARIN",
            "CANTONESE", "SV", "SWE", "SWEDISH", "DANISH", "FINNISH", "NOR", "NORWEGIAN", "EL", "GRE",
            "ELL", "CS", "CZ", "CZE", "CES", "CZECH", "HU", "HUNGARIAN", "RO", "RUM", "ROMANIAN", "UKR",
            "UKRAINIAN", "FA", "FAS", "PERSIAN", "FARSI", "UR", "URD", "URDU", "HEB", "HEBREW", "HIN", "HINDI", "TH",
            "THA", "VIETNAMESE", "MS", "MSA", "MALAY", "SQ", "ALB", "ALBANIAN", "SR", "SRP", "SERBIAN",
            "HR", "HRV", "CROATIAN", "BG", "BUL", "BULGARIAN", "KUR", "KURDISH", "TAM", "TAMIL", "TEL", "TELUGU",
            "MALAYALAM", "KANNADA", "BENGALI", "MARATHI", "PUNJABI", "GUJARATI", "INDONESIAN", "FILIPINO", "TAGALOG",
            "MULTI", "SUB", "SUBS", "SUBBED", "ESUB", "ESUBS", "DUB", "DUBBED", "VOST", "VOSTFR", "VOSE", "VF", "VO",
            "ENGSUB", "ENGSUBS", "MULTISUB", "MULTISUBS", "DUAL", "VFF", "VFQ", "TRUEFRENCH",
        )

        /**
         * Codes that are also words: tags only when joined on with a dash, "|" or brackets, and written as tags are, in
         * small letters or capitals ("-it", "-IT", not "Whip-It" or "Hi-de-Hi").
         */
        private val JOINED_TAGS = setOf("IT", "HI", "HE", "NO", "IND", "MAL", "KAN", "BEN", "PAN", "EX", "YU", "EXYU")

        /** Of those, the ones that in brackets are the country a show is from ("The Office (IND)"): kept. */
        private val BRACKET_COUNTRIES = setOf("IT", "NO", "IND")

        /** Countries and places after a language ("-pt-br", "-en-us", "-es-la"): they go with it. */
        private val REGIONS = setOf(
            "US", "USA", "UK", "GB", "BR", "LA", "CA", "TW", "HK", "MX", "AL", "AU", "NZ", "IE", "IN", "BE", "CH", "AT",
        )

        /** A country alone, joined on in capitals ("Shameless-US"), tells same-named shows apart: kept, in brackets. */
        private val COUNTRY_TAGS = setOf("US", "USA", "UK", "GB", "CA", "AU", "NZ", "IE")

        /**
         * Removes language tags from the end: "BLANK-eng" → "BLANK", "Dark [EN] (2017)" → "Dark (2017)", "BLANK-pt-br"
         * → "BLANK". [anyCase]: the spaces were dots or underscores, so a tag after one is a tag whatever its case.
         */
        private fun stripEndTags(input: String, anyCase: Boolean): String {
            val year = yearAtEndRegex.find(input)
            if (year != null && year.range.first > 0) {
                val head = stripEndTags(input.substring(0, year.range.first), anyCase)
                return "$head ${year.value.trim()}"
            }
            var s = input.trim()
            while (true) {
                // A tag of a few parts ("-pt-br", "[EN+FR]") when they all are tags, otherwise its last part alone.
                var parts = trailPartsRegex.find(s)
                while (parts != null && !isEndTag(parts.groupValues[1], parts.groupValues[2], anyCase, s, parts.range.first)) {
                    parts = trailPartsRegex.find(s, parts.range.first + 1)
                }
                val m = parts ?: trailRegex.find(s) ?: return s
                val separator = m.groupValues[1]
                val tag = m.groupValues[2]
                val rest = s.substring(0, m.range.first).trim()
                if (rest.isEmpty()) return s
                val joined = separator.isNotBlank()
                if (tag.uppercase() in REGIONS) {
                    // "_pt_br", ".es.la": a country after a language, both once joined on with "_" or ".", goes with it.
                    val language = if (anyCase && !joined) trailRegex.find(rest) else null
                    if (language != null && language.range.first > 0 &&
                        isEndTag(language.groupValues[1], language.groupValues[2], true, rest, language.range.first)
                    ) {
                        s = rest.substring(0, language.range.first).trim()
                        continue
                    }
                    val bracketed = separator.trim().let { it.startsWith("(") || it.startsWith("[") }
                    return if (joined && !bracketed && tag == tag.uppercase() && tag in COUNTRY_TAGS) "$rest ($tag)" else s
                }
                if (!isEndTag(separator, tag, anyCase, s, m.range.first)) return s
                s = rest
            }
        }

        /**
         * Whether [tag], after [separator] at [at] in [name], is a language or subtitle tag ("eng", "pt-br", "EN+FR").
         */
        private fun isEndTag(separator: String, tag: String, anyCase: Boolean, name: String, at: Int): Boolean {
            val joined = separator.isNotBlank()
            if (!joined) {
                // After a plain space, only a tag in capitals ("Dark ENG"): "Tout en" is a title. Where the spaces were
                // dots or underscores, a short code in any case ("BLANK_fr"), but a language's whole name only as tags
                // are written, all small: "Johnny.English" and "The.Albanian" are titles.
                if (tag != tag.uppercase() && (!anyCase || (tag.length > 3 && tag != tag.lowercase()))) return false
                // In a name all in capitals, a whole language name may be its last word ("JOHNNY ENGLISH").
                val head = name.substring(0, at)
                if (tag == tag.uppercase() && tag.length > 4 && head.none { it.isLowerCase() }) return false
            }
            val parts = tag.split('-', '+', ',', '/').map { it.trim() }
            val bracketed = separator.trim().let { it.startsWith("(") || it.startsWith("[") }
            fun known(part: String): Boolean {
                val code = part.uppercase()
                if (code in END_TAGS) return true
                // In brackets, a few of these name the country a show is from ("The Office (IND)").
                if (parts.size == 1 && bracketed && code in BRACKET_COUNTRIES) return false
                return joined && code in JOINED_TAGS && (part == part.lowercase() || part == part.uppercase())
            }
            if (parts.size == 1) return known(parts[0])
            // A language first, then maybe a country ("pt-br"): "Suits.LA-eng" keeps "LA".
            return known(parts[0]) && parts.drop(1).all { known(it) || it.uppercase() in REGIONS }
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
            val last = uri?.lastPathSegment ?: return NO_NAME
            val noExt = last.substringBeforeLast('.', last)
            // IPTV links end in a number (…/series/user/pass/12345.mkv); that isn't a useful title.
            return if (noExt.all { it.isDigit() }) NO_NAME else noExt
        }

        private fun stripYear(s: String) = clean(bracketYearRegex.replace(s, ""))

        private fun clean(s: String): String =
            s.replace(Regex("""\s+"""), " ")
                .trim(' ', '-', '–', '|', ':', '(', '[')
                .trim()
    }
}
