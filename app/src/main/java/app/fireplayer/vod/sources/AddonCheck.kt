package app.fireplayer.vod.sources

import app.fireplayer.data.CredentialChecks.State
import app.fireplayer.vod.Failure
import app.fireplayer.vod.FailureKind
import app.fireplayer.vod.Readiness
import app.fireplayer.vod.parseLink
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Locale

/**
 * What an add-on's answers say about it, in the API keys' states and words ([State]: Working, Not accepted, Couldn't
 * check…) with one short problem and the debrid service when the answer tells. Pure: no link is kept or quoted, the
 * words are this file's own (never an add-on's text or an exception's message, which can hold the link).
 */
object AddonCheck {

    /** A verdict: [problem] for Not accepted and Couldn't check, [service] ("Real-Debrid") when the answer told. */
    data class Outcome(val state: State, val problem: String? = null, val service: String? = null)

    /** The check's title: a popular, long-released film every debrid service has cached (The Shawshank Redemption). */
    const val TEST_ID = "tt0111161"

    // The problems, as the row's note says them ("MediaFusion: debrid key refused.").
    const val KEY_REFUSED = "debrid key refused"
    const val REDO = "settings need redoing"
    const val LIMIT = "debrid limit reached"
    const val DEBRID_DOWN = "debrid service down"
    const val DEBRID_BUSY = "debrid service busy"
    const val NOT_ANSWERING = "add-on not answering"
    const val ADDON_DOWN = "add-on down"
    const val ADDON_BUSY = "add-on busy"
    const val NOT_RECOGNISED = "link not recognised"
    const val NOT_AN_ADDON = "not an add-on's link"
    const val UNREADABLE = "answer unreadable"
    const val NO_DEBRID = "no debrid service in its settings"
    const val NONE_CACHED = "nothing cached to test with"
    const val NO_RESULTS = "no results to test with"
    const val NO_INTERNET = "no internet"

    /** The manifest's answer: null when it is an add-on's (an object with an id), else why not. */
    fun fromManifest(body: String): Outcome? {
        val id = runCatching { JSONObject(body).optString("id") }.getOrNull()
        return if (id.isNullOrBlank()) Outcome(State.NOT_ACCEPTED, NOT_AN_ADDON) else null
    }

    /**
     * The check's streams answer for [TEST_ID] ([json], read as [parsed]): Working when a cached source is there; a
     * debrid error row by the failure table ([Failures]); otherwise what kept it from being confirmed.
     */
    fun fromAnswer(json: String, parsed: ParsedAnswer): Outcome {
        if (parsed.busy) return Outcome(State.UNREACHABLE, ADDON_BUSY)
        if (parsed.streams.any { it.offer.readiness == Readiness.PROBABLY_READY }) return Outcome(State.WORKING, service = parsed.service)
        parsed.notice?.let { return fromFailure(it.kind)?.copy(service = parsed.service) ?: Outcome(State.UNREACHABLE, UNREADABLE) }
        if (parsed.streams.isNotEmpty()) return Outcome(State.UNREACHABLE, NONE_CACHED, parsed.service)
        val streams = runCatching { JSONObject(json).optJSONArray("streams") }.getOrElse { return Outcome(State.UNREACHABLE, UNREADABLE) }
        // Torrent hashes without links: the add-on works, but without a debrid service nothing of it can play here.
        val torrentsOnly = streams != null && (0 until streams.length()).any { i ->
            streams.optJSONObject(i)?.let { it.has("infoHash") && it.optString("url").isBlank() } == true
        }
        return if (torrentsOnly) Outcome(State.NOT_ACCEPTED, NO_DEBRID) else Outcome(State.UNREACHABLE, NO_RESULTS)
    }

    /** The check's request that failed with [e] (by its kind only, never its message). */
    fun fromError(e: Throwable?, online: Boolean): Outcome = when {
        !online -> Outcome(State.UNREACHABLE, NO_INTERNET)
        e is StreamsHttp.Busy -> Outcome(State.UNREACHABLE, ADDON_BUSY)
        e is StreamsHttp.Status && e.code in 500..599 -> Outcome(State.UNREACHABLE, ADDON_DOWN)
        e is StreamsHttp.Status && e.code in 400..499 -> Outcome(State.NOT_ACCEPTED, NOT_RECOGNISED)
        e is StreamsHttp.Status -> Outcome(State.UNREACHABLE, UNREADABLE)
        e is SocketTimeoutException || e is IOException -> Outcome(State.UNREACHABLE, NOT_ANSWERING)
        else -> Outcome(State.UNREACHABLE, UNREADABLE)
    }

    /**
     * What a failure of the table means for the add-on's status; null when it's about one file, the TV or the
     * network rather than the add-on or its debrid service.
     */
    fun fromFailure(kind: FailureKind): Outcome? = when (kind) {
        FailureKind.ACCOUNT -> Outcome(State.NOT_ACCEPTED, KEY_REFUSED)
        FailureKind.CONFIG -> Outcome(State.NOT_ACCEPTED, REDO)
        FailureKind.LIMITS -> Outcome(State.NOT_ACCEPTED, LIMIT)
        FailureKind.OUTAGE -> Outcome(State.UNREACHABLE, DEBRID_DOWN)
        FailureKind.RATE_LIMITED -> Outcome(State.UNREACHABLE, DEBRID_BUSY)
        FailureKind.ADDON_DOWN -> Outcome(State.UNREACHABLE, NOT_ANSWERING)
        else -> null
    }

    /**
     * What a real look-up's answer says, for free: a cached source marks it Working, a debrid error row or the add-on
     * not answering records the problem. Null (nothing noted) for an empty answer, a busy add-on, an answer the title
     * alone explains (HTTP 4xx), or no network.
     */
    fun fromLookup(parsed: ParsedAnswer?, error: Throwable?, online: Boolean): Outcome? {
        if (parsed == null) {
            if (!online || error is StreamsHttp.Busy) return null
            if (error is StreamsHttp.Status && error.code !in 500..599) return null
            return fromError(error, online = true)
        }
        if (parsed.busy) return null
        if (parsed.streams.any { it.offer.readiness == Readiness.PROBABLY_READY }) return Outcome(State.WORKING, service = parsed.service)
        if (parsed.streams.isEmpty()) parsed.notice?.let { return fromFailure(it.kind)?.copy(service = parsed.service) }
        return null
    }

    /** A play's link check: its film's link (from [host], the debrid service's) marks it Working; a failure as the table says. */
    fun fromLinkCheck(host: String?, failure: Failure?): Outcome? =
        if (failure == null) Outcome(State.WORKING, service = Debrids.byHost(host)) else fromFailure(failure.kind)

    /**
     * The note beside a status: "Real-Debrid · checked 5 min ago" while Working, the problem for Not accepted and
     * Couldn't check, nothing otherwise. [checkedAt] and [now] are epoch ms.
     */
    fun detail(state: State, problem: String?, service: String?, checkedAt: Long, now: Long): String? = when (state) {
        State.WORKING -> listOfNotNull(service, checkedAgo(checkedAt, now)).joinToString(" · ").ifEmpty { null }
        State.NOT_ACCEPTED, State.UNREACHABLE -> problem
        else -> null
    }

    /** "checked just now", "checked 5 min ago", "checked 3 h ago", "checked 2 days ago"; null when never. */
    fun checkedAgo(at: Long, now: Long): String? {
        if (at <= 0) return null
        val min = ((now - at).coerceAtLeast(0)) / 60_000
        return when {
            min < 1 -> "checked just now"
            min < 60 -> "checked $min min ago"
            min < 24 * 60 -> "checked ${min / 60} h ago"
            min < 48 * 60 -> "checked 1 day ago"
            else -> "checked ${min / (24 * 60)} days ago"
        }
    }
}

/**
 * The debrid services the add-ons use, by the words their answers carry: the path of a stream's link (Torrentio's
 * `/resolve/realdebrid/…`, StremThru's `/strem/…/torbox/…`), the short code in its name ("[RD+]", "⚡️ [TB]",
 * MediaFusion's "RD ⚡️"), and the sites their films stream from ("download.real-debrid.com"). Pure.
 */
object Debrids {

    private class Service(val name: String, val slugs: Set<String>, val codes: Set<String>, val sites: Set<String>)

    private val ALL = listOf(
        Service("Real-Debrid", setOf("realdebrid", "real-debrid"), setOf("RD"), setOf("real-debrid.com", "rdeb.io")),
        Service("TorBox", setOf("torbox"), setOf("TB", "TRB"), setOf("torbox.app", "tb-cdn.st", "tb-cdn.io")),
        Service("AllDebrid", setOf("alldebrid"), setOf("AD"), setOf("alldebrid.com", "debrid.it")),
        Service("Premiumize", setOf("premiumize"), setOf("PM"), setOf("premiumize.me")),
        Service("Debrid-Link", setOf("debridlink", "debrid-link"), setOf("DL"), setOf("debrid-link.com", "debrid-link.fr")),
        Service("EasyDebrid", setOf("easydebrid"), setOf("ED"), emptySet()),
        Service("Offcloud", setOf("offcloud"), setOf("OC"), setOf("offcloud.com")),
        Service("Put.io", setOf("putio"), setOf("PUTIO"), emptySet()),
        Service("PikPak", setOf("pikpak"), setOf("PKP", "PP"), emptySet()),
    )

    /** The sites the debrid services stream from, by name ([SourceLog] names them too). */
    val SITES: Map<String, String> = ALL.flatMap { s -> s.sites.map { it to s.name } }.toMap()

    private val BY_SLUG = ALL.flatMap { s -> s.slugs.map { it to s.name } }.toMap()
    private val BY_CODE = ALL.flatMap { s -> s.codes.map { it to s.name } }.toMap()
    private val CODES = ALL.flatMap { it.codes }.joinToString("|")

    /** "[RD+]", "[TB download]", "⚡️ [TB]"; MediaFusion's "RD ⚡️" / "TRB ⏳"; an error row's "RD error". */
    private val CODE = Regex("""\[($CODES)(?:\+| download)?]|\b($CODES)\s*[⚡⏳]|\b($CODES) error\b""", RegexOption.IGNORE_CASE)

    /** The service a stream's [link] or [name] tells; null when neither does. The link is read in memory only. */
    fun of(name: String?, link: String?): String? {
        link?.let { l ->
            val parts = parseLink(l)?.rawPath?.lowercase(Locale.ROOT)?.split('/') ?: emptyList()
            parts.firstNotNullOfOrNull { BY_SLUG[it] }?.let { return it }
        }
        val m = name?.let { CODE.find(it) } ?: return null
        val code = m.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: return null
        return BY_CODE[code.uppercase(Locale.ROOT)]
    }

    /** The service a film's link is served by, from its host ("42.download.real-debrid.com"); null when not a debrid's. */
    fun byHost(host: String?): String? {
        val h = host?.lowercase(Locale.ROOT)?.trim('.') ?: return null
        return SITES.entries.firstOrNull { (site, _) -> h == site || h.endsWith(".$site") }?.value
    }
}
