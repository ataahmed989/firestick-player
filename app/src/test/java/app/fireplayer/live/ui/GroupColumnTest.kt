package app.fireplayer.live.ui

import app.fireplayer.live.ChannelGroup
import app.fireplayer.live.GuideScope
import app.fireplayer.live.Playlist
import app.fireplayer.live.PlaylistKind
import app.fireplayer.live.ui.GroupColumn.Item
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The playlists-and-groups list the guide's column and the list over the picture both show. */
class GroupColumnTest {

    private fun playlist(id: String, name: String) = Playlist(id, name, PlaylistKind.M3U, "")

    private val home = playlist("p", "Home")
    private val sports = playlist("q", "Sports")
    private val groups = mapOf(
        "p" to listOf(ChannelGroup("p", "kids", "Kids", 1), ChannelGroup("p", "news", "News", 0)),
        "q" to listOf(ChannelGroup("q", "foot", "Football", 0)),
    )

    @Test fun everyPlaylistIsAHeadingInTheViewersOrderWithItsListsUnderItWhileOpen() {
        val folds = GroupColumn.Folds().apply { open("p") }
        val items = GroupColumn.items(listOf(sports, home), groups, folds)
        assertEquals(
            listOf(
                Item.All,
                Item.Head(sports, false),
                Item.Head(home, true),
                Item.Entry(GuideScope.Favorites("p"), "Favorites"),
                Item.Entry(GuideScope.AllChannels("p"), "All channels"),
                // The groups in the order they were read in (the viewer's, from Manage groups), not sorted again.
                Item.Entry(GuideScope.Group("p", "kids"), "Kids"),
                Item.Entry(GuideScope.Group("p", "news"), "News"),
            ),
            items,
        )
        assertEquals(listOf("All playlists", "Sports", "Home", "Favorites", "All channels", "Kids", "News"), items.map { GroupColumn.labelOf(it) })
        assertEquals(GuideScope.AllPlaylists, GroupColumn.scopeOf(items[0]))
        assertNull(GroupColumn.scopeOf(items[1]))
        assertEquals(GuideScope.Group("p", "kids"), GroupColumn.scopeOf(items[5]))
    }

    @Test fun aPlaylistWithoutGroupsStillHasFavoritesAndAllChannels() {
        val folds = GroupColumn.Folds().apply { open("p") }
        val items = GroupColumn.items(listOf(home), emptyMap(), folds)
        assertEquals(listOf("All playlists", "Home", "Favorites", "All channels"), items.map { GroupColumn.labelOf(it) })
    }

    @Test fun foldsOpenAndFoldAndAreSharedByWhoeverHoldsThem() {
        val folds = GroupColumn.Folds()
        assertFalse(folds.isOpen("p"))
        assertTrue(folds.toggle("p"))
        assertTrue(folds.isOpen("p"))
        assertFalse(folds.open("p"))
        assertFalse(folds.toggle("p"))
        assertFalse(folds.isOpen("p"))
        assertTrue(folds.open("p"))
        // Two lists built from the same folds are the same list.
        assertEquals(GroupColumn.items(listOf(home, sports), groups, folds), GroupColumn.items(listOf(home, sports), groups, folds))
    }

    @Test fun theListStartsWithTheShownGroupsPlaylistOpen() {
        val both = listOf(home, sports)
        val folds = GroupColumn.Folds()
        assertTrue(folds.starting(both, GuideScope.Group("q", "foot")))
        assertTrue(folds.isOpen("q"))
        assertFalse(folds.isOpen("p"))
        // Open already: nothing changes.
        assertFalse(folds.starting(both, GuideScope.Favorites("q")))
        // All playlists holds no playlist: none opens among several.
        val none = GroupColumn.Folds()
        assertFalse(none.starting(both, GuideScope.AllPlaylists))
        assertFalse(none.isOpen("p"))
        assertFalse(none.starting(both, null))
        // An only playlist opens by itself.
        assertTrue(none.starting(listOf(home), GuideScope.AllPlaylists))
        assertTrue(none.isOpen("p"))
    }

    @Test fun aScopesPlaylist() {
        assertNull(GroupColumn.playlistOf(GuideScope.AllPlaylists))
        assertEquals("p", GroupColumn.playlistOf(GuideScope.AllChannels("p")))
        assertEquals("p", GroupColumn.playlistOf(GuideScope.Favorites("p")))
        assertEquals("q", GroupColumn.playlistOf(GuideScope.Group("q", "foot")))
    }

    @Test fun theHeaderNamesThePlaylistTheGroupShownIsIn() {
        val both = listOf(home, sports)
        assertEquals("Sports · Football", GroupColumn.header(both, groups, GuideScope.Group("q", "foot")))
        assertEquals("Home · News", GroupColumn.header(both, groups, GuideScope.Group("p", "news")))
        assertEquals("Sports · Favorites", GroupColumn.header(both, groups, GuideScope.Favorites("q")))
        assertEquals("Home · All channels", GroupColumn.header(both, groups, GuideScope.AllChannels("p")))
        assertEquals("All playlists", GroupColumn.header(both, groups, GuideScope.AllPlaylists))
        // A group that isn't listed (hidden, or not read yet): the playlist alone.
        assertEquals("Home", GroupColumn.header(both, groups, GuideScope.Group("p", "hidden")))
        assertEquals("Home", GroupColumn.header(both, emptyMap(), GuideScope.Group("p", "news")))
        // A playlist that's gone: the list's own name.
        assertEquals("All channels", GroupColumn.header(both, groups, GuideScope.AllChannels("gone")))
    }
}
