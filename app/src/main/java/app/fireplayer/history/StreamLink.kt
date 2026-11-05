package app.fireplayer.history

import app.fireplayer.titles.TitleProxy

/**
 * What a provider's link says about the video, without its login: whose server it's on, and which film, episode or
 * channel it is ("http://host:port/movie/USER/PASSWORD/123.mkv", or the same behind FirePlayer's title server,
 * "http://127.0.0.1:8000/host:port/series/USER/PASSWORD/456.mkv"). Plain text in, so the watch history can be tested
 * on its own.
 */
class StreamLink private constructor(
    /** "host:port", the same whether or not the link goes through the title server. */
    val server: String,
    val kind: Kind,
    /** The provider's id for the film or episode; null for a link of no known shape. */
    val id: String?,
    val extension: String?,
    /** The provider's server when the link goes through FirePlayer's title server, which then knows its titles. */
    val proxied: String?,
) {
    enum class Kind { MOVIE, EPISODE, LIVE, OTHER }

    val isLive get() = kind == Kind.LIVE

    companion object {
        private val VOD = mapOf("movie" to Kind.MOVIE, "series" to Kind.EPISODE)
        private val LIVE = setOf("live", "timeshift", "streaming")

        /** Null for anything but a web link. */
        fun parse(url: String): StreamLink? {
            val scheme = url.substringBefore("://", "").lowercase()
            if (scheme != "http" && scheme != "https") return null
            val rest = url.substringAfter("://")
            val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#')
            val path = rest.substring(authority.length).substringBefore('?').substringBefore('#')
            var parts = path.split('/').filter { it.isNotEmpty() }
            val host = normalize(authority, scheme)
            val local = host == "127.0.0.1:${TitleProxy.PORT}" || host == "localhost:${TitleProxy.PORT}"
            val proxied = parts.firstOrNull()?.takeIf { local && TitleProxy.isServer(it) }
            if (proxied != null) parts = parts.drop(1)
            val server = proxied?.let { normalize(it, "http") } ?: host
            val last = parts.lastOrNull().orEmpty()
            val id = last.substringBeforeLast('.')
            val extension = last.substringAfterLast('.', "").takeIf { it.isNotEmpty() }
            val first = parts.firstOrNull()?.lowercase()
            val kind = when {
                first in LIVE || path.lowercase().endsWith("timeshift.php") -> Kind.LIVE
                parts.size == 4 && first in VOD && id.isNotEmpty() -> VOD.getValue(first!!)
                // A live channel can also be ".../USER/PASSWORD/123".
                parts.size == 3 && id.isNotEmpty() && id.all { it.isDigit() } -> Kind.LIVE
                else -> Kind.OTHER
            }
            return StreamLink(server, kind, id.takeIf { kind == Kind.MOVIE || kind == Kind.EPISODE }, extension, proxied)
        }

        /** "Host:80" for "user:pass@HOST", with the scheme's port when it has none. */
        private fun normalize(authority: String, scheme: String): String {
            val host = authority.substringAfterLast('@').lowercase()
            if (host.startsWith("[") || ':' in host) return host
            return host + if (scheme == "https") ":443" else ":80"
        }
    }
}
