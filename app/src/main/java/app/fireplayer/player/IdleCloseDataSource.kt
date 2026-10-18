package app.fireplayer.player

import android.net.Uri
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Lets go of the connection while nothing reads from it, and reconnects where it left off when reading resumes.
 *
 * The skip preview loads a moment of video at each jump target and then waits, often for good. Left open, its
 * connection would hold one of the few an IPTV server allows (the film needs it when it jumps) and keep data
 * coming that nobody wants.
 */
@OptIn(UnstableApi::class)
class IdleCloseDataSource(private val upstream: DataSource, private val idleMs: Long) : DataSource {

    private val lock = ReentrantLock()
    private var spec: DataSpec? = null
    private var bytesRead = 0L
    private var connected = false
    private var lastReadAt = 0L
    private var uri: Uri? = null
    private var headers: Map<String, List<String>> = emptyMap()

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long = lock.withLock {
        val length = upstream.open(dataSpec)
        spec = dataSpec
        bytesRead = 0
        connected = true
        uri = upstream.uri
        headers = upstream.responseHeaders
        lastReadAt = SystemClock.elapsedRealtime()
        active.add(this)
        startWatching()
        length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = lock.withLock {
        val current = spec ?: throw IOException("Read from a closed source")
        if (!connected) {
            upstream.open(current.subrange(bytesRead))
            connected = true
        }
        val read = upstream.read(buffer, offset, length)
        if (read > 0) bytesRead += read
        lastReadAt = SystemClock.elapsedRealtime()
        read
    }

    override fun getUri(): Uri? = uri

    override fun getResponseHeaders(): Map<String, List<String>> = headers

    override fun close() = lock.withLock {
        active.remove(this)
        spec = null
        connected = false
        upstream.close()
    }

    /** From the watcher thread. A read in progress holds the lock, so a connection that's busy is never cut. */
    private fun closeIfIdle(now: Long) {
        if (!lock.tryLock()) return
        try {
            if (connected && now - lastReadAt >= idleMs) {
                connected = false
                try {
                    upstream.close()
                } catch (_: IOException) {
                    // Already gone; the next read reconnects either way.
                }
            }
        } finally {
            lock.unlock()
        }
    }

    private companion object {
        val active: MutableSet<IdleCloseDataSource> = ConcurrentHashMap.newKeySet()
        var watcher: ScheduledExecutorService? = null

        @Synchronized
        fun startWatching() {
            if (watcher != null) return
            watcher = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "IdleClose").apply { isDaemon = true } }
                .apply {
                    scheduleWithFixedDelay({
                        val now = SystemClock.elapsedRealtime()
                        for (source in active) source.closeIfIdle(now)
                    }, 200, 200, TimeUnit.MILLISECONDS)
                }
        }
    }
}
