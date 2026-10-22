package app.fireplayer.titles

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

class JsonListTest {

    private fun copy(json: String, rename: (JsonList.Item) -> String?): String {
        val out = ByteArrayOutputStream()
        JsonList(ByteArrayInputStream(json.toByteArray()), out).copy(rename)
        return out.toString("UTF-8")
    }

    @Test
    fun unchangedListComesOutByteForByte() {
        val json = """[{"num":1,"name":"K.O. - 2025","stream_id":764653,"tmdb":"","category_ids":[80],"x":{"a":"}"}},""" +
            """ {"name":"Café \"Noir\"","stream_id":"7","direct_source":"http:\/\/a\/b"}]"""
        assertEquals(json, copy(json) { null })
    }

    @Test
    fun readsFieldsAndRenamesOnlyTheName() {
        val json = """[{"num":1,"name":"ماميز - 2023","stream_id":5,"tmdb":"123","title":"x"},""" +
            """{"name":"Keep","stream_id":6}]"""
        val seen = mutableListOf<String>()
        val out = copy(json) { item ->
            seen += "${item.text("name")}|${item.number("stream_id")}|${item.number("tmdb")}"
            if (item.number("stream_id") == 5) "Mamiz \"Group\" (2023) [4K]" else null
        }
        assertEquals(listOf("ماميز - 2023|5|123", "Keep|6|0"), seen)
        assertEquals(
            """[{"num":1,"name":"Mamiz \"Group\" (2023) [4K]","stream_id":5,"tmdb":"123","title":"Mamiz \"Group\" (2023) [4K]"},""" +
                """{"name":"Keep","stream_id":6}]""",
            out,
        )
    }

    @Test
    fun notAListIsCopied() {
        val json = """{"user_info":{"auth":0}}"""
        assertEquals(json, copy(json) { "never" })
        assertEquals("", copy("") { "never" })
    }

    /** With CATALOG=<a saved get_vod_streams answer>: a real provider's list, through unchanged and read in full. */
    @Test
    fun realCatalog() {
        val file = System.getenv("CATALOG")?.let(::File) ?: return
        val bytes = file.readBytes()
        val out = ByteArrayOutputStream()
        var items = 0
        val started = System.nanoTime()
        JsonList(ByteArrayInputStream(bytes), out).copy { item ->
            if (item.text("name") != null) items++
            null
        }
        println("${file.name}: $items items in ${(System.nanoTime() - started) / 1_000_000} ms")
        assertArrayEquals(bytes, out.toByteArray())
    }
}
