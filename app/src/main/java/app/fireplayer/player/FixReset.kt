package app.fireplayer.player

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import app.fireplayer.history.ContinueWatching
import app.fireplayer.history.WatchHistory
import org.json.JSONObject

/**
 * Once on the TV: clears every Fix match made for single episodes by earlier releases (the season and episode pickers
 * with "Move the rest of the season too", the Season and Episode lists with "Also fix N other episodes from this
 * source"), for playlists' shows and Films & Shows titles, and what was made from them, so every show is paired as if
 * none had been made. Which show or film a title is (Fix match now) is kept, as are the episodes said to look right.
 *
 * Safe to stop at any point: what was made from the fixes goes first (each step safe to repeat), then the fixes and the
 * mark that it's done, in one write of the store ([SharedPreferences.Editor.commit]).
 */
object FixReset {
    private const val TAG = "FirePlayerFixes"

    /** Kept in [MatchFixes.FILE] once the reset is done. */
    const val DONE = "reset|episode-fixes"

    @Volatile private var done = false
    private val lock = Any()

    /**
     * One show's episode fixes as earlier releases kept them: episodes placed one by one ("id": "season,number"), and
     * runs of a season moved together ("season,from,toSeason,delta": from E[from] on, into [toSeason], by [delta]).
     */
    class Moved(val picks: Map<String, Pair<Int, Int>>, val shifts: List<List<Int>>) {
        val isEmpty: Boolean get() = picks.isEmpty() && shifts.isEmpty()

        /** Where these fixes had put an episode, or null when they didn't move it. */
        fun place(id: String, season: Int, number: Int): Pair<Int, Int>? {
            picks[id]?.let { return it }
            val s = shifts.filter { it[0] == season && number >= it[1] }.maxByOrNull { it[1] } ?: return null
            return s[2] to (number + s[3]).coerceAtLeast(0)
        }
    }

    /**
     * What the reset changes in the store: shows' fixes rewritten with the show alone ([write]), and those with nothing
     * left or that nothing reads any more ([remove]); [moved]: each playlist show's episode fixes, by its key, for what
     * was made from them.
     */
    class Plan(val write: Map<String, String>, val remove: Set<String>, val moved: Map<String, Moved>)

    /** The reset of the store [all] ([SharedPreferences.getAll] of [MatchFixes.FILE]). Pure. */
    fun plan(all: Map<String, *>): Plan {
        val write = HashMap<String, String>()
        val remove = HashSet<String>()
        val moved = HashMap<String, Moved>()
        for ((key, value) in all) {
            if (!key.startsWith("show|")) continue
            val o = (value as? String)?.let { runCatching { JSONObject(it) }.getOrNull() }
            if (o == null) {
                remove += key
                continue
            }
            // A Films & Shows title's episodes (its show is kept as the title's own fix, "vod|…"), read by nothing now.
            if (key.startsWith("show|" + MatchFixes.vodKey(""))) {
                remove += key
                continue
            }
            val m = moved(o)
            if (!m.isEmpty) moved[key.removePrefix("show|")] = m
            if (!o.has("picks") && !o.has("shifts")) continue
            val show = MatchFixes.Show.fromJson(o.toString()) ?: MatchFixes.Show(0, emptySet())
            if (show.tmdbId <= 0 && show.confirmed.isEmpty()) remove += key else write[key] = show.toJson()
        }
        return Plan(write, remove, moved)
    }

    private fun moved(o: JSONObject): Moved {
        val picks = HashMap<String, Pair<Int, Int>>()
        o.optJSONObject("picks")?.let { p ->
            p.keys().forEach { id ->
                val parts = p.optString(id).split(',').mapNotNull { it.trim().toIntOrNull() }
                if (parts.size == 2) picks[id] = parts[0] to parts[1]
            }
        }
        val shifts = o.optJSONArray("shifts")?.let { a ->
            (0 until a.length()).mapNotNull { i -> a.optString(i).split(',').mapNotNull { it.trim().toIntOrNull() }.takeIf { it.size == 4 } }
        }.orEmpty()
        return Moved(picks, shifts)
    }

    /**
     * A Continue Watching row's numbers back at the source's own, for an episode [moved] had put elsewhere and the row
     * was noted at ([season] / [episode], as [ep], the kept guide's, shows it). Null: the row is left as it is. Pure.
     */
    fun ownNumbers(ep: ShowGuide.Ep, moved: Moved, season: Int, episode: Int): WatchHistory.Numbers? {
        val at = ep.season to ep.number
        if (at == (ep.providerSeason to ep.providerNumber)) return null
        if (moved.place(ep.id, ep.providerSeason, ep.providerNumber) != at || (season to episode) != at) return null
        return WatchHistory.Numbers(ep.providerSeason, ep.providerNumber, ep.providerTitle.ifEmpty { null })
    }

    /** Runs the reset unless it was done; at once when it was. Blocks on small files: off the main thread. */
    fun runOnce(context: Context) {
        if (done) return
        synchronized(lock) {
            if (done) return
            val app = context.applicationContext
            val prefs = app.getSharedPreferences(MatchFixes.FILE, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(DONE, false)) {
                runCatching { reset(app, prefs) }
                    .onFailure { Log.w(TAG, "Couldn't clear the episode fixes: ${it.javaClass.simpleName}") }
            }
            done = true
        }
    }

    private fun reset(app: Context, prefs: SharedPreferences) {
        val plan = plan(prefs.all)
        if (plan.moved.isNotEmpty()) {
            // Continue Watching's rows are found by the source's ids, never by an episode's numbers; those noted at a
            // number a fix gave go back to the source's own (the next play notes TMDB's again).
            val guides = HashMap<String, ShowGuide?>()
            ContinueWatching.renumber(app) { r ->
                if (r.kind != WatchHistory.Kind.EPISODE) return@renumber null
                val uri = Uri.parse(r.resume.uri)
                val showId = NextEpisode.rememberedShowId(app, uri) ?: return@renumber null
                val key = NextEpisode.showKey(uri, showId) ?: return@renumber null
                val moved = plan.moved[key] ?: return@renumber null
                val ep = guides.getOrPut(key) { ShowGuide.saved(app, key) }?.episode(NextEpisode.episodeId(uri))
                ep?.let { ownNumbers(it, moved, r.season, r.episode) }
            }
            // The guides paired with the fixes: made again, without them, the next time each show is asked for.
            plan.moved.keys.forEach { ShowGuide.forget(app, it) }
        }
        val edit = prefs.edit()
        plan.remove.forEach { edit.remove(it) }
        plan.write.forEach { (k, v) -> edit.putString(k, v) }
        edit.putBoolean(DONE, true)
        check(edit.commit()) { "not kept" }
        Log.i(TAG, "Episode fixes cleared: ${plan.moved.size} shows paired again, ${plan.remove.size + plan.write.size} kept fixes tidied")
    }
}
