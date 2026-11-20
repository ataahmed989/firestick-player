package app.fireplayer.vod.sources

import app.fireplayer.vod.Addon
import app.fireplayer.vod.Failure
import app.fireplayer.vod.FailureKind
import app.fireplayer.vod.redactLinks
import app.fireplayer.vod.parseLink
import java.net.URLDecoder
import java.util.Locale

/*
 * The failure table (design-brief §6.5, rev_source-failures §6): what an add-on's notice, a link check or a player
 * error means. Pure: no links are kept, only the notice's own name ("downloading_v3") or an ElfHosted slate's title.
 */

/** The add-ons' notices: short videos they redirect to instead of a film, and what each one means. */
object Notices {

    /** Torrentio's `/videos/<name>.mp4` notices (static_videos.rs). */
    private val TORRENTIO = mapOf(
        "downloading" to FailureKind.NOT_READY,
        "download_failed" to FailureKind.FILE,
        "failed_opening" to FailureKind.FILE,
        "failed_rar" to FailureKind.FILE,
        "failed_too_big" to FailureKind.FILE,
        "failed_infringement" to FailureKind.FILE,
        "failed_access" to FailureKind.ACCOUNT,
        "blocked_access" to FailureKind.ACCOUNT,
        "limits_exceeded" to FailureKind.LIMITS,
        "failed_unavailable" to FailureKind.RATE_LIMITED,
        "failed_unexpected" to FailureKind.UNKNOWN,
    )

    /** MediaFusion's `/static/exceptions/<name>.mp4` notices (providers/torrents/realdebrid.rs, playback.rs). */
    private val MEDIAFUSION = mapOf(
        "torrent_not_downloaded" to FailureKind.NOT_READY,
        "transfer_error" to FailureKind.FILE,
        "no_matching_file" to FailureKind.FILE,
        "no_video_file_found" to FailureKind.FILE,
        "content_infringing" to FailureKind.FILE,
        "restricted" to FailureKind.FILE,
        "invalid_token" to FailureKind.ACCOUNT,
        "need_premium" to FailureKind.ACCOUNT,
        "ip_not_allowed" to FailureKind.ACCOUNT,
        "exceed_remote_traffic_limit" to FailureKind.LIMITS,
        "torrent_limit" to FailureKind.LIMITS,
        "too_many_requests" to FailureKind.RATE_LIMITED,
        "debrid_service_down_error" to FailureKind.OUTAGE,
        "api_error" to FailureKind.UNKNOWN,
        "invalid_config" to FailureKind.CONFIG,
    )

    /** ElfHosted's slate titles (docs.elfhosted.com public-addon-messages), lower case, first match wins. */
    private val SLATE = listOf(
        "not ready" to FailureKind.NOT_READY,
        "still downloading" to FailureKind.NOT_READY,
        "not cached" to FailureKind.NOT_READY,
        "different connection" to FailureKind.LINK_EXPIRED,
        "wrong ip" to FailureKind.LINK_EXPIRED,
        "client mismatch" to FailureKind.LINK_EXPIRED,
        "link expired" to FailureKind.LINK_EXPIRED,
        "not recognised" to FailureKind.LINK_EXPIRED,
        "not recognized" to FailureKind.LINK_EXPIRED,
        "invalid link" to FailureKind.LINK_EXPIRED,
        "shared with other people" to FailureKind.CONFIG,
        "invalid config" to FailureKind.CONFIG,
        "couldn't start" to FailureKind.FILE,
        "could not start" to FailureKind.FILE,
        "content unavailable" to FailureKind.FILE,
        "connection expired" to FailureKind.ACCOUNT,
        "request refused" to FailureKind.ACCOUNT,
        "service is down" to FailureKind.OUTAGE,
        "too many requests" to FailureKind.RATE_LIMITED,
    )

    /**
     * StremThru's `/v0/store/_/static/<name>.mp4` notices (internal/store/video in its source), which Torz redirects to
     * instead of a film: named by HTTP status or by what happened, the same for every debrid service.
     */
    private val STREMTHRU = mapOf(
        "downloading" to FailureKind.NOT_READY,
        "download_failed" to FailureKind.FILE,
        "no_matching_file" to FailureKind.FILE,
        "451" to FailureKind.FILE,
        "401" to FailureKind.ACCOUNT,
        "403" to FailureKind.ACCOUNT,
        "payment_required" to FailureKind.ACCOUNT,
        "store_limit_exceeded" to FailureKind.LIMITS,
        "content_proxy_limit_reached" to FailureKind.LIMITS,
        "429" to FailureKind.RATE_LIMITED,
        "500" to FailureKind.UNKNOWN,
    )

    const val SLATE_HOST = "slate.elfhosted.com"

    private val TIO_VIDEO = Regex("""/videos/([a-z_]+?)(?:_v\d+)?\.mp4$""")
    private val MF_VIDEO = Regex("""/static/exceptions/([a-z_]+)\.mp4$""")
    private val ST_VIDEO = Regex("""/v0/store/_/static/([a-z0-9_]+)\.mp4$""")

    /** Whether "copyright" is what [kind] came from, for the "just added" rule (§6.5); StremThru's is its "451". */
    fun isCopyright(detail: String?): Boolean =
        detail != null && (detail.contains("infring") || detail.contains("copyright") || detail == "451")

    /**
     * What a link ([url], an add-on's play link or where it redirected) says when it is a notice; null when it is not
     * one (a film, or an address we don't know). [addon] is whose link it was. The failure's detail is the notice's
     * name or the slate's title, never the link.
     */
    fun classify(url: String, addon: Addon?): Failure? {
        val uri = parseLink(url) ?: return null
        val path = uri.rawPath?.lowercase(Locale.ROOT) ?: return null
        TIO_VIDEO.find(path)?.let { m ->
            val name = m.groupValues[1]
            val kind = TORRENTIO[name] ?: FailureKind.UNKNOWN
            return Failure(kind, addon ?: Addon.TORRENTIO, name)
        }
        MF_VIDEO.find(path)?.let { m ->
            val name = m.groupValues[1]
            return Failure(MEDIAFUSION[name] ?: FailureKind.UNKNOWN, addon ?: Addon.MEDIAFUSION, name)
        }
        ST_VIDEO.find(path)?.let { m ->
            val name = m.groupValues[1]
            return Failure(STREMTHRU[name] ?: FailureKind.UNKNOWN, addon ?: Addon.TORZ, name)
        }
        if (uri.host?.lowercase(Locale.ROOT) == SLATE_HOST) {
            val title = query(uri.rawQuery, "title").orEmpty()
            return Failure(slateKind(title), addon ?: Addon.MEDIAFUSION, redactLinks(title).take(80).ifEmpty { "slate" })
        }
        return null
    }

    /** What an ElfHosted slate titled [title] means. */
    fun slateKind(title: String): FailureKind {
        val t = title.lowercase(Locale.ROOT)
        return SLATE.firstOrNull { t.contains(it.first) }?.second ?: FailureKind.UNKNOWN
    }

    /**
     * An error row inside an add-on's list (Torrentio's "RD error" / "TB error" rows, MediaFusion's configuration
     * notice): what its words say, or null when [text] isn't one of those words.
     */
    fun listError(text: String, addon: Addon): Failure? {
        val t = text.lowercase(Locale.ROOT)
        val kind = when {
            INVALID_SERVICE.containsMatchIn(t) || "apikey" in t || "api key" in t -> FailureKind.ACCOUNT
            "subscription" in t || "expired" in t && "debrid" in t -> FailureKind.ACCOUNT
            "currently unavailable" in t -> FailureKind.OUTAGE
            "invalid mediafusion configuration" in t || "invalid configuration" in t -> FailureKind.CONFIG
            else -> return null
        }
        return Failure(kind, addon, kind.name.lowercase(Locale.ROOT))
    }

    /** "Invalid RealDebrid ApiKey", "invalid torbox token": a debrid service refusing the key, whichever it is. */
    private val INVALID_SERVICE =
        Regex("""invalid (?:real-?debrid|torbox|alldebrid|premiumize|debrid-?link|easydebrid|offcloud|put\.?io|debrid)""")

    private fun query(raw: String?, name: String): String? {
        raw ?: return null
        for (part in raw.split('&')) {
            val eq = part.indexOf('=')
            if (eq <= 0 || part.substring(0, eq) != name) continue
            return runCatching { URLDecoder.decode(part.substring(eq + 1), "UTF-8") }.getOrNull()
        }
        return null
    }
}

/** A player error as a failure (Media3's PlaybackException codes, rev_source-failures §4C and §6). */
object PlayerErrors {
    fun failureOf(errorCode: Int, httpStatus: Int?, online: Boolean, addon: Addon?): Failure {
        if (!online && errorCode in 2000..2999) return Failure(FailureKind.NO_INTERNET, addon, "no network")
        val kind = when (errorCode) {
            2004 -> when (httpStatus) {
                401, 403, 404, 410 -> FailureKind.LINK_EXPIRED
                429 -> FailureKind.RATE_LIMITED
                451 -> FailureKind.FILE
                else -> FailureKind.UNKNOWN
            }
            2005, 2006 -> FailureKind.LINK_EXPIRED
            2003, 2007, 3001, 3002, 3003, 3004 -> FailureKind.FILE
            4001, 4002, 4003, 4004, 4005 -> FailureKind.FORMAT
            5001, 5002, 5003, 5004 -> FailureKind.AUDIO
            else -> FailureKind.UNKNOWN
        }
        val detail = "player error $errorCode" + (httpStatus?.let { " (HTTP $it)" } ?: "")
        return Failure(kind, addon, detail)
    }
}

/**
 * The words the player shows when a play stops or nothing is left (plain words, no links). They name no one debrid
 * service: the add-ons may use Real-Debrid, TorBox or another, and the same failures mean the same with each.
 */
object FailureWords {
    fun stop(kind: FailureKind): String = when (kind) {
        FailureKind.ACCOUNT -> "Your debrid service (Real-Debrid, TorBox…) refused your account. Check your sign-in or " +
            "subscription on its website."
        FailureKind.LIMITS -> "Your debrid service says a limit is reached (often its download slots are full). Clear " +
            "stuck downloads on its website, then try again."
        FailureKind.RATE_LIMITED -> "Your debrid service is busy (too many requests). Try again in a minute."
        FailureKind.OUTAGE -> "Your debrid service isn't working right now. Try again later."
        FailureKind.NO_INTERNET -> "No internet connection. Check the Wi-Fi and try again."
        FailureKind.CONFIG -> "MediaFusion's settings need redoing: enter its link again in Settings › Add-ons."
        else -> "This source didn't start."
    }

    const val NONE_READY = "No cached source worked. Choose one in Sources."
    const val TRIES_USED = "Tried 3 sources. Choose another in Sources, or use Next source."
    const val SWITCHES_USED = "Switched source twice already. Use Next source in Picture & sound to try another."
    const val GUESSES_WRONG = "Two sources said to be cached weren't. Choose one in Sources."
    const val NO_ADDONS = "No add-ons yet: add Torrentio, MediaFusion or StremThru Torz in Settings › Add-ons."
    const val NOTHING_FOUND = "No add-on found a source for this."
    const val FETCHING = "Your debrid service is fetching it now. It can't play until that's done: try again in a few minutes."

    /** The strip's words for an add-on that failed to answer. */
    fun addonFailed(addon: Addon, failure: Failure?): String = when (failure?.kind) {
        FailureKind.ACCOUNT -> "${addon.label} · the debrid service refused the account"
        FailureKind.CONFIG -> "${addon.label} · settings need redoing"
        FailureKind.OUTAGE -> "${addon.label} · the debrid service is unavailable"
        else -> "${addon.label} · didn't answer"
    }
}
