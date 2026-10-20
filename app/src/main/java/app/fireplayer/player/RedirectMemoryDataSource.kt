package app.fireplayer.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import java.io.InterruptedIOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Goes straight to where the provider last sent a link, instead of asking it again every time.
 *
 * IPTV film links usually point at the provider's login server, which answers every request by sending the player on
 * to the server that actually has the file. An MKV takes three requests before its picture appears (its start, its
 * index at the end, then back to the start), and every skip one more, so each would also wait on the login server.
 * If the remembered address stops working (it often carries a pass that expires), the original link is used again;
 * a link whose new address keeps failing the first time it's reused is asked every time, as before.
 */
@OptIn(UnstableApi::class)
class RedirectMemoryDataSource(private val upstream: DataSource) : DataSource {

    /** Where a link was last sent, and whether going there directly has worked yet. */
    private class Sent(val uri: Uri, val reused: Boolean)

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        val link = dataSpec.uri.toString()
        val sent = remembered[link]
        if (sent != null) {
            try {
                val length = upstream.open(dataSpec.withUri(sent.uri))
                if (!sent.reused) {
                    remembered[link] = Sent(sent.uri, reused = true)
                    misses.remove(link)
                }
                return length
            } catch (e: HttpDataSource.HttpDataSourceException) {
                // Cancelled, or asking for more than the file has: the original link would do no better.
                if (e.cause is InterruptedIOException || DataSourceException.isCausedByPositionOutOfRange(e)) throw e
                upstream.close()
                remembered.remove(link)
                // Once could be the server turning away a reconnect for a moment; twice, the address only works once.
                if (!sent.reused) misses[link] = (misses[link] ?: 0) + 1
            }
        }
        val length = upstream.open(dataSpec)
        val landed = upstream.uri
        if (landed != null && landed != dataSpec.uri && (misses[link] ?: 0) < MAX_MISSES) {
            // A live stream's parts each have their own link; don't let those pile up.
            if (remembered.size >= MAX_REMEMBERED) remembered.clear()
            remembered[link] = Sent(landed, reused = false)
        }
        return length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = upstream.read(buffer, offset, length)

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() = upstream.close()

    class Factory(private val upstream: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = RedirectMemoryDataSource(upstream.createDataSource())
    }

    private companion object {
        // Shared by the film and the skip preview, which open the same links.
        val remembered = ConcurrentHashMap<String, Sent>()
        // How often in a row a link's new address failed the first time it was reused.
        val misses = ConcurrentHashMap<String, Int>()
        const val MAX_MISSES = 2
        const val MAX_REMEMBERED = 200
    }
}
