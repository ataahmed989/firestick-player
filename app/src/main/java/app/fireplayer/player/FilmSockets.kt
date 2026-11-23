package app.fireplayer.player

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import android.net.Uri
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory

/**
 * The film's own TLS connections get a wider receive window than the platform gives a socket.
 *
 * Left to itself the TV's kernel starts a socket's receive buffer at 1 MiB and grows it to at most 2 MiB, which lets
 * about 1 MiB be in flight at a time: at the 86-106 ms a round trip takes while the film loads, one connection can't
 * carry more than 79-95 Mb/s, whatever the server and the line could do. Each film socket asks for [WIDE_BYTES], or
 * [NARROW_BYTES] on a TV short of memory ([lowMemory], [squeezed]). The kernel doubles the size asked for (the other
 * half covers its own bookkeeping), so a socket the player stops reading can hold up to twice that much in the kernel,
 * outside the film's own budget: up to about 32 MiB with four film sockets open while loading is held (two connections,
 * each with a span asked ahead). [WIDE_BYTES] still lets about 4 MiB be in flight: some 300 Mb/s for one connection at
 * the 106 ms a round trip takes, and about 88 Mb/s at the 379 ms it reaches at worst.
 *
 * The socket is set up after its TCP connection is made and before its TLS handshake, in the one place every HTTPS
 * connection's socket passes through: the platform's SSL socket factory ([install] wraps it once; the wrapper changes
 * nothing for any other connection). Only sockets made while [film] runs, on the film's own threads, are widened.
 * The window clamp is raised first (on kernels older than 6.x it was fixed at connect time from the 1 MiB default, and a
 * receive buffer set after connect alone would hold the window to about 512 KiB); only once that took is the receive
 * buffer set. A call the TV refuses leaves the socket as the platform made it. What the TV granted is logged once (no
 * host, no link).
 *
 * [created] counts the film sockets made, for the bench line's "new=" ([TwoRanges.rates]): a new connection for every
 * range shows there as a count close to the number of requests.
 */
object FilmSockets {
    const val WIDE_BYTES = 4 shl 20
    const val NARROW_BYTES = 2 shl 20

    /** Linux's TCP_WINDOW_CLAMP (android.system.OsConstants doesn't name it). */
    private const val TCP_WINDOW_CLAMP = 10

    /** The TV had little memory free as the film started, or is a low-memory TV: sockets ask for [NARROW_BYTES]. */
    @Volatile var lowMemory = false

    /** The film's buffer is squeezed for the TV's memory right now ([FilmMemory.capFor]): the same. */
    @Volatile var squeezed = false

    /** What a film socket made now asks for, in bytes. */
    val receiveBytes: Int get() = if (lowMemory || squeezed) NARROW_BYTES else WIDE_BYTES

    private val made = AtomicLong()
    private val logged = AtomicBoolean()
    private val onFilm = ThreadLocal<Boolean>()
    @Volatile private var wide: SSLSocketFactory? = null

    /** Film sockets made since the app started. */
    val created: Long get() = made.get()

    /** The one wrapping factory, installed as the platform's default the first time: film connections set it too. */
    @Synchronized
    fun install(): SSLSocketFactory {
        wide?.let { return it }
        val current = HttpsURLConnection.getDefaultSSLSocketFactory()
        val w = current as? Wide ?: Wide(current)
        HttpsURLConnection.setDefaultSSLSocketFactory(w)
        wide = w
        return w
    }

    /** Runs [block] (which connects) as the film's: a socket it makes is widened. */
    fun <T> film(block: () -> T): T {
        if (onFilm.get() == true) return block()
        onFilm.set(true)
        try {
            return block()
        } finally {
            onFilm.set(false)
        }
    }

    private fun widen(socket: Socket) {
        made.incrementAndGet()
        val want = receiveBytes
        val clamped = try {
            ParcelFileDescriptor.fromSocket(socket).use { fd ->
                Os.setsockoptInt(fd.fileDescriptor, OsConstants.IPPROTO_TCP, TCP_WINDOW_CLAMP, want)
            }
            true
        } catch (e: Exception) {
            false
        }
        // Only with the clamp raised: a receive buffer set alone after connect would narrow the window instead.
        val set = clamped && try {
            socket.receiveBufferSize = want
            true
        } catch (e: Exception) {
            false
        }
        if (!logged.compareAndSet(false, true)) return
        val got = try {
            socket.receiveBufferSize
        } catch (e: Exception) {
            -1
        }
        Log.i(TwoRanges.TAG, "film socket: asked for ${want shr 20} MiB, " + when {
            !clamped -> "window clamp refused, left as the platform made it"
            !set -> "window clamp raised, receive buffer refused"
            else -> "receive buffer ${got shr 10} KiB, window clamp raised"
        })
    }

    private class Wide(private val inner: SSLSocketFactory) : SSLSocketFactory() {
        override fun getDefaultCipherSuites(): Array<String> = inner.defaultCipherSuites

        override fun getSupportedCipherSuites(): Array<String> = inner.supportedCipherSuites

        // The platform's HTTPS connection: its TCP socket is connected, the TLS handshake not started.
        override fun createSocket(s: Socket, host: String?, port: Int, autoClose: Boolean): Socket {
            if (onFilm.get() == true) widen(s)
            return inner.createSocket(s, host, port, autoClose)
        }

        override fun createSocket(): Socket = inner.createSocket()

        override fun createSocket(host: String?, port: Int): Socket = inner.createSocket(host, port)

        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
            inner.createSocket(host, port, localHost, localPort)

        override fun createSocket(host: InetAddress?, port: Int): Socket = inner.createSocket(host, port)

        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
            inner.createSocket(address, port, localAddress, localPort)
    }

    /**
     * The film's connection as it opens, run as the film's ([film]): "Faster downloads" Off reads the film with this
     * one request, and its socket gets the wider window too. Everything else is passed on as it is.
     */
    @OptIn(UnstableApi::class)
    class Opening(private val upstream: DataSource) : DataSource {
        override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

        override fun open(dataSpec: DataSpec): Long = film { upstream.open(dataSpec) }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = upstream.read(buffer, offset, length)

        override fun getUri(): Uri? = upstream.uri

        override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

        override fun close() = upstream.close()

        class Factory(private val upstream: DataSource.Factory) : DataSource.Factory {
            override fun createDataSource(): DataSource = Opening(upstream.createDataSource())
        }
    }
}
