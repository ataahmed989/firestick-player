package app.fireplayer.player

import android.content.Context
import android.util.Log
import app.fireplayer.player.SkipRules.Part
import app.fireplayer.player.SkipRules.Pick
import app.fireplayer.subs.SyncMapping
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.FutureTask

/**
 * Intro, recap and credits times from SkipDB (skipdb.tv, asked with the file's length), with IntroDB (introdb.app)
 * only for the parts SkipDB doesn't know or that don't fit. Which times are trusted is [SkipRules]'. Answers are kept
 * on disk a few days (a day when there was nothing), at most [KEPT_FILES] of them; the same question asked twice
 * at once goes out once. Every call blocks: run it off the main thread.
 */
object SkipTimes {
    private const val TAG = "FirePlayerSegments"
    private const val SKIPDB = "https://api.skipdb.tv/api/segments"
    private const val INTRODB = "https://api.introdb.app/segments"
    private const val KEPT_MS = 5 * 24 * 60 * 60_000L
    private const val MISS_KEPT_MS = 24 * 60 * 60_000L
    private const val KEPT_FILES = 300
    // After "too many requests" or no answer, nothing is asked for a minute; the film plays on regardless.
    private const val BACK_OFF_MS = 60_000L
    // SkipDB's "exact": within 2 s of the length it was asked with.
    private const val SAME_MS = 2_000L

    @Volatile private var quietUntil = 0L
    private val asking = ConcurrentHashMap<String, FutureTask<Answer?>>()

    /**
     * The parts of an episode ([season] and [episode] given) or a film, for a file [durationMs] long. [tmdbMinutes]:
     * TMDB's length for it, 0 when unknown (then IntroDB's times aren't used). Null when nothing fits.
     */
    fun find(
        context: Context, imdbId: String, season: Int?, episode: Int?, durationMs: Long, tmdbMinutes: Int,
        sync: SyncMapping? = null,
    ): Segments? {
        if (durationMs <= 0 || !imdbId.matches(IMDB)) return null
        val what = if (season != null && episode != null) "&season=$season&episode=$episode" else ""
        val seconds = Math.round(durationMs / 1000.0)
        val skipDb = get(context, "$SKIPDB?imdb_id=$imdbId$what&duration=$seconds&adjust=conservative")
            ?.let { runCatching { skipDbPicks(JSONObject(it), durationMs) }.getOrNull() }
            ?: emptyMap()
        var picks = skipDb
        var from = "skipdb"
        // IntroDB only for what SkipDB left open (unknown, or not fitting this file).
        val open = listOf(Part.INTRO, Part.RECAP, Part.OUTRO).any { SkipRules.open(skipDb[it]) }
        if (open) {
            val movie = if (what.isEmpty()) "&is_movie=true" else ""
            val intro = get(context, "$INTRODB?imdb_id=$imdbId$what$movie")
                ?.let { runCatching { introDbPicks(JSONObject(it), durationMs, SkipRules.runtimeAgrees(tmdbMinutes, durationMs)) }.getOrNull() }
            picks = SkipRules.combine(skipDb, intro)
            if (intro != null && Part.values().any { skipDb[it] !== picks[it] }) from = if (skipDb.values.any { it is Pick.Found }) "skipdb+introdb" else "introdb"
        }
        // What's still open, from the databases' times taken on the downloaded subtitles' version and put on this
        // file's timeline through the offsets auto sync confirmed (a file with an ad or bumper the databases' hasn't).
        if (sync != null && listOf(Part.INTRO, Part.RECAP, Part.OUTRO).any { SkipRules.open(picks[it]) }) {
            val synced = onSubtitles(context, imdbId, what, durationMs, tmdbMinutes, sync)
            if (synced != null) {
                val before = picks
                picks = SkipRules.combine(picks, synced)
                if (Part.values().any { before[it] !== picks[it] }) from += "+sync"
            }
        }
        Log.i(TAG, "$imdbId$what at ${seconds}s ($from): " + describe(picks))
        return SkipRules.segments(picks, durationMs, from)
    }

    /**
     * The parts on the subtitles' timeline, mapped to this file's ([SkipRules.mapped]); null when there's nothing to
     * go on. SkipDB is asked with the subtitles' timeline's length and adjust=none (its own times, unshifted), and
     * only its exact answers count; IntroDB's (asked already, from disk) are weighed against that length.
     */
    private fun onSubtitles(
        context: Context, imdbId: String, what: String, durationMs: Long, tmdbMinutes: Int, sync: SyncMapping,
    ): Map<Part, Pick>? {
        // Offsets within SkipDB's own "exact" leave nothing the file's own length didn't already say.
        if (sync.segments.all { Math.abs(it.delayMs) < SAME_MS }) return null
        val clean = sync.cleanLength(durationMs) ?: return null
        val seconds = Math.round(clean / 1000.0)
        val onSubs = get(context, "$SKIPDB?imdb_id=$imdbId$what&duration=$seconds&adjust=none")
            ?.let { runCatching { skipDbPicks(JSONObject(it), clean, onSubtitles = true) }.getOrNull() }
            ?: emptyMap()
        var mapped = Part.values().associateWith { SkipRules.mapped(it, onSubs[it] ?: Pick.Missing, sync, durationMs) }
        if (listOf(Part.INTRO, Part.RECAP, Part.OUTRO).any { SkipRules.open(mapped[it]) }) {
            val movie = if (what.isEmpty()) "&is_movie=true" else ""
            val intro = get(context, "$INTRODB?imdb_id=$imdbId$what$movie")
                ?.let { runCatching { introDbPicks(JSONObject(it), clean, SkipRules.runtimeAgrees(tmdbMinutes, clean)) }.getOrNull() }
                ?.mapValues { (part, pick) -> SkipRules.mapped(part, pick, sync, durationMs) }
            mapped = SkipRules.combine(mapped, intro)
        }
        Log.i(TAG, "$imdbId$what, subtitles' timeline ${seconds}s, offsets " +
            sync.segments.joinToString { "${it.fromMs / 1000}s:${it.delayMs}" } + ": " + describe(mapped))
        return mapped
    }

    private fun describe(picks: Map<Part, Pick>) = Part.values().joinToString { p ->
        p.name.lowercase() + " " + when (val k = picks[p]) {
            is Pick.Found -> "${k.start}..${k.end}"
            is Pick.Rejected -> "rejected (${k.why})"
            Pick.None -> "none"
            else -> "unknown"
        }
    }

    private fun skipDbPicks(o: JSONObject, duration: Long, onSubtitles: Boolean = false): Map<Part, Pick> {
        val all = o.optJSONObject("segments") ?: return emptyMap()
        return Part.values().associateWith { part ->
            val s = all.optJSONObject(part.name.lowercase())?.let {
                SkipRules.SkipDbSegment(
                    it.optLong("start_ms", -1), it.optLong("end_ms", -1), it.optString("match"),
                    it.optLong("offset_ms", 0), it.optDouble("confidence", 0.0),
                )
            }
            if (onSubtitles) SkipRules.skipDbOnSubtitles(part, s, duration) else SkipRules.skipDb(part, s, duration)
        }
    }

    private fun introDbPicks(o: JSONObject, duration: Long, tmdbAgrees: Boolean): Map<Part, Pick> {
        fun seg(name: String) = o.optJSONObject(name)?.let {
            SkipRules.IntroDbSegment(it.optLong("start_ms", -1), it.optLong("end_ms", -1), it.optDouble("confidence", 0.0))
        }
        val evidence = SkipRules.Evidence(tmdbAgrees, SkipRules.outroAnchors(seg("outro"), duration))
        return mapOf(
            Part.INTRO to SkipRules.introDb(Part.INTRO, seg("intro"), o.optBoolean("no_intro"), evidence, duration),
            Part.RECAP to SkipRules.introDb(Part.RECAP, seg("recap"), o.optBoolean("no_recap"), evidence, duration),
            Part.OUTRO to SkipRules.introDb(Part.OUTRO, seg("outro"), o.optBoolean("no_outro"), evidence, duration),
            // A film's scene after the credits: something to stay for.
            Part.PREVIEW to SkipRules.introDb(Part.PREVIEW, seg("post_credits"), false, evidence, duration),
        )
    }

    /** The answer's text, from disk when it's fresh; null on no answer. The same address asked twice goes out once. */
    private fun get(context: Context, url: String): String? {
        val dir = File(context.cacheDir, "skiptimes").apply { mkdirs() }
        val file = File(dir, "${url.hashCode().toUInt()}.json")
        if (file.exists()) {
            val age = System.currentTimeMillis() - file.lastModified()
            val text = runCatching { file.readText() }.getOrNull()
            // An answer without any times is kept for less time than one with some: they may come in soon.
            val kept = if (text != null && "start_ms" in text) KEPT_MS else MISS_KEPT_MS
            if (text != null && age < kept) return text.ifEmpty { null }
        }
        if (System.currentTimeMillis() < quietUntil) return null
        val task = FutureTask { fetch(url) }
        val running = asking.putIfAbsent(url, task)
        val answer = if (running != null) runCatching { running.get() }.getOrNull() else {
            try { task.run(); task.get() } finally { asking.remove(url) }
        }
        if (running == null && answer != null) {
            runCatching { file.writeText(answer.text) }
            trim(dir)
        }
        return answer?.text?.ifEmpty { null }
    }

    /** "" for a definite nothing (404), the body for an answer; null for no answer (kept on disk: neither). */
    private class Answer(val text: String)

    private fun fetch(url: String): Answer? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 6_000
        conn.readTimeout = 8_000
        conn.setRequestProperty("User-Agent", "FirePlayer/1.0 (Android TV)")
        conn.setRequestProperty("Accept", "application/json")
        return try {
            when (val code = conn.responseCode) {
                200 -> Answer(conn.inputStream.bufferedReader().use { it.readText() })
                404 -> Answer("")
                else -> {
                    if (code == 429 || code >= 500) quietUntil = System.currentTimeMillis() + BACK_OFF_MS
                    Log.w(TAG, "${url.substringBefore('?')}: $code")
                    null
                }
            }
        } catch (e: Exception) {
            quietUntil = System.currentTimeMillis() + BACK_OFF_MS
            Log.w(TAG, "${url.substringBefore('?')} didn't answer: $e")
            null
        } finally {
            conn.disconnect()
        }
    }

    /** At most [KEPT_FILES] answers kept: the oldest go. */
    private fun trim(dir: File) {
        val files = dir.listFiles() ?: return
        if (files.size <= KEPT_FILES) return
        files.sortedBy { it.lastModified() }.take(files.size - KEPT_FILES).forEach { it.delete() }
    }

    private val IMDB = Regex("^tt\\d{7,9}$")
}
