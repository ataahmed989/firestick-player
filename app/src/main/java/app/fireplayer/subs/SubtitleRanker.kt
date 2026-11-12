package app.fireplayer.subs

import android.net.Uri
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs
import kotlin.math.log10

/**
 * Puts the online subtitles most likely to be in time with this video first. Downloads alone say little (the most
 * downloaded are often for another release, and drift); what counts is, in order: made for this very file (its
 * fingerprint, see [MovieHash]), the right episode, timed for the same frame rate, the same release (group and
 * source), then quality, and downloads only to break a tie.
 */
object SubtitleRanker {

    /**
     * What's known of the video playing: its file name (release), frame rate (0 if unknown) and the episode wanted.
     * [names]: the episode's name (or, for a half-hour joined from two, both); subtitles whose episode name is one of
     * them go up, and with [namesMust] those named for another episode are left out.
     */
    class Video(
        val name: String,
        val fps: Float,
        val season: Int?,
        val number: Int?,
        val names: List<String> = emptyList(),
        val namesMust: Boolean = false,
    )

    /** Whether a subtitle's [release] or its episode's [title] names one of [names]. Null when it can't tell. */
    fun namesEpisode(title: String, release: String, names: List<String>): Boolean? {
        val wanted = names.map { simple(it) }.filter { it.length >= 4 }
        if (wanted.isEmpty()) return null
        val inRelease = simple(release)
        if (wanted.any { inRelease.contains(it) }) return true
        val t = simple(title)
        if (t.isEmpty()) return null
        return wanted.any { t == it || t.contains(it) || it.contains(t) && t.length >= 6 }
    }

    private fun simple(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("""\p{M}+"""), "").replace("&", "and").filter { it.isLetterOrDigit() }

    /** A result with its score (1 to 99) and short notes on why ([best] is the top one). */
    class Ranked(val result: OpenSubtitles.Result, val score: Int, val notes: List<String>, val stretch: Float) {
        var best = false
    }

    fun rank(results: List<OpenSubtitles.Result>, video: Video): List<Ranked> {
        val mine = Release.of(video.name)
        val ranked = results.mapNotNull { r ->
            // For another episode: left out, not just put lower.
            if (video.season != null && video.number != null && r.season > 0 && r.number > 0 &&
                (r.season != video.season || r.number != video.number)
            ) return@mapNotNull null
            var score = 40.0
            val notes = ArrayList<String>()
            when (namesEpisode(r.episodeTitle, r.release, video.names)) {
                true -> {
                    score += 20
                    notes += "Episode name matches"
                }
                false -> if (video.namesMust) return@mapNotNull null
                null -> {}
            }
            if (r.hashMatch) {
                score += 45
                notes += "Made for this exact file"
            }
            val theirs = Release.of(r.release)
            if (mine.group != null && mine.group == theirs.group) {
                score += 15
                notes += "Same release (${theirs.groupShown})"
            } else if (mine.source != null && mine.source == theirs.source) {
                score += 12
                notes += "Same source (${theirs.source})"
            } else if (theirs.source != null) {
                notes += theirs.source
            }
            if (mine.resolution != null && mine.resolution == theirs.resolution) score += 3
            var stretch = 1f
            if (r.fps > 0 && video.fps > 0) {
                val ratio = r.fps / video.fps
                when {
                    abs(ratio - 1f) < 0.004f -> {
                        score += 12
                        notes += fps(r.fps)
                    }
                    // A PAL (25) copy of a 23.976 film runs 4% fast: stretched to fit when it's loaded.
                    abs(ratio - 25f / 23.976f) < 0.01f || abs(ratio - 23.976f / 25f) < 0.01f -> {
                        if (!r.hashMatch) {
                            score -= 8
                            stretch = ratio
                        }
                        notes += "${fps(r.fps)}, fitted to this video"
                    }
                    else -> {
                        score -= 20
                        notes += "${fps(r.fps)}, may drift"
                    }
                }
            }
            if (r.machine) {
                score -= 25
                notes += "Machine translated"
            }
            if (r.trusted) score += 4
            if (r.hearingImpaired) score -= 2
            score += minOf(10.0, log10(r.downloads + 1.0) * 2.5)
            if (r.downloads > 0) notes += downloads(r.downloads)
            Ranked(r, score.toInt().coerceIn(1, 99), notes, stretch)
        }.sortedByDescending { it.score }
        ranked.firstOrNull()?.best = true
        return ranked
    }

    private fun fps(f: Float) = (if (abs(f - f.toInt()) < 0.01f) f.toInt().toString() else String.format(java.util.Locale.ROOT, "%.3f", f)) + " FPS"

    private fun downloads(n: Int) = when {
        n >= 1_000_000 -> "${n / 1_000_000}M downloads"
        n >= 1_000 -> "${n / 1_000}k downloads"
        n == 1 -> "1 download"
        else -> "$n downloads"
    }

    /** The release a name says it is: "Show.S02E03.1080p.WEB-DL.DD5.1.H.264-NTb" → group NTb, source WEB, 1080p. */
    private class Release(val group: String?, val groupShown: String?, val source: String?, val resolution: String?) {
        companion object {
            private val GROUP = Regex("""-([A-Za-z0-9]{2,15})(?:\[[^\]]*])?(?:\.[A-Za-z0-9]{2,4})?$""")
            private val RESOLUTION = Regex("""(?i)\b(2160p|1080p|720p|576p|480p)\b""")

            fun of(name: String): Release {
                val trimmed = name.trim().substringAfterLast('/')
                val groupShown = GROUP.find(trimmed)?.groupValues?.get(1)?.takeIf { g -> g.any { it.isLetter() } && g.lowercase() !in NOT_GROUPS }
                val lower = trimmed.lowercase()
                val source = when {
                    Regex("""\bweb[ .-]?(dl|rip)?\b|\b(amzn|nf|dsnp|hmax|atvp)\b""").containsMatchIn(lower) -> "WEB"
                    Regex("""\b(blu-?ray|bdrip|brrip|bdremux|remux)\b""").containsMatchIn(lower) -> "BluRay"
                    Regex("""\bhdtv\b""").containsMatchIn(lower) -> "HDTV"
                    Regex("""\bdvd(rip)?\b""").containsMatchIn(lower) -> "DVD"
                    else -> null
                }
                return Release(groupShown?.lowercase(), groupShown, source, RESOLUTION.find(lower)?.value)
            }

            private val NOT_GROUPS = setOf("dl", "rip", "264", "265", "hevc", "x264", "x265", "eng", "en", "srt", "sub")
        }
    }

    /**
     * Fits a subtitle timed for another frame rate to this video: every time is multiplied by [factor] (25 / 23.976
     * for a PAL copy on a 23.976 film). SRT and WebVTT only; others are left as they are.
     */
    fun stretch(file: File, factor: Float) {
        if (abs(factor - 1f) < 0.001f) return
        if (file.extension.lowercase() !in setOf("srt", "vtt")) return
        val text = runCatching { file.readText() }.getOrNull() ?: return
        val stretched = TIME.replace(text) { m ->
            val (h, mi, sec, ms) = m.destructured
            val total = ((h.toLong() * 3600 + mi.toLong() * 60 + sec.toLong()) * 1000 + ms.toLong()) * factor
            val t = total.toLong()
            String.format(java.util.Locale.ROOT, "%02d:%02d:%02d%s%03d", t / 3_600_000, t / 60_000 % 60, t / 1000 % 60, m.value[8], t % 1000)
        }
        runCatching { file.writeText(stretched) }
    }

    /** When the last subtitle shows (ms), to tell one made for a longer or shorter cut; 0 if it can't be read. */
    fun lastTime(file: File): Long {
        val text = runCatching { file.readText() }.getOrNull() ?: return 0L
        return TIME.findAll(text).maxOfOrNull { m ->
            val (h, mi, sec, ms) = m.destructured
            (h.toLong() * 3600 + mi.toLong() * 60 + sec.toLong()) * 1000 + ms.toLong()
        } ?: 0L
    }

    private val TIME = Regex("""(\d{2}):(\d{2}):(\d{2})[,.](\d{3})""")
}

/**
 * OpenSubtitles' fingerprint of a video file: its size plus the sums of its first and last 64 KB read as 64-bit
 * numbers. Read with two small range requests, so only for providers that allow a second connection.
 */
object MovieHash {
    private const val CHUNK = 65_536L

    fun of(uri: Uri, headers: Map<String, String>): String? = runCatching {
        val (head, size) = range(uri, headers, 0, CHUNK - 1) ?: return null
        if (size < CHUNK * 2) return null
        val (tail, _) = range(uri, headers, size - CHUNK, size - 1) ?: return null
        var hash = size
        hash += sum(head)
        hash += sum(tail)
        String.format(java.util.Locale.ROOT, "%016x", hash)
    }.getOrNull()

    private fun sum(bytes: ByteArray): Long {
        var s = 0L
        var i = 0
        while (i + 8 <= bytes.size) {
            var v = 0L
            for (b in 7 downTo 0) v = (v shl 8) or (bytes[i + b].toLong() and 0xFF)
            s += v
            i += 8
        }
        return s
    }

    /** The bytes asked for and the file's whole size, or null when the server doesn't do ranges. */
    private fun range(uri: Uri, headers: Map<String, String>, from: Long, to: Long): Pair<ByteArray, Long>? {
        val conn = URL(uri.toString()).openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000
        conn.readTimeout = 10_000
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        conn.setRequestProperty("Range", "bytes=$from-$to")
        try {
            if (conn.responseCode != 206) return null
            val total = conn.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull() ?: return null
            val want = (to - from + 1).toInt()
            val out = ByteArray(want)
            var got = 0
            conn.inputStream.use { input ->
                while (got < want) {
                    val n = input.read(out, got, want - got)
                    if (n < 0) break
                    got += n
                }
            }
            if (got < want) return null
            return out to total
        } finally {
            conn.disconnect()
        }
    }
}
