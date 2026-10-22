package app.fireplayer.titles

import android.net.Uri

/**
 * Where TiviMate reaches a provider through FirePlayer, so its films and shows come with their English names (see
 * [TitleService]): the provider's server address after FirePlayer's own, "http://127.0.0.1:8000/proxpanel.fans:80".
 */
object TitleProxy {
    const val PORT = 8000

    /** What to type in TiviMate instead of [server] ("http://proxpanel.fans:80" or "proxpanel.fans:80"). */
    fun addressFor(server: String): String =
        "http://127.0.0.1:$PORT/" + server.trim().removePrefix("http://").trimEnd('/')

    /**
     * The provider's server ("proxpanel.fans:80") when [uri] goes through FirePlayer, null otherwise. Its links are
     * then the provider's own with that in front: ".../proxpanel.fans:80/series/USER/PASSWORD/123.mkv".
     */
    fun serverIn(uri: Uri): String? {
        if (uri.port != PORT || (uri.host != "127.0.0.1" && uri.host != "localhost")) return null
        return uri.pathSegments.firstOrNull()?.takeIf { isServer(it) }
    }

    /** "proxpanel.fans:80", "premium-pro2.com:8789" or "1.2.3.4": a host, maybe with its port. */
    fun isServer(segment: String): Boolean {
        val host = segment.substringBefore(':')
        val port = segment.substringAfter(':', "80")
        return '.' in host && host.all { it.isLetterOrDigit() || it == '.' || it == '-' } &&
            port.isNotEmpty() && port.all { it.isDigit() }
    }
}
