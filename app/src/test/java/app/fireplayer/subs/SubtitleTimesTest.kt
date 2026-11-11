package app.fireplayer.subs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SubtitleTimesTest {

    @Test
    fun tagsAreStrippedSoSoundNotesInThemAreLeftOut() {
        val srt = """
            1
            00:00:01,000 --> 00:00:02,000
            {\an8}<i>[door slams]</i>

            2
            00:00:03,000 --> 00:00:04,500
            {\an8}<i>Where were you?</i>
        """.trimIndent()
        val lines = SubtitleTimes.parse(srt, ass = false)
        assertEquals(1, lines.size)
        assertEquals(3_000L, lines[0].startMs)
        val ass = "Dialogue: 0,0:00:05.00,0:00:06.00,Default,,0,0,0,,{\\i1}Hello there{\\i0}"
        assertEquals(1, SubtitleTimes.parse(ass, ass = true).size)
    }

    /**
     * Android's regex engine (ICU) rejects a "}" that doesn't close a "{n,m}" count, which desktop Java (and so these
     * tests) accepts: FirePlayer 1.0.141 crashed on the TV compiling SubtitleTimes' tag pattern. Every regex literal
     * in the app has to escape its literal braces.
     */
    @Test
    fun everyRegexInTheAppEscapesItsLiteralBraces() {
        val sources = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue("found the app's sources", sources.isNotEmpty())
        val bad = ArrayList<String>()
        for (f in sources) {
            for (m in RAW_REGEX.findAll(f.readText())) {
                val pattern = m.groupValues[1]
                if (!icuBracesOk(pattern)) bad += "${f.name}: $pattern"
            }
        }
        assertTrue("literal braces not escaped (crashes on Android): $bad", bad.isEmpty())
    }

    private companion object {
        val RAW_REGEX = Regex("Regex\\(\\s*\"\"\"(.*?)\"\"\"", RegexOption.DOT_MATCHES_ALL)

        /** False when a "}" outside a [class] closes no "{n}", "{n,}" or "{n,m}" count, or a "{" opens none. */
        fun icuBracesOk(p: String): Boolean {
            var i = 0
            var inClass = false
            while (i < p.length) {
                val c = p[i]
                when {
                    // "\p{M}", "\x{2F}" and the like: braces that are part of the escape
                    c == '\\' && i + 2 < p.length && p[i + 1] in "pPxN" && p[i + 2] == '{' -> i = p.indexOf('}', i).let { if (it < 0) p.length else it }
                    c == '\\' -> i++
                    inClass -> if (c == ']') inClass = false
                    c == '[' -> inClass = true
                    c == '{' -> {
                        val close = p.indexOf('}', i)
                        if (close < 0 || !p.substring(i + 1, close).matches(Regex("\\d+(,\\d*)?"))) return false
                        i = close
                    }
                    c == '}' -> return false
                }
                i++
            }
            return true
        }
    }
}
