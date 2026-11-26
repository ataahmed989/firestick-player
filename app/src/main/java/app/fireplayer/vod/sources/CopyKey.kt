package app.fireplayer.vod.sources

import app.fireplayer.vod.Addon
import app.fireplayer.vod.Offer
import java.security.MessageDigest
import java.util.Locale

/**
 * What a copy is known by when its add-on gave no info hash (MediaFusion's, when neither its answer nor its link shows
 * one: its [Offer.release] is null), only to keep and find what a play here measured of it (its picture and sound,
 * [app.fireplayer.player.MediaSpec], and how it played, [app.fireplayer.player.CopyHealth], in "quality_seen"):
 * "x|<add-on>|<print>|<film or episode>" ("x|mediafusion|3f0c…|tt0000077:1:1"). The print is a short hash of what
 * the add-on says of the copy that doesn't change between look-ups: its file's name (behaviorHints.filename) with the
 * size it states, else its bingeGroup and its description without what does change ([stable]: seeders and peers, age,
 * cached marks). So the same copy has the same key across look-ups and restarts, another copy or another episode
 * another. Never made from the link, and never logged (only as [SourceLog.fp]'s fingerprint). A key of its own, beside
 * [app.fireplayer.vod.ReleaseKey.storeKey]'s ("rd|…"): what was kept under those stays as it was. Pure.
 */
object CopyKey {

    const val PREFIX = "x|"

    /** The key of [offer]'s copy for the film or episode [streamId] ([app.fireplayer.vod.SourceTarget.streamId]). */
    fun of(offer: Offer, streamId: String): String = of(offer.addon, offer.filename, offer.bingeGroup, offer.text, streamId)

    fun of(addon: Addon, filename: String?, bingeGroup: String?, text: String, streamId: String): String =
        PREFIX + addon.name.lowercase(Locale.ROOT) + "|" + print(filename, bingeGroup, text) + "|" + streamId

    /** 16 hex digits of what [of] keys a copy by. */
    fun print(filename: String?, bingeGroup: String?, text: String): String {
        val name = filename?.trim()?.takeIf { it.isNotEmpty() }
        val what = if (name != null) "f\n$name\n${Words.size(text) ?: ""}" else "d\n${bingeGroup?.trim().orEmpty()}\n${stable(text)}"
        val digest = MessageDigest.getInstance("SHA-256").digest(what.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(16)
        for (i in 0 until 8) sb.append(String.format(Locale.ROOT, "%02x", digest[i].toInt() and 0xff))
        return sb.toString()
    }

    /** Whether [key] is one of these (not a release's). */
    fun isCopyKey(key: String?): Boolean = key?.startsWith(PREFIX) == true

    /**
     * [text] without what changes between look-ups of the same copy: seeders and peers ("👤 12", "50 seeders"), its age
     * ("📅 3d", "2 days ago"), cached and download marks (⚡, ⏳, "[RD+]", "cached"), emoji presentation marks; spaces and
     * separators folded, case ignored.
     */
    fun stable(text: String): String {
        var t = text.replace(INVISIBLE, "").lowercase(Locale.ROOT)
        for (r in VOLATILE) t = r.replace(t, " ")
        return t.replace(SEPARATORS, " ").trim()
    }

    private val INVISIBLE = Regex("[︎️‍]")

    private val VOLATILE = listOf(
        // Seeders and peers, by their marks or their words.
        Regex("""(?:👤|🌱|🧲|⬆️?|⬇️?)\s*[\d.,]+\s*[km]?\b"""),
        Regex("""\b(?:seed(?:er)?s?|peers?|leech(?:er)?s?)\s*[:=]?\s*[\d.,]+\s*[km]?\b"""),
        Regex("""\b[\d.,]+\s*[km]?\s*(?:seed(?:er)?s?|peers?|leech(?:er)?s?)\b"""),
        // Its age: a dated or timed field, or "… ago", or a count of days and the like.
        Regex("""(?:📅|🕒|🕐|⏱|⌛|🗓)\s*[^\s|·]+(?:\s+ago)?"""),
        Regex("""\b\d+\s*(?:y|yrs?|years?|mo|months?|w|wks?|weeks?|d|days?|h|hrs?|hours?|m|mins?|minutes?|s|secs?|seconds?)\s+ago\b"""),
        Regex("""\b\d+\s*(?:years?|months?|weeks?|days?|hours?|minutes?)\b"""),
        // Cached or not: the add-ons' marks and words.
        Regex("""\[(?:rd|tb|ad|pm|dl|ed|oc|putio)(?:\+|\s*download)?]"""),
        Regex("""[⚡⏳✅❌🔄]"""),
        Regex("""\b(?:not\s+cached|uncached|cached|instant|downloading|download)\b"""),
    )

    private val SEPARATORS = Regex("""[\s|·•,;:]+""")
}
