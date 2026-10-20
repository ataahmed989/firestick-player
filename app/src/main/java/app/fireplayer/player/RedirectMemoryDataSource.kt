package app.fireplayer.player

import android.net.Uri
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap

/**
 * Goes straight to where the provider last sent a link, instead of asking it again every time.
 *
 * IPTV film links usually point at the provider's login server, which answers every request by sending the player on
 * to the server that actually has the file. An MKV takes three requests before its picture appears (its start, its
 * index at the end, then back to the start), and every skip one more, so each would also wait on the login server.
 * If the remembered address stops working (it often carries a pass that expires), the original link is used again;
 * a link whose new address keeps failing the first time it's reused is asked every time, as before.
 *
 * When the film keeps arriving slower than it plays, [ServerSwitch] has its connection break off and start again
 * through the provider, which may send it to a less busy server.
 *
 * [readOnly] (the skip preview) goes where the film was sent but never changes what's remembered: the preview
 * being turned away, often only because the film has the account's one connection, says nothing about the address.
 */
@OptIn(UnstableApi::class)
class RedirectMemoryDataSource(
    private val upstream: DataSource,
    private val serverSwitch: ServerSwitch?,
    private val readOnly: Boolean = false,
) : DataSource {

    /** Where a link was last sent, and whether going there directly has worked yet. */
    private class Sent(val uri: Uri, val reused: Boolean)

    // The link being read, while it's read from a server the provider sent us on to.
    private var redirectedLink: String? = null
    // How much has come since this connection opened.
    private var readSinceOpen = 0L

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        redirectedLink = null
        readSinceOpen = 0
        // A move asked for between connections: this one simply goes through the provider, nothing to break off.
        if (serverSwitch?.take() == true) remembered.remove(dataSpec.uri.toString())
        if (serverSwitch != null) filmOpenedAt = SystemClock.elapsedRealtime()
        val length = openRemembering(dataSpec)
        if (upstream.uri != dataSpec.uri) {
            redirectedLink = dataSpec.uri.toString()
            // Subtitles opened with the film come straight from their own server; they don't say the film can't move.
            serverSwitch?.possible = true
        }
        return length
    }

    private fun openRemembering(dataSpec: DataSpec): Long {
        val link = dataSpec.uri.toString()
        // An address that already turned the preview away isn't tried by it again (until the film is sent elsewhere).
        val sent = remembered[link]?.takeUnless { readOnly && previewSkips[link] == it.uri }
        if (sent != null) {
            try {
                val length = upstream.open(dataSpec.withUri(sent.uri))
                if (!sent.reused && !readOnly) {
                    remembered[link] = Sent(sent.uri, reused = true)
                    misses.remove(link)
                }
                return length
            } catch (e: HttpDataSource.HttpDataSourceException) {
                // Cancelled, or asking for more than the file has: the original link would do no better. A server that
                // doesn't answer in time is different: that's what going back to the provider is for.
                val cause = e.cause
                val cancelled = cause is InterruptedIOException && cause !is SocketTimeoutException
                if (cancelled || DataSourceException.isCausedByPositionOutOfRange(e)) throw e
                upstream.close()
                if (readOnly) {
                    if (previewSkips.size >= MAX_REMEMBERED) previewSkips.clear()
                    previewSkips[link] = sent.uri
                    return upstream.open(dataSpec)
                }
                remembered.remove(link)
                // Once could be the server turning away a reconnect for a moment; twice, the address only works once.
                if (!sent.reused) misses[link] = (misses[link] ?: 0) + 1
            }
        }
        val length = upstream.open(dataSpec)
        val landed = upstream.uri
        if (!readOnly && landed != null && landed != dataSpec.uri && (misses[link] ?: 0) < MAX_MISSES) {
            // A live stream's parts each have their own link; don't let those pile up.
            if (remembered.size >= MAX_REMEMBERED) remembered.clear()
            remembered[link] = Sent(landed, reused = false)
        }
        return length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val link = redirectedLink
        // The film is coming too slowly from this server: forget it and break off, and the player reconnects where it
        // left off, through the provider again, which may send it somewhere quicker. Only once this connection has
        // brought something, so the player counts it as a hiccup on the way rather than a link that keeps failing.
        if (link != null && readSinceOpen >= MIN_READ_BEFORE_MOVE && serverSwitch?.take() == true) {
            remembered.remove(link)
            throw IOException("Moving to another of the provider's servers")
        }
        val read = try {
            upstream.read(buffer, offset, length)
        } catch (e: IOException) {
            // The server dropped the film or went quiet: the reconnect asks the provider again rather than waiting on
            // the same server. Not when the read was only cut short by a jump.
            val cancelled = e.cause is InterruptedIOException && e.cause !is SocketTimeoutException
            if (link != null && !readOnly && !cancelled) remembered.remove(link)
            throw e
        }
        if (read > 0) readSinceOpen += read
        return read
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        redirectedLink = null
        upstream.close()
    }

    /** [serverSwitch] only for the film's own connection; [readOnly] for the skip preview's. */
    class Factory(
        private val upstream: DataSource.Factory,
        private val serverSwitch: ServerSwitch?,
        private val readOnly: Boolean = false,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = RedirectMemoryDataSource(upstream.createDataSource(), serverSwitch, readOnly)
    }

    companion object {
        // When the film last opened a connection (elapsedRealtime).
        @Volatile private var filmOpenedAt = 0L

        /** Whether the film opened a connection within the last [ms]; a server allowing one may have cut the preview off for it. */
        fun filmOpenedWithin(ms: Long): Boolean = SystemClock.elapsedRealtime() - filmOpenedAt < ms

        // Shared by the film and the skip preview, which open the same links.
        private val remembered = ConcurrentHashMap<String, Sent>()
        // How often in a row a link's new address failed the first time it was reused.
        private val misses = ConcurrentHashMap<String, Int>()
        // Remembered addresses that turned the skip preview away; it goes through the provider for those.
        private val previewSkips = ConcurrentHashMap<String, Uri>()
        private const val MAX_MISSES = 2
        private const val MAX_REMEMBERED = 200
        private const val MIN_READ_BEFORE_MOVE = 256 * 1024
    }
}
