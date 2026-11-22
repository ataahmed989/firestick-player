package app.fireplayer.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * A connection that shares the film's line while it's open (the skip preview's): the film arriving slower meanwhile isn't
 * held against its server ([ServerSwitch.sharing]). Opened and closed on one thread at a time ([IdleCloseDataSource]'s
 * lock); every open is matched by one close, whether or not it opened.
 */
@OptIn(UnstableApi::class)
class SharedLineDataSource(private val upstream: DataSource) : DataSource {
    private var sharing = false

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        if (!sharing) {
            sharing = true
            ServerSwitch.sharing(true)
        }
        return upstream.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = upstream.read(buffer, offset, length)

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        try {
            upstream.close()
        } finally {
            if (sharing) {
                sharing = false
                ServerSwitch.sharing(false)
            }
        }
    }
}
