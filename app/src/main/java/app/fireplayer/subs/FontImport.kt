package app.fireplayer.subs

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.Window
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import app.fireplayer.R
import app.fireplayer.data.Prefs
import app.fireplayer.ui.Rows
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.zip.ZipInputStream

/**
 * Lets the user send a font file from their phone to the Fire TV over Wi-Fi.
 * The TV shows an address; the phone opens it, picks a .ttf/.otf/.zip, and uploads it.
 * The font is kept only in the app's private storage.
 */
class FontImport(private val activity: Activity, private val prefs: Prefs, private val onImported: () -> Unit) {

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var stopped = false

    fun show() {
        val rows = Rows(activity)
        val dialog = Dialog(activity).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }
        val text = ContextCompat.getColor(activity, R.color.text)
        val muted = ContextCompat.getColor(activity, R.color.text_muted)
        val accent = ContextCompat.getColor(activity, R.color.accent)
        fun label(value: String, size: Float, color: Int, font: Int) = TextView(activity).apply {
            this.text = value
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            typeface = ResourcesCompat.getFont(activity, font)
            gravity = Gravity.CENTER_HORIZONTAL
            setLineSpacing(3f * rows.density, 1f)
        }

        val address = localAddress()
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_popup)
            setPadding(rows.dp(26), rows.dp(22), rows.dp(26), rows.dp(20))
        }
        val status = label("Waiting for your phone…", 12f, muted, R.font.figtree_500)
        box.addView(label("Send a font from your phone", 16f, text, R.font.figtree_700), rows.listParams(0, 6))
        box.addView(
            label("On your phone, connected to the same Wi-Fi, open this address and choose the font file or zip.", 12f, muted, R.font.figtree_500),
            rows.listParams(0, 14),
        )
        box.addView(
            label(if (address != null) "http://$address:$PORT" else "Connect the Fire TV to Wi-Fi first", 22f, accent, R.font.figtree_700),
            rows.listParams(0, 14),
        )
        box.addView(status, rows.listParams(0, 12))
        val done = rows.base().apply {
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_pill)
            addView(rows.text("Done"))
            setOnClickListener { dialog.dismiss() }
        }
        box.addView(done, LinearLayout.LayoutParams(rows.dp(140), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })

        dialog.setContentView(box)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0.4f)
            attributes = attributes.apply { width = rows.dp(420) }
        }
        dialog.setOnDismissListener { stop() }
        dialog.show()
        done.requestFocus()

        if (address != null) start { message, ok ->
            status.text = message
            status.setTextColor(if (ok) accent else muted)
            if (ok) onImported()
        }
    }

    private fun start(report: (String, Boolean) -> Unit) {
        Thread {
            // Only one upload page at a time; close any left over from a window that didn't close cleanly.
            synchronized(FontImport) {
                runCatching { running?.close() }
                running = null
            }
            val socket = runCatching {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(java.net.InetSocketAddress(PORT))
                }
            }.getOrElse {
                handler.post { report("Couldn't open the address. Close other apps and try again.", false) }
                return@Thread
            }
            synchronized(FontImport) {
                if (stopped) {
                    runCatching { socket.close() }
                    return@Thread
                }
                running = socket
            }
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                runCatching { handle(client, report) }
                runCatching { client.close() }
            }
        }.start()
    }

    private fun stop() {
        synchronized(FontImport) {
            stopped = true
            runCatching { running?.close() }
            running = null
        }
    }

    private fun handle(client: Socket, report: (String, Boolean) -> Unit) {
        client.soTimeout = 30_000
        val input = client.getInputStream().buffered()
        val requestLine = readLine(input) ?: return
        var length = 0
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            if (line.startsWith("Content-Length:", ignoreCase = true)) length = line.substringAfter(':').trim().toIntOrNull() ?: 0
        }
        val (method, path) = requestLine.split(' ').let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "/") }

        if (method == "POST" && path.startsWith("/upload")) {
            if (length <= 0 || length > MAX_BYTES) {
                respond(client, 413, "Too big")
                return
            }
            val body = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(body, read, length - read)
                if (n < 0) break
                read += n
            }
            val name = java.net.URLDecoder.decode(path.substringAfter("name=", ""), "UTF-8")
            handler.post { report("Receiving $name…", false) }
            val result = runCatching { save(name, body) }
            result.onSuccess { fontName ->
                handler.post { report("$fontName is ready for your subtitles.", true) }
                runCatching { respond(client, 200, "ok") }
            }.onFailure {
                respond(client, 400, it.message ?: "That file isn't a font.")
                handler.post { report(it.message ?: "That file isn't a font.", false) }
            }
        } else {
            respond(client, 200, PAGE, "text/html; charset=utf-8")
        }
    }

    /** Stores the font (or the best weight from a zip) and returns its display name. */
    private fun save(name: String, body: ByteArray): String {
        val (fontBytes, fontName) = if (name.endsWith(".zip", ignoreCase = true)) pickFromZip(body) else body to name
        val dir = File(activity.filesDir, "fonts").apply { mkdirs() }
        val temp = File(dir, "incoming.ttf")
        temp.writeBytes(fontBytes)
        // Make sure Android can actually use it before replacing the current font.
        val ok = runCatching { Typeface.createFromFile(temp) }.getOrNull()?.let { it != Typeface.DEFAULT } ?: false
        if (!ok) {
            temp.delete()
            error("That file isn't a font Android can use.")
        }
        val target = customFontFile(activity)
        target.delete()
        temp.renameTo(target)
        val display = fullName(fontBytes) ?: prettyFileName(fontName)
        prefs.customFontName = display
        return display
    }

    /** "NetflixSans-Md.otf" → "Netflix Sans Medium", for fonts without a readable name. */
    private fun prettyFileName(file: String): String {
        val weights = mapOf("Md" to "Medium", "Rg" to "Regular", "Bd" to "Bold", "Lt" to "Light", "Bk" to "Black", "Th" to "Thin")
        val base = file.substringAfterLast('/').substringBeforeLast('.')
        return base.split('-', '_', ' ').filter { it.isNotEmpty() }
            .joinToString(" ") { part -> weights[part] ?: part.replace(Regex("(?<=[a-z])(?=[A-Z])"), " ") }
    }

    /** The font's own full name from its 'name' table (ID 4), e.g. "Netflix Sans Medium". */
    private fun fullName(font: ByteArray): String? = runCatching {
        val buf = java.nio.ByteBuffer.wrap(font)
        val tables = buf.getShort(4).toInt() and 0xFFFF
        var nameOffset = -1
        for (t in 0 until tables) {
            val record = 12 + t * 16
            val tag = String(font, record, 4, Charsets.US_ASCII)
            if (tag == "name") nameOffset = buf.getInt(record + 8)
        }
        if (nameOffset < 0) return@runCatching null
        val count = buf.getShort(nameOffset + 2).toInt() and 0xFFFF
        val strings = nameOffset + (buf.getShort(nameOffset + 4).toInt() and 0xFFFF)
        var best: String? = null
        for (r in 0 until count) {
            val rec = nameOffset + 6 + r * 12
            val platform = buf.getShort(rec).toInt() and 0xFFFF
            val nameId = buf.getShort(rec + 6).toInt() and 0xFFFF
            if (nameId != 4) continue
            val len = buf.getShort(rec + 8).toInt() and 0xFFFF
            val off = strings + (buf.getShort(rec + 10).toInt() and 0xFFFF)
            val value = if (platform == 3 || platform == 0) String(font, off, len, Charsets.UTF_16BE) else String(font, off, len, Charsets.ISO_8859_1)
            if (value.isNotBlank()) {
                best = value.trim()
                if (platform == 3) break
            }
        }
        best
    }.getOrNull()

    /** Prefers a Medium or Regular upright weight, which read best as subtitles. */
    private fun pickFromZip(zip: ByteArray): Pair<ByteArray, String> {
        val fonts = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(zip)).use { z ->
            while (true) {
                val entry = z.nextEntry ?: break
                val n = entry.name
                if (!entry.isDirectory && (n.endsWith(".ttf", true) || n.endsWith(".otf", true)) && !n.contains("__MACOSX")) {
                    fonts[n] = z.readBytes()
                }
            }
        }
        if (fonts.isEmpty()) error("No fonts in that zip.")
        val upright = fonts.keys.filterNot { Regex("""(?i)(italic|It\.|Obl)""").containsMatchIn(it.substringAfterLast('/')) }
        val preference = listOf("Md", "Medium", "Rg", "Regular", "Bd", "Bold")
        val best = preference.firstNotNullOfOrNull { weight ->
            upright.firstOrNull { Regex("""(?i)[-_ ]$weight\.(ttf|otf)$""").containsMatchIn(it) }
        } ?: upright.firstOrNull() ?: fonts.keys.first()
        return fonts.getValue(best) to best
    }

    private fun respond(client: Socket, code: Int, body: String, type: String = "text/plain; charset=utf-8") {
        val bytes = body.toByteArray()
        val status = when (code) { 200 -> "OK"; 413 -> "Payload Too Large"; else -> "Bad Request" }
        val head = "HTTP/1.1 $code $status\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        client.getOutputStream().apply {
            write(head.toByteArray())
            write(bytes)
            flush()
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 8192) return sb.toString()
        }
    }

    private fun localAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()

    companion object {
        const val PORT = 8765
        private const val MAX_BYTES = 20 * 1024 * 1024

        /** The upload page currently listening, if any. */
        private var running: ServerSocket? = null

        fun customFontFile(activity: android.content.Context) = File(File(activity.filesDir, "fonts"), "subtitle_font")

        private val PAGE = """
<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<title>FirePlayer font</title>
<style>
body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;background:#0b0e12;color:#f4f2ee;font:16px -apple-system,system-ui,sans-serif}
.card{width:min(88vw,380px);padding:28px 24px;border-radius:20px;background:#15191f;border:1px solid #ffffff1f;text-align:center}
h1{font-size:20px;margin:0 0 8px}p{color:#c9cdd0;margin:0 0 22px;line-height:1.4}
label{display:block;padding:14px;border-radius:12px;background:#3d8bff;color:#fff;font-weight:600}
input{display:none}#s{margin-top:16px;color:#c9cdd0;min-height:22px}
</style></head><body><div class="card"><h1>Send a subtitle font</h1>
<p>Choose a .ttf, .otf or a .zip of fonts. FirePlayer keeps it only on your Fire TV.</p>
<label>Choose file<input id="f" type="file" accept=".ttf,.otf,.zip"></label><div id="s"></div></div>
<script>
const s=document.getElementById('s');
document.getElementById('f').onchange=async e=>{const f=e.target.files[0];if(!f)return;s.textContent='Sending…';
try{const r=await fetch('/upload?name='+encodeURIComponent(f.name),{method:'POST',body:f});
s.textContent=r.ok?'Done. Look at your TV.':await r.text();}catch(x){s.textContent='Couldn\'t reach the TV.';}};
</script></body></html>
""".trimIndent()
    }
}
