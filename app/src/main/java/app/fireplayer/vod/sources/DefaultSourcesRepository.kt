package app.fireplayer.vod.sources

import app.fireplayer.data.Prefs
import app.fireplayer.data.Settings
import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import app.fireplayer.player.CopyHealth
import app.fireplayer.player.CopyLanguage
import app.fireplayer.player.MediaSpec
import app.fireplayer.vod.Addon
import app.fireplayer.vod.AddonSettings
import app.fireplayer.vod.AddonState
import app.fireplayer.vod.BestWhy
import app.fireplayer.vod.Cancel
import app.fireplayer.vod.LookupWhy
import app.fireplayer.vod.Measured
import app.fireplayer.vod.PlayRequest
import app.fireplayer.vod.PlaybackLibrary
import app.fireplayer.vod.PlaybackSource
import app.fireplayer.vod.ReleaseKey
import app.fireplayer.vod.SecretUrl
import app.fireplayer.vod.SourceList
import app.fireplayer.vod.SourceSession
import app.fireplayer.vod.SourceTarget
import app.fireplayer.vod.SourcesRepository
import app.fireplayer.vod.ThisTv
import app.fireplayer.vod.TitleDetails
import app.fireplayer.vod.TvPlan
import app.fireplayer.vod.data.VodRepositories
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.EnumMap
import java.util.EnumSet
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** The main thread as the engine uses it (a Handler on the device, a queue in tests). */
interface MainLoop {
    fun post(r: Runnable)
    fun postDelayed(r: Runnable, delayMs: Long)
    fun remove(r: Runnable)
}

/**
 * The sources engine (design-brief §6.4-6.5, r3_prefetch §2): asks the add-ons for a film's or episode's streams,
 * reads, merges and ranks them ("Best for this TV"), keeps them in memory (10 minutes, 6 titles; an empty answer 60 s)
 * and hands plays to [DefaultSourceSession]. Automatic look-ups wait first (1.5 s, 0.5 s) and keep to [Budget]; one
 * request per add-on per title at a time, joined by later asks; a newer automatic look-up cancels an older automatic
 * one on the same add-on. Each add-on has its answer limit counted from the first ask; a late answer still joins.
 * Everything runs on the main thread except fetching and reading ([io]) and merging and ranking ([work]).
 * Links never leave memory; failures are kept as plain words.
 */
class DefaultSourcesRepository(
    override val addons: AddonSettings,
    private val links: (Addon) -> SecretUrl?,
    override val thisTv: ThisTv,
    override val library: PlaybackLibrary,
    private val http: StreamsHttp,
    internal val linkCheck: LinkCheck,
    private val budget: Budget,
    private val measured: (String) -> Measured?,
    private val language: () -> String,
    internal val main: MainLoop,
    internal val io: Executor,
    private val work: Executor,
    internal val elapsed: () -> Long,
    internal val wall: () -> Long,
    internal val online: () -> Boolean,
    /** What this connection was measured to sustain when a film kept stalling for want of it ([LineRate.current]). */
    private val lineMbps: () -> Double? = { null },
    /** The add-ons' statuses: real use notes in them what it finds ([noted]). */
    override val checks: AddonChecks? = null,
    /** The language a title was made in, from what's stored of it ([app.fireplayer.vod.VodRepository.originalLanguage]); any thread. */
    private val originalLanguage: (app.fireplayer.vod.Title) -> String? = { null },
    /** The viewer's subtitle language ("en"); null when subtitles are off. */
    private val subtitles: () -> String? = { null },
) : SourcesRepository {

    private class Answer(val parsed: ParsedAnswer, val keepUntil: Long)

    private inner class Call(val entry: Entry, val addon: Addon, val automatic: Boolean, val why: LookupWhy) {
        var user = !automatic
        var cancelled = false
        val handle = CallHandle()
        var deadline: Runnable? = null
    }

    private class Sub(val why: LookupWhy, val only: Addon?, val onList: (SourceList) -> Unit) {
        var cancelled = false
        var start: Runnable? = null
    }

    private inner class Entry(val target: SourceTarget) {
        val answers = EnumMap<Addon, Answer>(Addon::class.java)
        val states = EnumMap<Addon, AddonState>(Addon::class.java)
        val failedAt = EnumMap<Addon, Long>(Addon::class.java)
        val calls = EnumMap<Addon, Call>(Addon::class.java)
        /** The add-ons in the order they answered, so rows keep their keys as answers join. */
        val order = ArrayList<Addon>()
        val subs = ArrayList<Sub>()
        var ranked: List<PlaybackSource> = emptyList()
        var best: PlaybackSource? = null
        var lastUsed: PlaybackSource? = null
        var why: BestWhy? = null
        /** What it was last ranked for: this TV's plan and the sound languages wanted. */
        var plan: TvPlan? = null
        var wanted: List<String> = emptyList()
        /** The row each release went into (merged copies). */
        var rowOf: Map<String, String> = emptyMap()
        /** It was ranked knowing the title's own language ([originalLanguage]). */
        var originalKnown = false
        var version = 0
        /** The add-ons whose answers are in [ranked]; one answered but not yet ranked still counts as looking. */
        var rankedFrom: Set<Addon> = emptySet()
    }

    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)
    private val automaticCalls = EnumMap<Addon, Call>(Addon::class.java)

    /** Titles (IMDb id) where the user was already asked whether the debrid service should fetch a source (once per title). */
    internal val fetchAsked = HashSet<String>()

    /** When FirePlayer last opened a link (it may have added a torrent to the debrid service): the "just added" rule. */
    internal var lastAddAt = Long.MIN_VALUE / 2

    // ---- SourcesRepository ----

    override fun cached(target: SourceTarget): SourceList? {
        val entry = entries[target.key] ?: return null
        expire(entry)
        if (entry.answers.isEmpty() && entry.calls.isEmpty() && entry.failedAt.isEmpty()) return null
        return listOf(entry)
    }

    override fun lookUp(target: SourceTarget, why: LookupWhy, only: Addon?, onList: (SourceList) -> Unit): Cancel {
        val entry = entryFor(target)
        val sub = Sub(why, only, onList)
        entry.subs += sub
        expire(entry)
        onList(listOf(entry))
        val start = Runnable {
            sub.start = null
            if (!sub.cancelled) ask(entry, why, only)
        }
        val dwell = dwellMs(why)
        if (dwell > 0) {
            sub.start = start
            main.postDelayed(start, dwell)
        } else {
            start.run()
        }
        return Cancel {
            sub.cancelled = true
            sub.start?.let { main.remove(it) }
            sub.start = null
            entry.subs.remove(sub)
        }
    }

    override fun session(request: PlayRequest): SourceSession = DefaultSourceSession(request, this)

    override fun forget() {
        for (entry in entries.values.toList()) {
            entry.calls.values.toList().forEach { cancelCall(it, notify = false) }
            entry.answers.clear()
            entry.states.clear()
            entry.failedAt.clear()
            entry.order.clear()
            entry.ranked = emptyList()
            entry.best = null
            entry.lastUsed = null
            entry.rankedFrom = emptySet()
            entry.version++
            val live = entry.subs.filterNot { it.cancelled || it.start != null }
            if (live.isEmpty()) {
                if (entry.subs.isEmpty()) entries.remove(entry.target.key)
                continue
            }
            val user = live.firstOrNull { !it.why.automatic }
            val only = live.map { it.only }.distinct().singleOrNull()
            notify(entry)
            ask(entry, (user ?: live.first()).why, only)
        }
    }

    /** This TV's plan changed: the lists in memory rank again from the answers they have (nothing is asked again). */
    fun rerank() {
        for (entry in entries.values.toList()) if (entry.answers.isNotEmpty()) rebuild(entry)
    }

    /**
     * A title's own language was stored (TMDB's details came after the add-ons' answers, or for a title never opened):
     * the lists ranked without it rank again, so copies in it aren't left below the viewer's dub.
     */
    fun languageLearnt() {
        for (entry in entries.values.toList()) if (entry.answers.isNotEmpty() && !entry.originalKnown) rebuild(entry)
    }

    // ---- For the session ----

    /** Drops [addon]'s kept answer for [target] (a link expired: refresh it once). */
    internal fun dropAnswer(target: SourceTarget, addon: Addon) {
        val entry = entries[target.key] ?: return
        if (entry.answers.remove(addon) != null) {
            entry.states.remove(addon)
            entry.order.remove(addon)
            rebuild(entry)
        }
    }

    internal fun link(addon: Addon): SecretUrl? = links(addon)

    /**
     * What real use found about [addon] ([AddonCheck]), for its status in Settings › Add-ons; null notes nothing.
     * [fromList]: a streams list's (it can't see a debrid problem that shows only when a link is opened).
     */
    internal fun noted(addon: Addon, outcome: AddonCheck.Outcome?, fromList: Boolean = false) {
        if (outcome != null) checks?.noted(addon, outcome, fromList)
    }

    /** The key of [target]'s row holding [release], also when it was merged into another release's row (×N). */
    internal fun rowKeyOf(target: SourceTarget, release: ReleaseKey): String? = entries[target.key]?.rowOf?.get(release.storeKey)

    /** [key]'s row of [target] started playing: it is Last used now, in the lists shown (not only once they rank again). */
    internal fun usedNow(target: SourceTarget, key: String) {
        val entry = entries[target.key] ?: return
        val row = entry.ranked.firstOrNull { it.key == key } ?: return
        if (entry.lastUsed?.key == key) return
        entry.lastUsed = row
        notify(entry)
    }

    // ---- Asking ----

    private fun dwellMs(why: LookupWhy): Long = when (why) {
        LookupWhy.PAGE -> PAGE_DWELL_MS
        LookupWhy.PAGE_SOON -> SOON_DWELL_MS
        else -> 0L
    }

    private fun entryFor(target: SourceTarget): Entry {
        entries[target.key]?.let { return it }
        val entry = Entry(target)
        entries[target.key] = entry
        trim()
        return entry
    }

    /** At most [KEEP] titles: the least recently used without listeners or requests go first. */
    private fun trim() {
        if (entries.size <= KEEP) return
        val it = entries.values.iterator()
        var over = entries.size - KEEP
        while (it.hasNext() && over > 0) {
            val e = it.next()
            if (e.subs.isEmpty() && e.calls.isEmpty()) {
                it.remove()
                over--
            }
        }
    }

    private fun ask(entry: Entry, why: LookupWhy, only: Addon?) {
        expire(entry)
        var changed = false
        for (addon in Addon.entries) {
            if (only != null && addon != only) continue
            val link = links(addon)
            if (link == null) {
                if (entry.states[addon] != AddonState.NotSet) changed = true
                entry.states[addon] = AddonState.NotSet
                continue
            }
            if (entry.answers.containsKey(addon)) continue
            val state = entry.states[addon]
            if (state is AddonState.Failed || state is AddonState.Busy) continue
            entry.calls[addon]?.let { call ->
                if (!why.automatic) call.user = true
                continue
            }
            if (!budget.mayAsk(addon, why)) {
                val until = budget.busyUntil(addon)
                if (until > 0) {
                    entry.states[addon] = AddonState.Busy(elapsed() + (until - wall()))
                    changed = true
                }
                continue
            }
            if (why.automatic) {
                automaticCalls[addon]?.takeIf { it.entry !== entry && !it.user }?.let { cancelCall(it, notify = true) }
            }
            send(entry, addon, link, why)
            changed = true
        }
        if (changed) notify(entry)
    }

    private fun send(entry: Entry, addon: Addon, link: SecretUrl, why: LookupWhy) {
        val since = elapsed()
        val call = Call(entry, addon, why.automatic, why)
        entry.states[addon] = AddonState.Asking(since, since + addon.answerLimitMs)
        entry.failedAt.remove(addon)
        entry.calls[addon] = call
        if (why.automatic) automaticCalls[addon] = call
        budget.asked(addon, why)
        val deadline = Runnable {
            call.deadline = null
            if (entry.calls[addon] === call && entry.states[addon] is AddonState.Asking) {
                entry.states[addon] = AddonState.Failed("didn't answer in ${addon.answerLimitMs / 1000} s")
                entry.failedAt[addon] = elapsed()
                notify(entry)
            }
        }
        call.deadline = deadline
        main.postDelayed(deadline, addon.answerLimitMs)
        val runtime = TitleDetails.runtimeMinutes(entry.target.title.runtime)
        val timeout = (addon.answerLimitMs + LATE_MS).toInt()
        io.execute {
            val result: Result<ParsedAnswer> = try {
                if (call.cancelled) throw IOException("cancelled")
                val json = http.get(link, entry.target, timeout, call.handle)
                Result.success(StreamParser.parse(addon, json, runtime))
            } catch (t: Throwable) {
                Result.failure(t)
            }
            main.post { arrived(call, result) }
        }
    }

    private fun arrived(call: Call, result: Result<ParsedAnswer>) {
        if (call.cancelled) return
        val entry = call.entry
        val addon = call.addon
        if (entry.calls[addon] === call) entry.calls.remove(addon)
        if (automaticCalls[addon] === call) automaticCalls.remove(addon)
        call.deadline?.let { main.remove(it) }
        call.deadline = null
        val now = elapsed()
        val parsed = result.getOrNull()
        noted(addon, AddonCheck.fromLookup(parsed, result.exceptionOrNull(), online()), fromList = true)
        when {
            parsed == null -> {
                val e = result.exceptionOrNull()
                if (e is StreamsHttp.Busy) {
                    busy(entry, addon)
                } else {
                    entry.states[addon] = AddonState.Failed(why(e))
                    entry.failedAt[addon] = now
                }
            }
            parsed.busy -> busy(entry, addon)
            parsed.streams.isEmpty() && parsed.notice != null -> {
                entry.states[addon] = AddonState.Failed(FailureWords.addonFailed(addon, parsed.notice).substringAfter(" · "))
                entry.failedAt[addon] = now
            }
            else -> {
                val keep = when {
                    parsed.streams.isEmpty() -> EMPTY_KEEP_MS
                    call.why == LookupWhy.NEXT_EPISODE -> NEXT_KEEP_MS
                    else -> KEEP_MS
                }
                entry.answers[addon] = Answer(parsed, now + keep)
                entry.states[addon] = AddonState.Answered(parsed.streams.size)
                entry.failedAt.remove(addon)
                entry.order.remove(addon)
                entry.order += addon
                rebuild(entry)
                return
            }
        }
        notify(entry)
    }

    private fun busy(entry: Entry, addon: Addon) {
        budget.busy(addon)
        entry.states[addon] = AddonState.Busy(elapsed() + Budget.SKIP_MS)
    }

    private fun why(e: Throwable?): String = when {
        !online() -> "no internet"
        e is SocketTimeoutException -> "didn't answer in time"
        e is UnknownHostException -> "couldn't be reached"
        e is StreamsHttp.Status -> "answered with an error (HTTP ${e.code})"
        e is IOException -> "couldn't be reached"
        else -> "answered with something unreadable"
    }

    private fun cancelCall(call: Call, notify: Boolean) {
        call.cancelled = true
        call.handle.cancel()
        call.deadline?.let { main.remove(it) }
        call.deadline = null
        val entry = call.entry
        if (entry.calls[call.addon] === call) {
            entry.calls.remove(call.addon)
            if (entry.states[call.addon] is AddonState.Asking) entry.states.remove(call.addon)
        }
        if (automaticCalls[call.addon] === call) automaticCalls.remove(call.addon)
        if (notify) notify(entry)
    }

    /** Drops answers past their time; a failure counts as final for [FAILED_FINAL_MS], a busy add-on until its time. */
    private fun expire(entry: Entry) {
        val now = elapsed()
        var dropped = false
        for (addon in Addon.entries) {
            entry.answers[addon]?.let { a ->
                if (now >= a.keepUntil) {
                    entry.answers.remove(addon)
                    entry.order.remove(addon)
                    if (entry.calls[addon] == null) entry.states.remove(addon)
                    dropped = true
                }
            }
            entry.failedAt[addon]?.let { at ->
                if (now - at >= FAILED_FINAL_MS && entry.calls[addon] == null) {
                    entry.failedAt.remove(addon)
                    entry.states.remove(addon)
                }
            }
            (entry.states[addon] as? AddonState.Busy)?.let { if (now >= it.until) entry.states.remove(addon) }
        }
        if (dropped) rebuild(entry)
    }

    // ---- The list ----

    private fun rebuild(entry: Entry) {
        val v = ++entry.version
        val from = entry.order.filterTo(EnumSet.noneOf(Addon::class.java)) { entry.answers.containsKey(it) }
        val answers = entry.order.mapNotNull { entry.answers[it]?.parsed?.streams }
        val target = entry.target
        val plan = thisTv.plan()
        val imaxAlways = runCatching { thisTv.imaxAlways() }.getOrDefault(false)
        val line = runCatching { lineMbps() }.getOrNull()
        val preferred = runCatching { language() }.getOrDefault("en")
        val subs = runCatching { subtitles() }.getOrNull()
        val now = wall()
        work.execute {
            // Always posted, so an answer never stays "looking" (a rank that throws keeps the list it had).
            val made = runCatching {
                // The film's own language, from what's stored of it (read here, off the main thread).
                val original = runCatching { originalLanguage(target.title) }.getOrNull()
                val input = RankInput(
                    plan = plan,
                    wanted = CopyLanguage.wanted(original, preferred),
                    measured = measured,
                    runtimeMin = TitleDetails.runtimeMinutes(target.title.runtime),
                    episode = target.episode != null,
                    lineMbps = line,
                    imaxAlways = imaxAlways,
                    subtitles = subs,
                    now = now,
                )
                // The same file from two add-ons is one row: by the exact size a play here measured, too.
                val merged = SourceMerge.mergeKeyed(answers, input.runtimeMin) { r -> measured(r.storeKey)?.spec?.sizeBytes?.takeIf { it > 0 } }
                val ranked = SourceRank.rank(merged.sources, input)
                val best = SourceRank.best(ranked)
                val last = runCatching { library.lastRelease(target.title, target.episode) }.getOrNull()
                val lastKey = last?.let { merged.rowOf[it.release.storeKey] }
                val lastUsed = lastKey?.let { k -> ranked.firstOrNull { it.key == k } }
                Ranked(merged.rowOf, ranked, best, lastUsed, SourceRank.why(ranked, input), input.wanted, original != null)
            }.getOrNull()
            main.post {
                if (entry.version != v) return@post
                entry.rankedFrom = from
                if (made != null) {
                    entry.rowOf = made.rowOf
                    entry.ranked = made.sources
                    entry.best = made.best
                    entry.lastUsed = made.lastUsed
                    entry.why = made.why
                    entry.plan = plan
                    entry.wanted = made.wanted
                    entry.originalKnown = made.originalKnown
                }
                notify(entry)
            }
        }
    }

    private fun listOf(entry: Entry): SourceList {
        val states = EnumMap<Addon, AddonState>(Addon::class.java)
        for (addon in Addon.entries) {
            val state = if (links(addon) == null) AddonState.NotSet else entry.states[addon] ?: AddonState.NotAsked
            // Answered, but its sources are still being ranked: not final yet, or another add-on's failure would make
            // the list "complete" without them (the rank's post brings the answer in).
            states[addon] = if (state is AddonState.Answered && addon !in entry.rankedFrom) {
                val now = elapsed()
                AddonState.Asking(now, now + addon.answerLimitMs)
            } else {
                state
            }
        }
        return SourceList(entry.target, states, entry.ranked, entry.best, entry.lastUsed, entry.why, entry.plan, entry.wanted)
    }

    private class Ranked(
        val rowOf: Map<String, String>,
        val sources: List<PlaybackSource>,
        val best: PlaybackSource?,
        val lastUsed: PlaybackSource?,
        val why: BestWhy?,
        val wanted: List<String>,
        val originalKnown: Boolean,
    )

    private fun notify(entry: Entry) {
        if (entry.subs.isEmpty()) return
        val list = listOf(entry)
        for (sub in entry.subs.toList()) if (!sub.cancelled) sub.onList(list)
    }

    companion object {
        const val PAGE_DWELL_MS = 1_500L
        const val SOON_DWELL_MS = 500L
        /** A request may run this long past its add-on's limit, so a late answer joins Sources and Next source. */
        const val LATE_MS = 20_000L
        const val KEEP_MS = 10 * 60_000L
        const val EMPTY_KEEP_MS = 60_000L
        /** The next episode's list: until it starts, or about 15 minutes after this one ends. */
        const val NEXT_KEEP_MS = 20 * 60_000L
        /** A failed add-on counts as answered (the waiting rule) for this long, then is asked again. */
        const val FAILED_FINAL_MS = 2 * 60_000L
        const val KEEP = 6

        /** The app's engine: the add-on vault, This TV, the library, its own threads; dropped on a network change. */
        fun create(app: Context): DefaultSourcesRepository {
            val handler = Handler(Looper.getMainLooper())
            val loop = object : MainLoop {
                override fun post(r: Runnable) {
                    handler.post(r)
                }

                override fun postDelayed(r: Runnable, delayMs: Long) {
                    handler.postDelayed(r, delayMs)
                }

                override fun remove(r: Runnable) = handler.removeCallbacks(r)
            }
            val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val online = { isOnline(connectivity) }
            val quality = app.getSharedPreferences("quality_seen", Context.MODE_PRIVATE)
            val budgetPrefs = app.getSharedPreferences("fireplayer_sources", Context.MODE_PRIVATE)
            var engine: DefaultSourcesRepository? = null
            val vault = AddonVault.of(app) { engine?.forget() }
            val checks = AddonChecks(
                records = Settings.records(app),
                links = vault::link,
                manifest = AddonChecks.URL_MANIFEST,
                streams = StreamsHttp.URL_STREAMS,
                background = AddonChecks.THREAD,
                main = loop,
                wall = System::currentTimeMillis,
                online = online,
            )
            val vod = VodRepositories.repository(app)
            val tv = ThisTvReader(app, app.getSharedPreferences(ThisTvReader.FILE, Context.MODE_PRIVATE), System::currentTimeMillis,
                Settings.of(app))
            val viewer = Prefs(app)
            val made = DefaultSourcesRepository(
                addons = vault,
                links = vault::link,
                thisTv = tv,
                library = RecordedLibrary(vod.library),
                http = StreamsHttp.URL_STREAMS,
                linkCheck = LinkCheck(LinkCheck.URL_HOP, online, SystemClock::elapsedRealtime),
                budget = Budget(object : Budget.Store {
                    override fun read(): String? = budgetPrefs.getString("budget", null)
                    override fun write(text: String) = budgetPrefs.edit().putString("budget", text).apply()
                }, System::currentTimeMillis),
                measured = { key ->
                    val spec = quality.getString(key, null)?.let { MediaSpec.decode(it) }
                    val health = CopyHealth.decode(quality.getString("h|$key", null))
                    // When it last played here: "✓ played" in Sources.
                    val at = spec?.playedAt?.takeIf { it > 0 } ?: health?.at?.takeIf { it > 0 }
                    if (spec == null && health == null) null else Measured(spec, health, at)
                },
                // The sound FirePlayer is set to (Settings › Playback), after the film's own language.
                language = { viewer.audioLanguage.ifEmpty { Locale.getDefault().language.ifEmpty { "en" } } },
                originalLanguage = { title -> vod.originalLanguage(title) },
                subtitles = { viewer.subtitleLanguage.ifEmpty { null } },
                main = loop,
                io = Executors.newFixedThreadPool(3, VodRepositories.threads("FirePlayerSources", Process.THREAD_PRIORITY_BACKGROUND)),
                work = Executors.newSingleThreadExecutor(VodRepositories.threads("FirePlayerSourcesRank", Process.THREAD_PRIORITY_BACKGROUND)),
                elapsed = SystemClock::elapsedRealtime,
                wall = System::currentTimeMillis,
                online = online,
                // With Faster downloads on, what two connections were measured to bring; one connection's otherwise.
                lineMbps = { LineRate.of(budgetPrefs, two = viewer.fasterDownloads).current() },
                checks = checks,
            )
            engine = made
            // A new plan for this TV (Settings › This TV): the lists kept in memory rank again; and as a title's own
            // language becomes known.
            tv.onChanged = { loop.post { made.rerank() } }
            vod.setOnOriginalLanguage { made.languageLearnt() }
            watchNetwork(connectivity, loop) { made.forget() }
            return made
        }

        @Suppress("DEPRECATION")
        @SuppressLint("MissingPermission")
        private fun isOnline(cm: ConnectivityManager?): Boolean {
            cm ?: return true
            return runCatching { cm.activeNetworkInfo?.isConnected == true }.getOrDefault(true)
        }

        /** Links are tied to the connection that fetched them (ElfHosted; Torrentio passes the IP on): drop all on a change. */
        @SuppressLint("MissingPermission")
        private fun watchNetwork(cm: ConnectivityManager?, loop: MainLoop, onChange: () -> Unit) {
            cm ?: return
            val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
            var current: Network? = null
            runCatching {
                cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        loop.post {
                            val before = current
                            current = network
                            if (before != null && before != network) onChange()
                        }
                    }

                    override fun onLost(network: Network) {
                        loop.post { if (current == network) onChange() }
                    }
                })
            }
        }
    }
}
