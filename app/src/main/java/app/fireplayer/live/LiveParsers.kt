package app.fireplayer.live

import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** One playlist's channels and groups as the provider lists them, before they are stored. */
class ParsedPlaylist(
    val groups: List<ChannelGroup>,
    val channels: List<Channel>,
    /** The EPG link the playlist itself names (M3U url-tvg / x-tvg-url), if any. */
    val epgUrl: String? = null,
)

/**
 * M3U / M3U8 playlists: the #EXTM3U header (url-tvg, x-tvg-url) and each #EXTINF's tvg-id, tvg-name, tvg-logo,
 * tvg-chno, group-title and catchup-days, paired with the stream link that follows it.
 */
object M3uParser {
    private val QUOTED = Regex("""([A-Za-z0-9_-]+)="([^"]*)"""")
    private val BARE = Regex("""([A-Za-z0-9_-]+)=([^\s",]+)""")

    class Entry(
        val name: String,
        val logo: String?,
        val group: String?,
        val tvgId: String?,
        val number: Int?,
        val archiveDays: Int,
        val url: String,
    )

    /** [header]: the #EXTM3U line was there (an M3U, even with no channels). */
    class Parsed(val entries: List<Entry>, val epgUrl: String?, val header: Boolean = false)

    fun parse(content: String): Parsed = parse(content.lineSequence())

    /** Reads the playlist line by line, so a large one is never held whole in memory. */
    fun parse(lines: Sequence<String>): Parsed {
        val out = ArrayList<Entry>()
        var epgUrl: String? = null
        var header = false
        var attrs: Map<String, String>? = null
        var display: String? = null
        var extGroup: String? = null
        for (raw in lines) {
            val line = raw.trim().removePrefix("﻿")
            when {
                line.isEmpty() -> {}
                line.startsWith("#EXTM3U") -> {
                    header = true
                    val head = attributes(line.removePrefix("#EXTM3U"))
                    epgUrl = (head["url-tvg"] ?: head["x-tvg-url"])?.split(',')?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                }
                line.startsWith("#EXTINF") -> {
                    val extinf = line.substringAfter("EXTINF:", "")
                    // The display name follows the first comma that isn't inside a quoted attribute.
                    val comma = firstUnquotedComma(extinf)
                    attrs = attributes(if (comma >= 0) extinf.substring(0, comma) else extinf)
                    display = if (comma >= 0) extinf.substring(comma + 1).trim() else null
                }
                line.startsWith("#EXTGRP:") -> extGroup = line.substringAfter("#EXTGRP:").trim().takeIf { it.isNotEmpty() }
                line.startsWith("#") -> {}
                else -> {
                    val a = attrs
                    if (a != null) {
                        out += Entry(
                            name = a["tvg-name"]?.takeIf { it.isNotBlank() } ?: display?.takeIf { it.isNotBlank() } ?: line,
                            logo = a["tvg-logo"]?.takeIf { it.isNotBlank() },
                            group = a["group-title"]?.takeIf { it.isNotBlank() } ?: extGroup,
                            tvgId = a["tvg-id"]?.takeIf { it.isNotBlank() },
                            number = a["tvg-chno"]?.toIntOrNull(),
                            archiveDays = a["catchup-days"]?.toIntOrNull() ?: a["tvg-rec"]?.toIntOrNull() ?: 0,
                            url = line,
                        )
                    }
                    attrs = null
                    display = null
                    extGroup = null
                }
            }
        }
        return Parsed(out, epgUrl, header)
    }

    /**
     * Channels for [playlistId] with ids that survive a refresh. An M3U has no channel ids of its own, so a channel is
     * known by its tvg-id when that is unique in the playlist, otherwise by its name and group (with a counter for
     * exact duplicates, in playlist order). The stream link is never the id: providers change links (tokens,
     * servers) without the channel changing, and favorites must follow the channel.
     */
    fun toPlaylist(playlistId: String, parsed: Parsed): ParsedPlaylist {
        val tvgCounts = parsed.entries.groupingBy { it.tvgId }.eachCount()
        val groups = LinkedHashMap<String, ChannelGroup>()
        val used = HashMap<String, Int>()
        val channels = ArrayList<Channel>(parsed.entries.size)
        for ((i, e) in parsed.entries.withIndex()) {
            val groupId = e.group?.let { g ->
                groups.getOrPut(g) { ChannelGroup(playlistId, g, g, groups.size) }.id
            }
            val base = if (e.tvgId != null && tvgCounts[e.tvgId] == 1) "t:${e.tvgId}" else "n:${norm(e.name)}|${norm(e.group ?: "")}"
            val n = used.merge(base, 1, Int::plus)!!
            val id = if (n == 1) base else "$base#$n"
            channels += Channel(
                playlistId = playlistId, id = id, number = e.number ?: (i + 1), name = e.name, logo = e.logo,
                groupId = groupId, url = e.url, epgRef = e.tvgId, archiveDays = e.archiveDays, position = i,
            )
        }
        return ParsedPlaylist(groups.values.toList(), channels, parsed.epgUrl)
    }

    private fun norm(s: String) = s.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

    private fun firstUnquotedComma(s: String): Int {
        var quoted = false
        for (i in s.indices) {
            when (s[i]) {
                '"' -> quoted = !quoted
                ',' -> if (!quoted) return i
            }
        }
        return -1
    }

    private fun attributes(head: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        QUOTED.findAll(head).forEach { out[it.groupValues[1].lowercase(Locale.ROOT)] = it.groupValues[2] }
        BARE.findAll(head).forEach { m ->
            val k = m.groupValues[1].lowercase(Locale.ROOT)
            if (k !in out) out[k] = m.groupValues[2]
        }
        return out
    }
}

/** Xtream Codes player_api.php answers. */
object XtreamParser {

    class Account(val active: Boolean, val expiresAt: Long, val maxConnections: Int, val activeConnections: Int, val message: String?)

    /** The account part of player_api.php's plain answer; null when it isn't one (bad server or login). */
    fun account(json: String): Account? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val info = root.optJSONObject("user_info") ?: return null
        val auth = info.opt("auth")?.toString() == "1"
        val status = info.optString("status", "")
        val exp = info.optString("exp_date", "").toLongOrNull()?.let { it * 1000 } ?: 0L
        return Account(
            active = auth && (status.isEmpty() || status.equals("Active", true)),
            expiresAt = exp,
            maxConnections = info.optString("max_connections", "").toIntOrNull() ?: 0,
            activeConnections = info.optString("active_cons", "").toIntOrNull() ?: 0,
            message = info.optString("message", "").takeIf { it.isNotBlank() },
        )
    }

    /** get_live_categories and get_live_streams, in the provider's order, as one playlist's groups and channels. */
    /**
     * Null when either answer isn't a JSON list (an error page, a login problem): that's a failed update, never
     * "the provider has no channels", which would remove every channel and group.
     */
    fun live(
        playlistId: String,
        categoriesJson: String,
        streamsJson: String,
        streamUrl: (streamId: String, extension: String?) -> String,
    ): ParsedPlaylist? {
        val categories = runCatching { JSONArray(categoriesJson.trim()) }.getOrNull() ?: return null
        val streamsArr = runCatching { JSONArray(streamsJson.trim()) }.getOrNull() ?: return null
        val groups = ArrayList<ChannelGroup>()
        val known = HashSet<String>()
        categories.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("category_id").takeIf { it.isNotBlank() } ?: continue
                if (known.add(id)) groups += ChannelGroup(playlistId, id, o.optString("category_name", id), groups.size)
            }
        }
        val channels = ArrayList<Channel>()
        val seen = HashSet<String>()
        streamsArr.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.opt("stream_id")?.toString()?.takeIf { it.isNotBlank() && it != "null" } ?: continue
                if (!seen.add(id)) continue
                val cat = o.opt("category_id")?.toString()?.takeIf { it.isNotBlank() && it != "null" }
                val epg = o.optString("epg_channel_id", "").takeIf { it.isNotBlank() && it != "null" }
                channels += Channel(
                    playlistId = playlistId,
                    id = id,
                    number = o.optString("num", "").toIntOrNull() ?: (channels.size + 1),
                    name = o.optString("name", id).trim(),
                    logo = o.optString("stream_icon", "").takeIf { it.isNotBlank() && it != "null" },
                    groupId = cat?.takeIf { it in known },
                    url = streamUrl(id, o.optString("container_extension", "").takeIf { it.isNotBlank() && it != "null" }),
                    epgRef = epg,
                    archiveDays = if (o.optString("tv_archive", "0") == "1") o.optString("tv_archive_duration", "0").toIntOrNull() ?: 0 else 0,
                    position = channels.size,
                )
            }
        }
        return ParsedPlaylist(groups, channels)
    }
}

/**
 * Streams an XMLTV document, keeping only programmes on channels in [wanted] that overlap [keepFrom, keepUntil):
 * past history beyond the retention window and far-future listings are dropped as they are read, so a big EPG
 * never has to fit in memory and old programmes are never stored (not even for a moment before a cleanup).
 */
object XmltvReader {
    private val FORMATS = arrayOf("yyyyMMddHHmmss", "yyyyMMddHHmm", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd HH:mm:ss")
    private val OFFSET = Regex("""([+-]\d{2}:?\d{2}|Z)\s*$""")

    class Item(val channel: String, val programme: Programme)

    /**
     * Reads programmes until the end, handing each kept one to [onProgramme]; returns how many were kept. A programme
     * without a stop time ends where the channel's next one starts (the last one of a channel: an hour).
     */
    fun read(
        parser: XmlPullParser,
        wanted: Set<String>,
        keepFrom: Long,
        keepUntil: Long,
        offsetMs: Long = 0,
        onProgramme: (Item) -> Unit,
    ): Int {
        var kept = 0
        val open = HashMap<String, Programme>()
        fun emit(ref: String, p: Programme) {
            if (p.start > 0 && p.stop > p.start && p.stop > keepFrom && p.start < keepUntil) {
                onProgramme(Item(ref, p))
                kept++
            }
        }
        var ref: String? = null
        var start = 0L
        var stop = 0L
        var title: String? = null
        var desc: String? = null
        var category: String? = null
        var inProgramme = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "programme" -> {
                        ref = parser.getAttributeValue(null, "channel")
                        inProgramme = ref != null && ref in wanted
                        if (inProgramme) {
                            // A time that's missing or unreadable stays 0, whatever the offset.
                            start = epoch(parser.getAttributeValue(null, "start")).let { if (it > 0) it + offsetMs else 0L }
                            stop = epoch(parser.getAttributeValue(null, "stop")).let { if (it > 0) it + offsetMs else 0L }
                            title = null; desc = null; category = null
                        }
                    }
                    "title" -> if (inProgramme && title == null) title = text(parser)
                    "desc" -> if (inProgramme && desc == null) desc = text(parser)
                    "category" -> if (inProgramme && category == null) category = text(parser)
                }
            } else if (event == XmlPullParser.END_TAG && parser.name == "programme") {
                val r = ref
                if (inProgramme && r != null && start > 0) {
                    // A programme left open (no stop) ends where this one starts.
                    open.remove(r)?.let { prev -> if (start > prev.start) emit(r, prev.copy(stop = start)) }
                    val p = Programme(start, stop, title?.takeIf { it.isNotBlank() } ?: "", desc?.takeIf { it.isNotBlank() }, category)
                    if (stop <= 0L) open[r] = p else emit(r, p)
                }
                inProgramme = false
                ref = null
            }
            event = parser.next()
        }
        for ((r, p) in open) emit(r, p.copy(stop = p.start + 60 * 60_000L))
        return kept
    }

    /** An XMLTV time as epoch millis; 0 when it can't be read. */
    fun epoch(text: String?): Long {
        if (text.isNullOrBlank()) return 0L
        // The usual "20261117205300 -0500" read digit by digit: a big EPG has half a million of these, and a date
        // formatter per time was a large part of an import on the TV. Anything else goes the long way below.
        val fast = fastEpoch(text)
        if (fast != NOT_FAST) return fast
        val t = text.trim()
        val m = OFFSET.find(t)
        val body = if (m != null) t.substring(0, m.range.first).trim() else t
        var offsetMin = 0
        if (m != null && m.groupValues[1] != "Z") {
            val off = m.groupValues[1]
            val d = off.substring(1).replace(":", "")
            if (d.length == 4) {
                val hh = d.substring(0, 2).toIntOrNull() ?: 0
                val mm = d.substring(2, 4).toIntOrNull() ?: 0
                offsetMin = (if (off.startsWith('-')) -1 else 1) * (hh * 60 + mm)
            }
        }
        for (format in FORMATS) {
            if (format.replace("'", "").length != body.length) continue
            val df = SimpleDateFormat(format, Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
                isLenient = false
            }
            val parsed = runCatching { df.parse(body) }.getOrNull() ?: continue
            return parsed.time - offsetMin * 60_000L
        }
        return 0L
    }

    private const val NOT_FAST = Long.MIN_VALUE

    /** yyyyMMddHHmm[ss], then optionally spaces and Z / +hhmm / -hh:mm; [NOT_FAST] for anything else. */
    internal fun fastEpoch(s: String): Long {
        var i = 0
        val n = s.length
        while (i < n && s[i] == ' ') i++
        fun num(len: Int): Int {
            if (i + len > n) return -1
            var v = 0
            for (k in 0 until len) {
                val c = s[i + k]
                if (c !in '0'..'9') return -1
                v = v * 10 + (c - '0')
            }
            i += len
            return v
        }
        val year = num(4); val month = num(2); val day = num(2); val hour = num(2); val minute = num(2)
        if (year < 0 || month < 0 || day < 0 || hour < 0 || minute < 0) return NOT_FAST
        val second = if (i + 2 <= n && s[i] in '0'..'9') num(2) else 0
        if (second < 0) return NOT_FAST
        while (i < n && s[i] == ' ') i++
        var offsetMin = 0
        if (i < n) {
            val sign = s[i]
            if (sign == 'Z') {
                i++
            } else if (sign == '+' || sign == '-') {
                i++
                val hh = num(2)
                if (i < n && s[i] == ':') i++
                val mm = num(2)
                if (hh < 0 || mm < 0) return NOT_FAST
                offsetMin = (if (sign == '-') -1 else 1) * (hh * 60 + mm)
            } else {
                return NOT_FAST
            }
            while (i < n && s[i] == ' ') i++
            if (i != n) return NOT_FAST
        }
        if (month !in 1..12 || day < 1 || day > daysIn(year, month) || hour > 23 || minute > 59 || second > 59) return NOT_FAST
        val days = daysFromCivil(year, month, day)
        return (((days * 24 + hour) * 60 + minute) * 60 + second) * 1000L - offsetMin * 60_000L
    }

    private fun daysIn(year: Int, month: Int): Int = when (month) {
        2 -> if ((year % 4 == 0 && year % 100 != 0) || year % 400 == 0) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }

    /** Days since 1970-01-01 of a proleptic Gregorian date (H. Hinnant's days_from_civil). */
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = (if (month <= 2) year - 1 else year).toLong()
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (month + (if (month > 2) -3 else 9)) + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097 + doe - 719468
    }

    private fun text(parser: XmlPullParser): String {
        val out = StringBuilder()
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> out.append(parser.text)
                XmlPullParser.END_DOCUMENT -> break
            }
        }
        return out.toString().trim()
    }
}
