package app.fireplayer.titles

import java.io.InputStream
import java.io.OutputStream

/**
 * Copies a provider's list (a JSON array of objects, such as get_vod_streams) from [input] to [output] while it
 * streams, so a list of 150,000 films is never held whole. Every byte goes out as it came, except the "name" (and
 * "title", when there is one) of an object [rename] gives a new name to.
 */
class JsonList(private val input: InputStream, private val output: OutputStream) {

    /** One object of the list: its top-level fields that matter here, read on demand. */
    class Item internal constructor(private val bytes: ByteArray, private val fields: Map<String, IntRange>) {

        /** The field's value as text: a string unescaped, a number as written; null when missing or null. */
        fun text(key: String): String? {
            val span = fields[key] ?: return null
            if (bytes[span.first] == QUOTE) return decode(bytes, span.first, span.last)
            val raw = String(bytes, span.first, span.last - span.first + 1, Charsets.UTF_8)
            return raw.takeIf { it != "null" }
        }

        /** The field as a whole number ("123" or 123); 0 when it isn't one. */
        fun number(key: String): Int = text(key)?.trim()?.toIntOrNull() ?: 0
    }

    private val inBuf = ByteArray(64 * 1024)
    private var inPos = 0
    private var inLen = 0
    private var obj = ByteArray(8 * 1024)
    private var objLen = 0

    /** Copies the whole list; anything that isn't a list ("{"error":…}", an empty answer) is copied as it is. */
    fun copy(rename: (Item) -> String?) {
        var c = read()
        while (c != -1 && isSpace(c)) {
            output.write(c)
            c = read()
        }
        if (c != '['.code) {
            copyRest(c)
            return
        }
        output.write(c)
        while (true) {
            c = read()
            when {
                c == -1 -> return
                c == '{'.code -> {
                    readObject()
                    writeObject(rename)
                }
                c == ']'.code -> {
                    output.write(c)
                    copyRest(read())
                    return
                }
                // Spaces and commas between items, or (never seen) something other than an object.
                else -> output.write(c)
            }
        }
    }

    private fun read(): Int {
        if (inPos == inLen) {
            inLen = input.read(inBuf)
            inPos = 0
            if (inLen <= 0) {
                inLen = 0
                return -1
            }
        }
        return inBuf[inPos++].toInt() and 0xff
    }

    private fun copyRest(first: Int) {
        if (first == -1) return
        output.write(first)
        output.write(inBuf, inPos, inLen - inPos)
        inPos = inLen
        input.copyTo(output, 64 * 1024)
    }

    /** Reads one object, its "{" already read, into [obj]. */
    private fun readObject() {
        objLen = 0
        append('{'.code)
        var depth = 1
        var inString = false
        var escaped = false
        while (depth > 0) {
            val c = read()
            if (c == -1) return
            append(c)
            when {
                escaped -> escaped = false
                inString -> if (c == '\\'.code) escaped = true else if (c == '"'.code) inString = false
                c == '"'.code -> inString = true
                c == '{'.code || c == '['.code -> depth++
                c == '}'.code || c == ']'.code -> depth--
            }
        }
    }

    private fun append(c: Int) {
        if (objLen == obj.size) obj = obj.copyOf(obj.size * 2)
        obj[objLen++] = c.toByte()
    }

    private fun writeObject(rename: (Item) -> String?) {
        val fields = fields(obj, objLen)
        val newName = rename(Item(obj, fields))
        if (newName == null) {
            output.write(obj, 0, objLen)
            return
        }
        val spans = listOfNotNull(fields["name"], fields["title"]).sortedBy { it.first }
        val encoded = encode(newName)
        var at = 0
        for (span in spans) {
            output.write(obj, at, span.first - at)
            output.write(encoded)
            at = span.last + 1
        }
        output.write(obj, at, objLen - at)
    }

    companion object {
        private const val QUOTE = '"'.code.toByte()
        private const val BACKSLASH = '\\'.code.toByte()

        /** The fields read from each object. */
        private val WANTED = setOf(
            "name", "title", "stream_id", "series_id", "category_id", "tmdb", "tmdb_id", "year", "releaseDate", "release_date",
        )
        private const val LONGEST_WANTED = 12

        private fun isSpace(c: Int) = c == ' '.code || c == '\n'.code || c == '\r'.code || c == '\t'.code

        /** Where the values of the [WANTED] top-level fields of the object in [b] lie. */
        internal fun fields(b: ByteArray, len: Int): Map<String, IntRange> {
            val found = HashMap<String, IntRange>(8)
            var i = 1
            while (i < len) {
                while (i < len && isSpace(b[i].toInt())) i++
                if (i >= len || b[i] != QUOTE) break
                val keyEnd = stringEnd(b, i, len)
                val keyLength = keyEnd - i - 1
                val key = if (keyLength in 1..LONGEST_WANTED) String(b, i + 1, keyLength, Charsets.US_ASCII) else null
                i = keyEnd + 1
                while (i < len && (isSpace(b[i].toInt()) || b[i] == ':'.code.toByte())) i++
                val valueStart = i
                i = valueEnd(b, i, len)
                if (key != null && key in WANTED) found[key] = valueStart until i
                while (i < len && (isSpace(b[i].toInt()) || b[i] == ','.code.toByte())) i++
            }
            return found
        }

        /** The index of the quote that closes the string opening at [start]. */
        private fun stringEnd(b: ByteArray, start: Int, len: Int): Int {
            var i = start + 1
            while (i < len) {
                when (b[i]) {
                    BACKSLASH -> i++
                    QUOTE -> return i
                }
                i++
            }
            return len - 1
        }

        /** Just past the value starting at [start]: a string, number, literal, object or array. */
        private fun valueEnd(b: ByteArray, start: Int, len: Int): Int {
            if (start >= len) return len
            if (b[start] == QUOTE) return stringEnd(b, start, len) + 1
            var i = start
            var depth = 0
            while (i < len) {
                val c = b[i].toInt().toChar()
                when {
                    c == '"' -> i = stringEnd(b, i, len)
                    c == '{' || c == '[' -> depth++
                    c == '}' || c == ']' -> {
                        if (depth == 0) return i
                        depth--
                        if (depth == 0) return i + 1
                    }
                    c == ',' && depth == 0 -> return i
                }
                i++
            }
            return len
        }

        /** The JSON string from the quote at [from] to the quote at [to], unescaped. */
        internal fun decode(b: ByteArray, from: Int, to: Int): String {
            val out = StringBuilder(to - from)
            var i = from + 1
            var run = i
            while (i < to) {
                if (b[i] != BACKSLASH) {
                    i++
                    continue
                }
                if (i > run) out.append(String(b, run, i - run, Charsets.UTF_8))
                val e = if (i + 1 < to) b[i + 1].toInt().toChar() else '\\'
                when (e) {
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    'r' -> out.append('\r')
                    'b' -> out.append('\b')
                    'f' -> out.append('\u000C')
                    'u' -> {
                        val hex = if (i + 6 <= to) String(b, i + 2, 4, Charsets.US_ASCII) else ""
                        hex.toIntOrNull(16)?.let { out.append(it.toChar()) }
                        i += 4
                    }
                    else -> out.append(e)
                }
                i += 2
                run = i
            }
            if (to > run) out.append(String(b, run, to - run, Charsets.UTF_8))
            return out.toString()
        }

        /** [s] as a JSON string, quotes included, in UTF-8. */
        fun encode(s: String): ByteArray {
            val out = StringBuilder(s.length + 2).append('"')
            for (ch in s) {
                when {
                    ch == '"' -> out.append("\\\"")
                    ch == '\\' -> out.append("\\\\")
                    ch < ' ' -> out.append(String.format("\\u%04x", ch.code))
                    else -> out.append(ch)
                }
            }
            return out.append('"').toString().toByteArray(Charsets.UTF_8)
        }
    }
}
