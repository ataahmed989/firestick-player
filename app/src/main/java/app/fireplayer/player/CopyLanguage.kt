package app.fireplayer.player

import java.util.Locale

/**
 * Which copies have the sound the viewer wants: their own language always, and for a film made in another language its
 * own language too (watched with subtitles), either one as good. What a copy has comes from its tracks once FirePlayer
 * has played it, else from the language tag the provider puts in front of its name ("|FR| …").
 */
object CopyLanguage {

    const val MULTI = "multi"

    /** The sounds to look for, either as good: the viewer's language, and [original] (the film's own) when it differs. */
    fun wanted(original: String?, preferred: String): List<String> {
        val p = code(preferred) ?: "en"
        val o = code(original)
        return if (o == null || o == p) listOf(p) else listOf(p, o)
    }

    /** 2: has a sound wanted; 1: may have one (several languages, or nothing known); 0: known to have none. */
    fun fit(wanted: List<String>, audioLangs: String, tag: String?): Int {
        val langs = audioLangs.split(',').mapNotNull { code(it) }
        return when {
            langs.isNotEmpty() -> if (langs.any { it in wanted }) 2 else 0
            tag == null || tag == MULTI -> 1
            tag in wanted -> 2
            else -> 0
        }
    }

    /** Whether the file carries subtitles in [language]. */
    fun hasSubs(subLangs: String, language: String): Boolean {
        val want = code(language) ?: return false
        return subLangs.split(',').any { code(it) == want }
    }

    /** The language a provider's name puts in front: "|FR| Title" → "fr", "[MULTI] …" → [MULTI]; null when none. */
    fun tagIn(name: String): String? {
        val m = TAG.find(name) ?: return null
        return TAGS[m.groupValues[1].uppercase(Locale.ROOT)]
    }

    /** "English", "French". */
    fun name(code: String): String = Locale(code).getDisplayLanguage(Locale.ENGLISH).ifEmpty { code }

    /** ISO 639-1 of "en", "eng", "en-US" or "fre"; null for none or "und". */
    fun code(lang: String?): String? {
        val l = lang?.trim()?.lowercase(Locale.ROOT)?.substringBefore('-')?.substringBefore('_') ?: return null
        if (l.isEmpty() || l == "und" || l == "mul" || l == "zxx") return null
        if (l.length == 2) return l
        return ISO3[l] ?: l
    }

    private val ISO3: Map<String, String> by lazy {
        val map = HashMap<String, String>()
        for (two in Locale.getISOLanguages()) runCatching { map[Locale(two).isO3Language] = two }
        // The bibliographic codes some files use.
        map.putAll(mapOf("fre" to "fr", "ger" to "de", "dut" to "nl", "chi" to "zh", "cze" to "cs", "gre" to "el",
            "per" to "fa", "rum" to "ro", "slo" to "sk", "alb" to "sq", "arm" to "hy", "baq" to "eu", "bur" to "my",
            "geo" to "ka", "ice" to "is", "mac" to "mk", "mao" to "mi", "may" to "ms", "tib" to "bo", "wel" to "cy"))
        map
    }

    // A tag at the very start: "|FR|", "[FR]", "FR -", "FR:", "FR |".
    private val TAG = Regex("""^\s*[|\[(]?\s*([A-Za-z]{2,5})\s*[|\])]?\s*[-:|]?\s""")

    private val TAGS = mapOf(
        "EN" to "en", "ENG" to "en", "UK" to "en", "US" to "en", "USA" to "en", "CA" to "en", "AU" to "en",
        "FR" to "fr", "VF" to "fr", "VFF" to "fr", "DE" to "de", "GER" to "de", "ES" to "es", "SPA" to "es", "LAT" to "es",
        "IT" to "it", "PT" to "pt", "BR" to "pt", "NL" to "nl", "AR" to "ar", "ARA" to "ar", "TR" to "tr", "PL" to "pl",
        "RU" to "ru", "HI" to "hi", "IN" to "hi", "KR" to "ko", "JP" to "ja", "CN" to "zh", "ALB" to "sq", "AL" to "sq",
        "SE" to "sv", "SW" to "sv", "NO" to "no", "DK" to "da", "FI" to "fi", "GR" to "el", "RO" to "ro", "HU" to "hu",
        "CZ" to "cs", "IR" to "fa", "IL" to "he", "PH" to "tl", "PK" to "ur", "MULTI" to MULTI, "MULTISUB" to MULTI,
    )
}
