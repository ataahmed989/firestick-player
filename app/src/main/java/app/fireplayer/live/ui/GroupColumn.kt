package app.fireplayer.live.ui

import app.fireplayer.live.ChannelGroup
import app.fireplayer.live.GuideScope
import app.fireplayer.live.Playlist

/**
 * The playlists-and-groups list, as a model (pure, unit-tested): "All playlists", then each playlist in the viewer's
 * order as a heading that opens to Favorites, All channels and its groups (the shown ones, in their order). One list
 * for both places it shows: the guide's groups column ([GuideSidebar]) and the groups column of the channel list over
 * full screen and Multiview ([LiveOverlay]), so the two never differ.
 */
object GroupColumn {

    sealed class Item {
        object All : Item()
        data class Head(val playlist: Playlist, val expanded: Boolean) : Item()
        data class Entry(val scope: GuideScope, val label: String) : Item()
    }

    /**
     * Which playlists are open. One set for the guide's column and the list over the picture: a playlist opened or
     * folded in one is so in the other.
     */
    class Folds {
        private val open = HashSet<String>()

        fun isOpen(playlistId: String): Boolean = playlistId in open

        /** Opens or folds [playlistId]; true when it's open now. */
        fun toggle(playlistId: String): Boolean {
            if (open.remove(playlistId)) return false
            open += playlistId
            return true
        }

        /** Opens [playlistId]; false when it was open already. */
        fun open(playlistId: String): Boolean = open.add(playlistId)

        /**
         * As the list starts: the playlist holding [scope] is open, and an only playlist with nothing open yet. True
         * when something opened.
         */
        fun starting(playlists: List<Playlist>, scope: GuideScope?): Boolean {
            var changed = scope?.let { playlistOf(it) }?.let { open(it) } ?: false
            if (open.isEmpty() && playlists.size == 1) changed = open(playlists[0].id) || changed
            return changed
        }
    }

    /** The rows: [groups] by playlist, as the guide lists them (hidden ones left out by whoever read them). */
    fun items(playlists: List<Playlist>, groups: Map<String, List<ChannelGroup>>, folds: Folds): List<Item> =
        buildList {
            add(Item.All)
            for (p in playlists) {
                val open = folds.isOpen(p.id)
                add(Item.Head(p, open))
                if (open) {
                    add(Item.Entry(GuideScope.Favorites(p.id), FAVORITES))
                    add(Item.Entry(GuideScope.AllChannels(p.id), ALL_CHANNELS))
                    for (g in groups[p.id].orEmpty()) add(Item.Entry(GuideScope.Group(p.id, g.id), g.name))
                }
            }
        }

    /** A row's words. */
    fun labelOf(item: Item): String = when (item) {
        Item.All -> ALL_PLAYLISTS
        is Item.Head -> item.playlist.name
        is Item.Entry -> item.label
    }

    /** The list a row shows; none for a playlist's heading. */
    fun scopeOf(item: Item): GuideScope? = when (item) {
        Item.All -> GuideScope.AllPlaylists
        is Item.Entry -> item.scope
        is Item.Head -> null
    }

    /** The playlist holding [scope]; none for All playlists. */
    fun playlistOf(scope: GuideScope): String? = when (scope) {
        GuideScope.AllPlaylists -> null
        is GuideScope.AllChannels -> scope.playlistId
        is GuideScope.Favorites -> scope.playlistId
        is GuideScope.Group -> scope.playlistId
    }

    /**
     * What [scope] is called over its channels: "playlist · group" (Favorites and All channels as groups; a group not
     * listed, the playlist alone), "All playlists" for them all.
     */
    fun header(playlists: List<Playlist>, groups: Map<String, List<ChannelGroup>>, scope: GuideScope): String {
        val id = playlistOf(scope) ?: return ALL_PLAYLISTS
        val pl = playlists.firstOrNull { it.id == id }?.name.orEmpty()
        val label = when (scope) {
            is GuideScope.Favorites -> FAVORITES
            is GuideScope.AllChannels -> ALL_CHANNELS
            is GuideScope.Group -> groups[id]?.firstOrNull { it.id == scope.groupId }?.name
            GuideScope.AllPlaylists -> null
        } ?: return pl
        return if (pl.isEmpty()) label else "$pl · $label"
    }

    const val ALL_PLAYLISTS = "All playlists"
    const val FAVORITES = "Favorites"
    const val ALL_CHANNELS = "All channels"
}
