package app.fireplayer.live

/**
 * The Live TV model. A channel is identified by its playlist plus the provider's own id for it ([Channel.key]),
 * never by its display name or stream link: names change, links change (new server, new token), and two playlists
 * can carry channels with the same name that aren't the same channel.
 */

enum class PlaylistKind(val code: String) {
    XTREAM("xtream"), M3U("m3u");

    companion object {
        fun of(code: String?): PlaylistKind = values().firstOrNull { it.code == code } ?: M3U
    }
}

/**
 * One playlist the viewer added. For Xtream Codes, [url] is the server address and [username]/[password] the
 * account; for M3U, [url] is the playlist link and the account fields are empty. Saved in the app's private
 * database (ordinary private storage, not encrypted) and never written to logs.
 */
data class Playlist(
    val id: String,
    val name: String,
    val kind: PlaylistKind,
    val url: String,
    val username: String = "",
    val password: String = "",
    /** The playlist's own EPG link; blank means the default (Xtream: the server's xmltv.php; M3U: its url-tvg). */
    val epgUrl: String = "",
    /** Xtream stream format: "ts" or "m3u8". */
    val output: String = "ts",
    val includeLive: Boolean = true,
    val includeVod: Boolean = true,
    val position: Int = 0,
    val updateHours: Int = 24,
    val lastSync: Long = 0,
    val lastStatus: String = "",
    /** Provider-reported account details (read-only): when it expires, how many streams it allows. */
    val expiresAt: Long = 0,
    val maxConnections: Int = 0,
    /** Manage groups: groups a later update adds start hidden. */
    val hideNewGroups: Boolean = false,
)

data class ChannelKey(val playlistId: String, val channelId: String) {
    override fun toString() = "$playlistId/$channelId"

    companion object {
        fun parse(s: String?): ChannelKey? {
            if (s.isNullOrEmpty()) return null
            val i = s.indexOf('/')
            if (i <= 0 || i == s.length - 1) return null
            return ChannelKey(s.substring(0, i), s.substring(i + 1))
        }
    }
}

data class Channel(
    val playlistId: String,
    val id: String,
    /** The number shown in the guide: the provider's when it gives one, else the playlist position + 1. */
    val number: Int,
    val name: String,
    val logo: String?,
    val groupId: String?,
    val url: String,
    /** The XMLTV channel id its programmes are listed under (tvg-id / epg_channel_id); null shows no EPG. */
    val epgRef: String?,
    /** Days of catch-up the provider offers (0: none). Shown only; catch-up playback is not implemented yet. */
    val archiveDays: Int = 0,
    val position: Int = 0,
    val favorite: Boolean = false,
    val blocked: Boolean = false,
) {
    val key get() = ChannelKey(playlistId, id)
}

data class ChannelGroup(val playlistId: String, val id: String, val name: String, val position: Int, val hidden: Boolean = false)

data class Programme(
    val start: Long,
    val stop: Long,
    val title: String,
    val description: String?,
    val category: String?,
) {
    fun isOn(at: Long) = at in start until stop
}

/** What the guide lists: every playlist, one playlist, or a part of one. */
sealed class GuideScope {
    /** Every playlist's channels, playlists in their order. */
    object AllPlaylists : GuideScope()
    data class AllChannels(val playlistId: String) : GuideScope()
    data class Favorites(val playlistId: String) : GuideScope()
    data class Group(val playlistId: String, val groupId: String) : GuideScope()

    fun encode(): String = when (this) {
        AllPlaylists -> "all"
        is AllChannels -> "p|$playlistId"
        is Favorites -> "f|$playlistId"
        is Group -> "g|$playlistId|$groupId"
    }

    companion object {
        fun decode(s: String?): GuideScope? {
            if (s == null) return null
            if (s == "all") return AllPlaylists
            val parts = s.split('|', limit = 3)
            return when {
                parts.size == 2 && parts[0] == "p" -> AllChannels(parts[1])
                parts.size == 2 && parts[0] == "f" -> Favorites(parts[1])
                parts.size == 3 && parts[0] == "g" -> Group(parts[1], parts[2])
                else -> null
            }
        }
    }
}
