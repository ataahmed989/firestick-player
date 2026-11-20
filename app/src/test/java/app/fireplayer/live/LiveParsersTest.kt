package app.fireplayer.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.StringReader

class LiveParsersTest {

    // ---- M3U ------------------------------------------------------------------------------------------------------

    private val m3u = """
        #EXTM3U url-tvg="http://epg.example/guide.xml,http://epg.example/other.xml"
        #EXTINF:-1 tvg-id="news.one" tvg-name="News One" tvg-logo="http://logo/1.png" tvg-chno="7" group-title="News, Local",News One HD
        http://stream.example/1.ts?token=a
        #EXTINF:-1 tvg-id="" group-title="Sports",Sports, Live
        http://stream.example/2.ts
        #EXTINF:-1 group-title="Sports",Sports, Live
        http://stream.example/3.ts
        #EXTINF:-1 catchup-days="3",Plain
        #EXTGRP:Extra
        http://stream.example/4.ts
    """.trimIndent()

    @Test fun m3uReadsAttributesNamesAndEpgLink() {
        val parsed = M3uParser.parse(m3u)
        assertEquals("http://epg.example/guide.xml", parsed.epgUrl)
        assertEquals(4, parsed.entries.size)
        val first = parsed.entries[0]
        assertEquals("News One", first.name)
        assertEquals("News, Local", first.group) // a comma inside a quoted attribute isn't the name separator
        assertEquals("news.one", first.tvgId)
        assertEquals(7, first.number)
        assertEquals("http://logo/1.png", first.logo)
        assertEquals("Sports, Live", parsed.entries[1].name) // the display name keeps its own commas
        assertNull(parsed.entries[1].tvgId)
        assertEquals("Extra", parsed.entries[3].group)
        assertEquals(3, parsed.entries[3].archiveDays)
    }

    @Test fun m3uChannelIdsAreStableAndNeverTheStreamLink() {
        val a = M3uParser.toPlaylist("p", M3uParser.parse(m3u))
        // The same playlist with new stream tokens and a reordered list: ids don't change.
        val moved = m3u.replace("token=a", "token=b").replace("stream.example", "cdn2.example")
        val b = M3uParser.toPlaylist("p", M3uParser.parse(moved))
        assertEquals(a.channels.map { it.id }, b.channels.map { it.id })
        assertTrue(a.channels.none { it.id.contains("stream.example") })
        // tvg-id when unique; name + group otherwise; exact duplicates numbered.
        assertEquals("t:news.one", a.channels[0].id)
        assertEquals("n:sports, live|sports", a.channels[1].id)
        assertEquals("n:sports, live|sports#2", a.channels[2].id)
        assertEquals(a.channels.size, a.channels.map { it.id }.toSet().size)
    }

    @Test fun m3uGroupsKeepPlaylistOrder() {
        val p = M3uParser.toPlaylist("p", M3uParser.parse(m3u))
        assertEquals(listOf("News, Local", "Sports", "Extra"), p.groups.map { it.name })
        assertEquals(listOf(0, 1, 2), p.groups.map { it.position })
        assertEquals(7, p.channels[0].number)
        assertEquals(2, p.channels[1].number) // no tvg-chno: playlist position + 1
    }

    @Test fun m3uDuplicateTvgIdsFallBackToNames() {
        val text = "#EXTM3U\n#EXTINF:-1 tvg-id=\"x\",A\nhttp://a\n#EXTINF:-1 tvg-id=\"x\",B\nhttp://b\n"
        val p = M3uParser.toPlaylist("p", M3uParser.parse(text))
        assertNotEquals(p.channels[0].id, p.channels[1].id)
        assertEquals("x", p.channels[0].epgRef) // still listed under that EPG id
    }

    // ---- Xtream ---------------------------------------------------------------------------------------------------

    @Test fun xtreamAccountActiveAndExpiry() {
        val a = XtreamParser.account("""{"user_info":{"auth":1,"status":"Active","exp_date":"1800000000","max_connections":"2","active_cons":"1"}}""")!!
        assertTrue(a.active)
        assertEquals(1_800_000_000_000L, a.expiresAt)
        assertEquals(2, a.maxConnections)
        assertEquals(1, a.activeConnections)
        val expired = XtreamParser.account("""{"user_info":{"auth":1,"status":"Expired"}}""")!!
        assertFalse(expired.active)
        assertNull(XtreamParser.account("""{"error":"no"}"""))
        assertNull(XtreamParser.account("<html>"))
    }

    @Test fun xtreamLiveStreams() {
        val cats = """[{"category_id":"1","category_name":"News"},{"category_id":"2","category_name":"Sports"}]"""
        val streams = """[
            {"num":5,"name":" News One ","stream_id":101,"stream_icon":"http://l/1.png","epg_channel_id":"news.one","category_id":"1","tv_archive":1,"tv_archive_duration":"3"},
            {"num":"6","name":"Orphan","stream_id":"102","stream_icon":"","epg_channel_id":null,"category_id":"99"},
            {"num":7,"name":"Twice","stream_id":101}
        ]"""
        val p = XtreamParser.live("pl", cats, streams) { id, _ -> "http://s/live/u/p/$id.ts" }!!
        assertEquals(2, p.groups.size)
        assertEquals(2, p.channels.size) // the repeated stream_id is dropped
        val a = p.channels[0]
        assertEquals("101", a.id)
        assertEquals(5, a.number)
        assertEquals("News One", a.name)
        assertEquals("1", a.groupId)
        assertEquals("news.one", a.epgRef)
        assertEquals(3, a.archiveDays)
        assertEquals("http://s/live/u/p/101.ts", a.url)
        val b = p.channels[1]
        assertNull(b.groupId) // an unknown category isn't invented
        assertNull(b.logo)
        assertNull(b.epgRef)
    }

    @Test fun xtreamAnswersThatArentListsAreAFailedUpdateNotAnEmptyPlaylist() {
        val url = { id: String, _: String? -> id }
        assertNull(XtreamParser.live("pl", "[]", "<html>Login failed</html>", url))
        assertNull(XtreamParser.live("pl", "{\"error\":1}", "[]", url))
        assertNull(XtreamParser.live("pl", "", "[]", url))
        // A real empty answer is still allowed (a provider with no live channels).
        assertEquals(0, XtreamParser.live("pl", "[]", " [] ", url)!!.channels.size)
    }

    // ---- XMLTV ----------------------------------------------------------------------------------------------------

    @Test fun xmltvProgrammesWithoutStopEndAtTheNextOne() {
        val doc = """<tv>
          <programme start="20261117180000 +0000" channel="a"><title>First</title></programme>
          <programme start="20261117183000 +0000" channel="b"><title>Other</title></programme>
          <programme start="20261117190000 +0000" channel="a"><title>Second</title></programme>
        </tv>"""
        val from = XmltvReader.epoch("20261117000000 +0000")
        val until = XmltvReader.epoch("20261120000000 +0000")
        val items = read(doc, setOf("a", "b"), from, until).associateBy { it.programme.title }
        assertEquals(XmltvReader.epoch("20261117190000 +0000"), items["First"]!!.programme.stop)
        // The last one of a channel gets an hour.
        assertEquals(XmltvReader.epoch("20261117200000 +0000"), items["Second"]!!.programme.stop)
        assertEquals(XmltvReader.epoch("20261117193000 +0000"), items["Other"]!!.programme.stop)
    }


    private fun read(xml: String, wanted: Set<String>, from: Long, until: Long): List<XmltvReader.Item> {
        val parser = KXmlParser()
        parser.setInput(StringReader(xml))
        val out = ArrayList<XmltvReader.Item>()
        XmltvReader.read(parser, wanted, from, until) { out += it }
        return out
    }

    private val xml = """<?xml version="1.0"?>
        <tv>
          <channel id="a"><display-name>A</display-name></channel>
          <programme start="20261117090000 +0000" stop="20261117100000 +0000" channel="a"><title>Old</title></programme>
          <programme start="20261117180000 +0100" stop="20261117190000 +0100" channel="a"><title lang="en">Evening</title><desc>About <b>it</b></desc><category>News</category><category>Talk</category></programme>
          <programme start="20261117180000 +0000" stop="20261117190000 +0000" channel="b"><title>Other channel</title></programme>
          <programme start="20261125180000 +0000" stop="20261125190000 +0000" channel="a"><title>Far future</title></programme>
          <programme start="20261117200000" stop="20261117190000" channel="a"><title>Bad times</title></programme>
        </tv>"""

    @Test fun xmltvKeepsOnlyWantedChannelsInsideTheWindow() {
        val from = XmltvReader.epoch("20261117120000 +0000")
        val until = XmltvReader.epoch("20261120000000 +0000")
        val items = read(xml, setOf("a"), from, until)
        // "Old" ended before the window (retention enforced while reading), "Other channel" isn't wanted,
        // "Far future" is past the horizon, "Bad times" ends before it starts.
        assertEquals(listOf("Evening"), items.map { it.programme.title })
        val p = items[0].programme
        assertEquals(XmltvReader.epoch("20261117170000 +0000"), p.start) // +0100 is an hour ahead of UTC
        assertEquals("About it", p.description)
        assertEquals("News", p.category)
    }

    @Test fun xmltvTimes() {
        assertEquals(0L, XmltvReader.epoch(""))
        assertEquals(0L, XmltvReader.epoch("garbage"))
        val utc = XmltvReader.epoch("20261117180000 +0000")
        assertEquals(utc, XmltvReader.epoch("20261117180000"))
        assertEquals(utc, XmltvReader.epoch("20261117130000 -0500"))
        assertEquals(utc, XmltvReader.epoch("20261117190000 +01:00"))
        assertEquals(utc, XmltvReader.epoch("202611171800 +0000"))
    }

    // ---- Keys and scopes ------------------------------------------------------------------------------------------

    @Test fun keysAndScopesRoundTrip() {
        val k = ChannelKey("pl-1", "t:a/b")
        assertEquals(k, ChannelKey.parse(k.toString()))
        assertNull(ChannelKey.parse("nope"))
        assertNull(ChannelKey.parse(null))
        for (s in listOf(GuideScope.AllPlaylists, GuideScope.AllChannels("p"), GuideScope.Favorites("p"), GuideScope.Group("p", "g|1"))) {
            assertEquals(s, GuideScope.decode(s.encode()))
        }
        assertNull(GuideScope.decode("x|y"))
    }

    @Test
    fun fastXmltvTimesMatchTheDateFormatter() {
        // The digit-by-digit reader must agree with the formatter it replaces, across years, leap days, month ends,
        // offsets in both spellings and both directions, and with or without seconds.
        val utc = java.util.TimeZone.getTimeZone("UTC")
        fun reference(body: String, pattern: String, offsetMin: Int): Long =
            java.text.SimpleDateFormat(pattern, java.util.Locale.US).apply { timeZone = utc; isLenient = false }.parse(body)!!.time - offsetMin * 60_000L
        val dates = listOf("19991231", "20000229", "20240229", "20260101", "20261117", "20261231", "20380119", "21000301")
        val clocks = listOf("000000", "235959", "120000", "093015")
        val offsets = listOf("" to 0, " +0000" to 0, " -0500" to -300, "+0530" to 330, " +05:30" to 330, " -1200" to -720, " Z" to 0, "Z" to 0)
        for (d in dates) for (c in clocks) for ((o, min) in offsets) {
            val text = d + c + o
            assertEquals(text, reference(d + c, "yyyyMMddHHmmss", min), XmltvReader.epoch(text))
            val short = d + c.substring(0, 4) + o
            assertEquals(short, reference(d + c.substring(0, 4), "yyyyMMddHHmm", min), XmltvReader.epoch(short))
        }
        // Impossible dates aren't times (the formatter isn't lenient either), nor is trailing junk.
        assertEquals(0L, XmltvReader.epoch("20260230120000 +0000"))
        assertEquals(0L, XmltvReader.epoch("20261317120000 +0000"))
        assertEquals(0L, XmltvReader.epoch("20261117120000 +0000 x"))
        // Other spellings still go the long way.
        assertEquals(reference("2026-11-17T12:00:00", "yyyy-MM-dd'T'HH:mm:ss", 60), XmltvReader.epoch("2026-11-17T12:00:00+01:00"))
    }
}
