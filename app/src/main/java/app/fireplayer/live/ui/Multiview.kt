package app.fireplayer.live.ui

import android.app.Activity
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.RectF
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import app.fireplayer.live.Channel
import app.fireplayer.live.ChannelKey
import app.fireplayer.live.Playlist
import app.fireplayer.live.ui.MultiviewLayout.Dir
import app.fireplayer.live.ui.MultiviewRules.Choice
import app.fireplayer.playback.LiveCushion
import app.fireplayer.playback.LiveSession
import app.fireplayer.playback.MultiviewLeases
import app.fireplayer.playback.MultiviewPlayer
import app.fireplayer.playback.PlayerHost
import app.fireplayer.shell.Tokens

/**
 * Multiview, inside the full-screen Live TV player (the user's design: "2-4 side by side, pressing one makes one larger
 * and puts the others underneath and holding a selected picture gives me the options to remove, add/search,
 * change/search, or go full screen"; sound only from the selected picture; never more streams than the provider
 * allows).
 *
 * - Full screen's Multiview button: the playing picture shrinks to the top right (ready for a second) and the channel
 *   list ([LiveOverlay]: the playlist's groups, the channels beside them, "Search" at the top) opens on the left for
 *   the second; Search opens Live TV search ([ChannelPicker]) over it, see-through, the channel history under its bar.
 *   Multiview opens once a channel is picked (Back first: full screen as it was).
 * - The D-pad moves between the pictures ([MultiviewLayout.move]); the selected one has the white ring and the sound,
 *   the others play silent. OK makes it the large one, OK on the large one goes back to equal; OK on "Add a channel"
 *   opens the picker. Held OK (or Menu): Remove, Add channel, Change channel, Full screen ([HoldPopup]).
 * - Back, Full screen, or removing down to one picture: Multiview closes and that channel plays full screen in the one
 *   live player ([Callbacks.leave]).
 *
 * Each picture has its own player ([MultiviewPlayer]) and surface ([MultiviewBoard]), made when the picture is added
 * and let go of when it's removed; its connection is a lease from the app's [app.fireplayer.playback.ConnectionBudget]
 * ([MultiviewLeases]); a channel that wouldn't fit is said so and not opened. The sound is asked for once, for all of
 * them ([requestSound]); the players never ask themselves, so they never take it from one another. The one live player
 * is stopped while Multiview shows ([Callbacks.enter]) and untouched otherwise. When the screen stops every player and
 * lease goes ([suspend]); the pictures come back when it starts again ([resume]).
 */
class Multiview(private val activity: Activity, private val t: Tokens, private val callbacks: Callbacks) {

    interface Callbacks {
        /** The board, added once under the pages (over the wallpaper, under the controls, windows and notices). */
        fun addBoard(board: View)
        /** The picker or the small window, added once over the page (under its notices). */
        fun addOverlay(view: View, params: FrameLayout.LayoutParams)
        /** Multiview opens: the one live picture stops (its connection becomes the first picture's) and steps aside. */
        fun enter()
        /** Multiview closed: [channel] plays full screen in the one live player. */
        fun leave(channel: Channel, playlist: Playlist)
        /** The picker closed before Multiview opened: full screen as it was. */
        fun entryCancelled()
        /** [key]'s channel and its playlist, read off the main thread, handed to [then] on it (not at all if gone). */
        fun lookUp(key: ChannelKey, then: (Channel, Playlist) -> Unit)
        fun notice(text: String)
        /** The channel list over the screen ([LiveOverlay]), made once by the page: where a channel is picked. */
        fun list(): LiveOverlay
        /**
         * Multiview's start: the one picture small at the screen's top right, ready for a second ([on]); or full screen
         * again (the start given up). [animate]: it moves there.
         */
        fun readyPicture(on: Boolean, animate: Boolean)
    }

    /** A picture: the channel it shows, its player, its place on the board. */
    private inner class Tile(val id: Int, var channel: Channel, var playlist: Playlist, val picture: MultiviewBoard.Picture) {
        val player = MultiviewPlayer(activity) { if (!gone) refresh(this) }
        /** Its signs asked for again when due (made once, not per event). */
        val again: () -> Unit = { if (!gone) refresh(this) }
        /** Couldn't get a connection (the screen came back and the provider's allowance was used meanwhile). */
        var limited = false
        var gone = false
    }

    /** What the picker is open for. */
    private sealed class Pick {
        class Enter(val channel: Channel, val playlist: Playlist) : Pick()
        object Add : Pick()
        class Change(val tile: Tile) : Pick()
    }

    private val prefs = LivePrefs(activity)
    private val main = Handler(Looper.getMainLooper())
    private val model = MultiviewModel<Tile>(MultiviewRules.MOST)
    private val leases = MultiviewLeases(PlayerHost.budget)
    private val press = PressHold(ViewConfiguration.getLongPressTimeout().toLong())
    private var nextId = 1
    private var board: MultiviewBoard? = null
    private var picker: ChannelPicker? = null
    private var menu: HoldPopup? = null
    private var pickFor: Pick? = null
    /** The pictures' views in the pictures' order, for the board. */
    private val order = ArrayList<MultiviewBoard.Picture>()
    private val rect = RectF()

    /** Multiview shows (from the second channel picked until it's left), whether or not the screen is started. */
    var isOpen = false
        private set

    /** The screen is started: pictures play. */
    private var running = false

    /** Multiview, or a pick for it (the list or the search page), has the keys. */
    val active: Boolean get() = isOpen || pickFor != null

    /** The channel list, once asked for (the page's). */
    private var list: LiveOverlay? = null
    /** What the pick is for, as the search page's heading says. */
    private var pickTitle = ""

    /** The list's answers while it's open for a pick. */
    private val pickList = object : LiveOverlay.Listener {
        override fun onChannel(channel: Channel, scope: app.fireplayer.live.GuideScope?) = picked(channel.key)
        override fun onSearch() = openSearch()
        override fun onClosed() = cancelPicker()
    }

    /** A Back this took the DOWN of: only its UP acts. */
    private var backDown = false

    // ---- Opening and leaving --------------------------------------------------------------------------------------

    /** Full screen's Multiview button: [channel] (playing) will be the first picture; the picker opens for the second. */
    fun begin(channel: Channel, playlist: Playlist) {
        if (active) return
        model.limitTo(MultiviewRules.most(prefs.multiviewMost))
        // Ready for a second picture: the one playing shrinks to the top right, the list opens on the left.
        callbacks.readyPicture(true, animate = true)
        openPicker(Pick.Enter(channel, playlist), ENTER_TITLE)
    }

    private fun enterWith(first: Pick.Enter, ch: Channel, pl: Playlist) {
        if (ch.key == first.channel.key) {
            callbacks.notice(MultiviewRules.alreadyMessage(ch.name))
            return
        }
        // The one picture's connection becomes the first picture's: the second needs room of its own.
        if (PlayerHost.budget.free(pl.id, pl.maxConnections) < 1) {
            callbacks.notice(MultiviewRules.limitMessage(pl.maxConnections))
            return
        }
        closePicker()
        callbacks.enter()
        isOpen = true
        val b = board()
        b.visibility = View.VISIBLE
        b.keepScreenOn = running
        if (running) requestSound()
        watchMemory(true)
        Log.i(TAG, "Multiview: opened with 2 pictures")
        addTile(first.channel, first.playlist)
        addTile(ch, pl)
        relayout(animate = false)
    }

    /** Multiview closes for full screen on [tile]'s channel. */
    private fun leaveTo(tile: Tile) {
        val ch = tile.channel
        val pl = tile.playlist
        closeAll()
        callbacks.leave(ch, pl)
    }

    /** Every picture stops and is let go of, with its connection and the sound; the board hides. Nothing plays after. */
    fun end() {
        if (!active) return
        closeAll()
    }

    private fun closeAll() {
        menu?.close(fade = false, focusBack = false)
        val pick = pickFor
        closePicker(animate = false)
        // Its start given up: the one picture full screen again.
        if (pick is Pick.Enter) callbacks.readyPicture(false, animate = false)
        for (tile in model.tiles) dropTile(tile)
        leases.dropAll()
        model.clear()
        order.clear()
        abandonSound()
        watchMemory(false)
        board?.let {
            it.finishMotion()
            it.keepScreenOn = false
            it.visibility = View.GONE
        }
        if (isOpen) Log.i(TAG, "Multiview: closed")
        isOpen = false
        backDown = false
    }

    /** The screen stopped: every picture's stream, player and connection go, and the sound; the pictures are kept. */
    fun suspend() {
        running = false
        menu?.close(fade = false, focusBack = false)
        // A pick half made is dropped (Multiview's start too: full screen is as it was).
        val pick = pickFor
        closePicker(animate = false)
        if (pick is Pick.Enter) callbacks.readyPicture(false, animate = false)
        backDown = false
        press.cancel()
        if (!isOpen) return
        for (tile in model.tiles) {
            tile.player.close()
            tile.limited = false
            refresh(tile)
        }
        leases.dropAll()
        abandonSound()
        board?.let {
            it.finishMotion()
            it.keepScreenOn = false
        }
    }

    /** The screen started: the pictures play again, each asking for its connection again. */
    fun resume() {
        running = true
        if (!isOpen) return
        board?.keepScreenOn = true
        requestSound()
        for (tile in model.tiles) startTile(tile)
        applyPlayers()
    }

    fun release() {
        closeAll()
        running = false
        picker?.release()
    }

    fun applyLook() {
        menu?.applyLook()
        picker?.applyLook()
        board?.applyLook()
    }

    // ---- Pictures -------------------------------------------------------------------------------------------------

    private fun addTile(ch: Channel, pl: Playlist): Tile {
        val tile = Tile(nextId++, ch, pl, board().take())
        model.add(tile, prefs.multiviewLarge)
        if (running) startTile(tile)
        refresh(tile)
        return tile
    }

    /** [tile]'s channel plays, if its provider's allowance has room; else it says so and nothing is opened. */
    private fun startTile(tile: Tile) {
        val pl = tile.playlist
        if (!leases.take(tile.id, pl.id, pl.maxConnections)) {
            tile.limited = true
            refresh(tile)
            return
        }
        tile.limited = false
        tile.player.open(sourceOf(tile), tile.picture.surface)
    }

    private fun sourceOf(tile: Tile) =
        LiveSession.Source(tile.channel.key.toString(), tile.channel.url, tile.playlist.id, tile.playlist.maxConnections)

    /** [tile] goes: its player first (it lets go of the surface), its connection, then its view. */
    private fun dropTile(tile: Tile) {
        tile.gone = true
        tile.player.release()
        leases.drop(tile.id)
        board?.give(tile.picture)
    }

    private fun remove(tile: Tile) {
        val i = model.tiles.indexOf(tile)
        if (i < 0) return
        val left = model.remove(i)
        dropTile(tile)
        // One picture left: full screen on it.
        if (left != null) {
            leaveTo(left)
            return
        }
        relayout(animate = true)
    }

    private fun addPicture(ch: Channel, pl: Playlist) {
        if (model.count >= model.most) {
            closePicker()
            return
        }
        if (model.tiles.any { it.channel.key == ch.key }) {
            callbacks.notice(MultiviewRules.alreadyMessage(ch.name))
            return
        }
        if (!MultiviewRules.fits(PlayerHost.budget.inUse(pl.id), pl.maxConnections)) {
            callbacks.notice(MultiviewRules.limitMessage(pl.maxConnections))
            return
        }
        closePicker()
        addTile(ch, pl)
        relayout(animate = true)
    }

    private fun changePicture(tile: Tile, ch: Channel, pl: Playlist) {
        if (tile.gone || ch.key == tile.channel.key) {
            closePicker()
            return
        }
        if (model.tiles.any { it !== tile && it.channel.key == ch.key }) {
            callbacks.notice(MultiviewRules.alreadyMessage(ch.name))
            return
        }
        // Its own connection is given back first (it counts once); refused, it keeps playing what it played.
        if (running && !leases.swap(tile.id, pl.id, pl.maxConnections, tile.playlist.maxConnections)) {
            callbacks.notice(MultiviewRules.limitMessage(pl.maxConnections))
            return
        }
        closePicker()
        tile.channel = ch
        tile.playlist = pl
        tile.picture.setMessage(null)
        if (running) {
            tile.limited = false
            tile.player.open(sourceOf(tile), tile.picture.surface)
        }
        refresh(tile)
        applyPlayers()
    }

    /** [tile]'s message and buffering symbol as its player says (on its news, never per frame). */
    private fun refresh(tile: Tile) {
        val pic = tile.picture
        if (tile.limited) {
            pic.setMessage(MultiviewRules.limitMessage(tile.playlist.maxConnections))
            pic.showWaiting(false, false, false, "", tile.again)
            return
        }
        val p = tile.player
        pic.setMessage(p.error)
        val size = p.videoSize
        pic.setVideoShape(size.width, size.height, size.pixelWidthHeightRatio)
        pic.showWaiting(p.waiting, p.reconnecting, p.refused, p.waitText, tile.again)
    }

    /** The pictures to the model's layout ([animate]: those on screen move there), the ring on the selected one. */
    private fun relayout(animate: Boolean) {
        val b = board ?: return
        val layout = layoutNow()
        order.clear()
        for (tile in model.tiles) order += tile.picture
        b.place(order, layout, animate)
        b.select(order, model.selected)
        applyPlayers()
    }

    /** Each picture's memory, video size and sound: only the one heard last selected is heard. */
    private fun applyPlayers() {
        val n = model.count
        val step = maxOf(MultiviewRules.memoryStep(n), memoryLow)
        for ((i, tile) in model.tiles.withIndex()) {
            tile.player.setMemoryStep(step)
            tile.player.limitVideo(MultiviewRules.maxVideo(MultiviewRules.role(i, n, model.featured)))
            tile.player.setVolume(if (i == model.audible && soundHeld) 1f else 0f)
        }
    }

    // ---- Keys -----------------------------------------------------------------------------------------------------

    /** Every key while Multiview or its picker shows (LivePage hands them here first). True when used. */
    fun dispatchKey(event: KeyEvent): Boolean {
        val code = event.keyCode
        val down = event.action == KeyEvent.ACTION_DOWN
        if (picker?.isOpen == true) {
            // Back from the search page: the list again, as it was.
            if (code == KeyEvent.KEYCODE_BACK) return back(event) { closeSearch() }
            // The picker's bar and results have the rest; nothing else opens over it.
            return code in SWALLOWED
        }
        if (pickFor != null) {
            // The list has the D-pad, OK and Back (Back gives the pick up: [cancelPicker]).
            val l = list
            if (l != null && l.isOpen && l.dispatchKey(event)) return true
            return code in SWALLOWED
        }
        if (!isOpen) return false
        val m = menu
        if (m != null && m.isOpen) {
            if (code == KeyEvent.KEYCODE_BACK) return back(event) { m.close() }
            // Its buttons have the selection.
            return code in SWALLOWED
        }
        when (code) {
            KeyEvent.KEYCODE_BACK -> return back(event) { model.backTarget()?.let { leaveTo(it) } }
            KeyEvent.KEYCODE_DPAD_LEFT -> if (down) move(Dir.LEFT)
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (down) move(Dir.RIGHT)
            KeyEvent.KEYCODE_DPAD_UP -> if (down) move(Dir.UP)
            KeyEvent.KEYCODE_DPAD_DOWN -> if (down) move(Dir.DOWN)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> ok(event)
            // Menu: the selected picture's small window, as a held OK.
            KeyEvent.KEYCODE_MENU -> if (down && event.repeatCount == 0) held()
            else -> return code in SWALLOWED
        }
        return true
    }

    /** Back: acts on its release, only for a press that went down here. */
    private fun back(event: KeyEvent, action: () -> Unit): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.repeatCount == 0) backDown = true
        } else if (backDown) {
            backDown = false
            action()
        }
        return true
    }

    private fun move(dir: Dir) {
        press.cancel()
        val b = board ?: return
        if (!model.move(dir, layoutNow())) return
        b.select(order, model.selected)
        applyPlayers()
    }

    /** The model's layout on the screen as it is (the board's size; the window's before the board is first laid out). */
    private fun layoutNow(): MultiviewLayout.Layout {
        val b = board
        val d = t.density
        val w = (b?.width?.takeIf { it > 0 } ?: activity.window.decorView.width) / d
        val h = (b?.height?.takeIf { it > 0 } ?: activity.window.decorView.height) / d
        return model.layout(w, h, LiveWidths.MV_GAP)
    }

    /** OK: a short press acts on its release; held, the small window opens. */
    private fun ok(event: KeyEvent) {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (press.down(event.repeatCount, event.isLongPress, event.eventTime - event.downTime)) held()
            KeyEvent.ACTION_UP -> if (press.up(event.isCanceled)) {
                if (model.onAdd) openPicker(Pick.Add, ADD_TITLE)
                else if (model.ok()) relayout(animate = true)
            }
        }
    }

    /** Held OK (or Menu): the selected picture's small window; on "Add a channel", the picker. */
    private fun held() {
        press.cancel()
        if (model.onAdd) {
            openPicker(Pick.Add, ADD_TITLE)
            return
        }
        val tile = model.tiles.getOrNull(model.selected) ?: return
        val b = board ?: return
        val m = menu()
        b.rectOf(tile.picture, rect)
        // Opens from the picture's middle, inside the screen.
        val anchor = RectF(rect.left, rect.top, rect.centerX(), rect.bottom)
        val g = t.dpf(LiveWidths.MV_GAP)
        val area = RectF(g, g, b.width - g, b.height - g)
        val buttons = MultiviewRules.menu(model.count, model.most).map { c -> HoldPopup.Button(c.label) { choose(c, tile) } }
        m.open(tile.channel.name, buttons, anchor, area)
    }

    private fun choose(c: Choice, tile: Tile) {
        menu?.close(fade = c == Choice.REMOVE)
        if (tile.gone || !isOpen) return
        when (c) {
            Choice.REMOVE -> remove(tile)
            Choice.ADD -> openPicker(Pick.Add, ADD_TITLE)
            Choice.CHANGE -> openPicker(Pick.Change(tile), "Change ${tile.channel.name}")
            Choice.FULL_SCREEN -> leaveTo(tile)
        }
    }

    // ---- The channel picker ---------------------------------------------------------------------------------------

    /**
     * The channel list opens for [pick] (the user: "list first, Search at its top"), on the group of the channel it's
     * for: the playing one at the start, the picture being changed, else the selected picture's.
     */
    private fun openPicker(pick: Pick, title: String) {
        menu?.close(fade = false, focusBack = false)
        press.cancel()
        backDown = false
        picker?.close()
        val near: Pair<Channel, Playlist>? = when (pick) {
            is Pick.Enter -> pick.channel to pick.playlist
            is Pick.Change -> pick.tile.channel to pick.tile.playlist
            Pick.Add -> (model.tiles.getOrNull(model.selected) ?: model.tiles.firstOrNull())?.let { it.channel to it.playlist }
        }
        if (near == null) return
        pickFor = pick
        pickTitle = title
        val l = callbacks.list().also { list = it }
        l.open(near.first, near.second, picking = true, listener = pickList)
    }

    /** Search at the list's top: Live TV search over it, see-through, the channel history under its bar. */
    private fun openSearch() {
        val pick = pickFor ?: return
        backDown = false
        list?.hideForSearch()
        // The channels on screen already (the one playing, at the start) are left out of its channel history.
        val shown: Set<ChannelKey> = if (pick is Pick.Enter) setOf(pick.channel.key) else model.tiles.mapTo(HashSet()) { it.channel.key }
        picker().open(pickTitle, shown)
    }

    /** Back from the search page: the list as it was. */
    private fun closeSearch() {
        picker?.close()
        if (pickFor != null) list?.showAfterSearch()
    }

    /** The pick is done or given up: the list and the search page close. */
    private fun closePicker(animate: Boolean = true) {
        pickFor = null
        picker?.close()
        list?.takeIf { it.isOpen && it.picking }?.close(animate)
    }

    /** Back in the list: Multiview as it was; at Multiview's start, full screen as it was (the picture grows back). */
    private fun cancelPicker() {
        val pick = pickFor
        closePicker()
        if (pick is Pick.Enter) {
            callbacks.readyPicture(false, animate = true)
            callbacks.entryCancelled()
        } else if (isOpen && MultiviewRules.leaveWithoutPick(model.count)) {
            model.tiles.firstOrNull()?.let { leaveTo(it) }
        }
    }

    private fun picked(key: ChannelKey) {
        val pick = pickFor ?: return
        callbacks.lookUp(key) { ch, pl ->
            // Closed, or open for something else, meanwhile.
            if (pickFor !== pick) return@lookUp
            when (pick) {
                is Pick.Enter -> enterWith(pick, ch, pl)
                Pick.Add -> addPicture(ch, pl)
                is Pick.Change -> changePicture(pick.tile, ch, pl)
            }
        }
    }

    // ---- Views, made the first time they're needed ----------------------------------------------------------------

    private fun board(): MultiviewBoard = board ?: MultiviewBoard(activity, t).also {
        board = it
        callbacks.addBoard(it)
    }

    private fun picker(): ChannelPicker = picker ?: ChannelPicker(activity, t).also {
        picker = it
        it.onPick = { key -> picked(key) }
        callbacks.addOverlay(it, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun menu(): HoldPopup = menu ?: HoldPopup(activity, t).also {
        menu = it
        callbacks.addOverlay(it, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.LEFT))
    }

    // ---- Sound: asked for once, for all the pictures --------------------------------------------------------------

    private val audio = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var soundAsked = false
    /** Multiview has the TV's sound (another app hasn't taken it): the selected picture is heard. */
    private var soundHeld = false
    private val soundChange = AudioManager.OnAudioFocusChangeListener { change ->
        // Another app's sound (Alexa answering, a film elsewhere): every picture silent until it's given back.
        soundHeld = change == AudioManager.AUDIOFOCUS_GAIN
        applyPlayers()
    }
    private var soundRequest: Any? = null

    private fun requestSound() {
        if (soundAsked) return
        soundAsked = true
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val r = (soundRequest as? AudioFocusRequest) ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                .setOnAudioFocusChangeListener(soundChange, main)
                .build().also { soundRequest = it }
            audio.requestAudioFocus(r)
        } else {
            @Suppress("DEPRECATION")
            audio.requestAudioFocus(soundChange, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
        soundHeld = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonSound() {
        if (!soundAsked) return
        soundAsked = false
        soundHeld = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (soundRequest as? AudioFocusRequest)?.let { audio.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audio.abandonAudioFocus(soundChange)
        }
    }

    // ---- Memory: Fire OS short of it while Multiview shows ---------------------------------------------------------

    /** The memory step Fire OS last asked for while Multiview showed (0 none): every picture holds at least that. */
    private var memoryLow = 0
    private var memoryWatched = false
    private val memoryWatch = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
            if (level > ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) return
            val step = LiveCushion.stepFor(level)
            if (step <= memoryLow) return
            memoryLow = step
            Log.i(TAG, "Multiview: memory low (level $level), pictures' buffers held to step $step")
            applyPlayers()
        }
        override fun onConfigurationChanged(newConfig: Configuration) {}
        @Deprecated("Deprecated in Java") override fun onLowMemory() {}
    }

    private fun watchMemory(on: Boolean) {
        if (on == memoryWatched) return
        memoryWatched = on
        val app = activity.applicationContext
        if (on) app.registerComponentCallbacks(memoryWatch) else {
            app.unregisterComponentCallbacks(memoryWatch)
            memoryLow = 0
        }
    }

    companion object {
        private const val TAG = "FirePlayerLive"
        const val ENTER_TITLE = "Multiview: pick a channel to watch beside this one"
        const val ADD_TITLE = "Add a channel to Multiview"
        /** Keys Multiview uses up without acting on them, so nothing behind it does (channel, guide, play, menu). */
        private val SWALLOWED = setOf(
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_INFO,
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
        )
    }
}
