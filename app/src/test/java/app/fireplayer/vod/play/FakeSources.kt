package app.fireplayer.vod.play

import app.fireplayer.vod.Addon
import app.fireplayer.vod.AddonSave
import app.fireplayer.vod.AddonSettings
import app.fireplayer.vod.AddonState
import app.fireplayer.vod.Attempt
import app.fireplayer.vod.Cancel
import app.fireplayer.vod.EpisodeRef
import app.fireplayer.vod.Failure
import app.fireplayer.vod.FailureKind
import app.fireplayer.vod.Hdr
import app.fireplayer.vod.LastRelease
import app.fireplayer.vod.LookupWhy
import app.fireplayer.vod.Offer
import app.fireplayer.vod.OpenWhy
import app.fireplayer.vod.Opened
import app.fireplayer.vod.PlayRequest
import app.fireplayer.vod.PlaybackLibrary
import app.fireplayer.vod.PlaybackSource
import app.fireplayer.vod.Readiness
import app.fireplayer.vod.ReleaseKey
import app.fireplayer.vod.SecretUrl
import app.fireplayer.vod.SourceFacts
import app.fireplayer.vod.SourceGroup
import app.fireplayer.vod.SourceList
import app.fireplayer.vod.SourceSession
import app.fireplayer.vod.SourceTarget
import app.fireplayer.vod.SourcesRepository
import app.fireplayer.vod.StallWatch
import app.fireplayer.vod.Step
import app.fireplayer.vod.ThisTv
import app.fireplayer.vod.Title
import app.fireplayer.vod.TvCapabilities
import app.fireplayer.vod.TvPlan

/*
 * Fakes of the sources contract for the pages' and the player's logic. Links are placeholders on the reserved ".invalid"
 * domain: never a real add-on link or key.
 */

/** A clock moved by hand, with its timers. */
class FakeTicker(var time: Long = 1_000_000L) : Ticker {
    private class Job(val at: Long, val task: () -> Unit, var live: Boolean = true)
    private val jobs = ArrayList<Job>()

    override fun now(): Long = time

    override fun after(ms: Long, task: () -> Unit): Cancel {
        val j = Job(time + ms, task)
        jobs += j
        return Cancel { j.live = false }
    }

    /** Moves the clock on by [ms], running each timer due on the way, in order. */
    fun advance(ms: Long) {
        val end = time + ms
        while (true) {
            val next = jobs.filter { it.live && it.at <= end }.minByOrNull { it.at } ?: break
            next.live = false
            time = next.at
            next.task()
        }
        time = end
        jobs.removeAll { !it.live }
    }

    val pending: Int get() = jobs.count { it.live }
}

object Fixtures {
    val film = Title("tt0133093", app.fireplayer.vod.Kind.MOVIE, "Film", year = "1999", tmdbId = 603, runtime = "136 min")
    val show = Title("tt1695360", app.fireplayer.vod.Kind.SERIES, "Show", tmdbId = 1234)

    fun hash(i: Int): String = i.toString(16).padStart(40, '0')

    fun link(i: Int): SecretUrl = SecretUrl.of("https://link.invalid/$i")!!

    fun source(
        i: Int,
        addon: Addon = Addon.TORRENTIO,
        readiness: Readiness = Readiness.PROBABLY_READY,
        group: SourceGroup = SourceGroup.UHD,
        height: Int? = 2160,
        mbps: Double? = 25.0,
        also: Addon? = null,
    ): PlaybackSource {
        val offers = listOfNotNull(
            Offer(addon, readiness, "Release.$i.2160p.WEB-DL", "Release.$i.mkv", link = link(i)),
            also?.let { Offer(it, readiness, "Release $i", link = link(1000 + i)) },
        )
        return PlaybackSource(
            key = "k$i",
            release = ReleaseKey.of(hash(i), 0),
            facts = SourceFacts(height = height, hdr = setOf(Hdr.HDR10), origin = "WEB-DL", codec = "HEVC", bitDepth = 10,
                audio = "DD+", channels = "5.1", sizeBytes = 29_300_000_000L, avgMbps = mbps, group = "GRP", languages = listOf("en")),
            offers = offers,
            readiness = readiness,
            group = group,
        )
    }

    fun list(
        target: SourceTarget,
        sources: List<PlaybackSource>,
        states: Map<Addon, AddonState> = mapOf(Addon.TORRENTIO to AddonState.Answered(sources.size), Addon.MEDIAFUSION to AddonState.Answered(0)),
        best: PlaybackSource? = sources.firstOrNull { it.automatic },
        lastUsed: PlaybackSource? = null,
    ) = SourceList(target, states, sources, best, lastUsed)
}

class FakeLibrary : PlaybackLibrary {
    val releases = HashMap<String, LastRelease>()
    val recorded = ArrayList<List<Any?>>()

    private fun key(title: Title, episode: EpisodeRef?) = SourceTarget(title, episode).key

    override fun lastRelease(title: Title, episode: EpisodeRef?): LastRelease? = releases[key(title, episode)]

    override fun recordRelease(title: Title, episode: EpisodeRef?, release: LastRelease) {
        releases[key(title, episode)] = release
    }

    override fun recordPlayback(title: Title, episode: EpisodeRef?, positionMs: Long, durationMs: Long, creditsAtMs: Long?) {
        recorded += listOf(title.id, episode, positionMs, durationMs, creditsAtMs)
    }
}

/** A session whose answers the test gives ([answer]) and whose calls it reads. */
class FakeSession(override val request: PlayRequest) : SourceSession {
    override var list: SourceList? = null
    override val attempts = ArrayList<Attempt>()
    val opens = ArrayList<Pair<PlaybackSource?, OpenWhy>>()
    val fetched = ArrayList<PlaybackSource>()
    val started = ArrayList<PlaybackSource>()
    val failures = ArrayList<Failure>()
    var step: Step = Step.TryNext
    var switchAllowed = true
    var closed = false
    var nextAsked = ArrayList<EpisodeRef>()
    private var pending: ((Opened) -> Unit)? = null

    /** Stage 4's watch, as the test sets it. */
    var switchNow = false
    val stallEvents = ArrayList<String>()
    override val stalls = object : StallWatch {
        override fun playing(now: Long) { stallEvents += "playing" }
        override fun jumped(now: Long) { stallEvents += "jumped" }
        override fun buffering(now: Long, on: Boolean) { stallEvents += "buffering $on" }
        override fun excuse(now: Long) { stallEvents += "excused" }
        override fun shouldSwitch(now: Long) = switchNow
    }

    override fun setListener(listener: (() -> Unit)?) = Unit

    override fun open(source: PlaybackSource?, why: OpenWhy, onOpened: (Opened) -> Unit): Cancel {
        opens += source to why
        pending = onOpened
        return Cancel { if (pending === onOpened) pending = null }
    }

    override fun fetch(source: PlaybackSource, onOpened: (Opened) -> Unit): Cancel {
        fetched += source
        pending = onOpened
        return Cancel { if (pending === onOpened) pending = null }
    }

    val refreshed = ArrayList<PlaybackSource>()

    override fun refresh(source: PlaybackSource, addon: Addon?, onOpened: (Opened) -> Unit): Cancel {
        refreshed += source
        pending = onOpened
        return Cancel { if (pending === onOpened) pending = null }
    }

    /** The last open's answer. */
    fun answer(o: Opened) = pending?.invoke(o)

    fun ready(s: PlaybackSource, startMs: Long? = null, resumedNear: Boolean = false) =
        answer(Opened.Ready(s, s.offers.first(), s.offers.first().link, startMs, resumedNear))

    override fun started(source: PlaybackSource, offer: Offer) {
        started += source
    }

    override fun failed(source: PlaybackSource, failure: Failure): Step {
        failures += failure
        return step
    }

    /** Copies chosen by hand that turned out not to be the whole film ([SourceSession.notWhole]). */
    val notWholes = ArrayList<PlaybackSource>()

    override fun notWhole(source: PlaybackSource) {
        notWholes += source
    }

    /** The TV's network is away, as the test sets it. */
    var netDown = false

    override fun failureOf(errorCode: Int, httpStatus: Int?) = Failure(
        when {
            netDown -> FailureKind.NO_INTERNET
            httpStatus == 404 -> FailureKind.FILE
            httpStatus == 403 -> FailureKind.LINK_EXPIRED
            else -> FailureKind.UNKNOWN
        },
    )

    override fun online() = !netDown

    override fun maySwitch(now: Long) = switchAllowed

    /** The lighter source the session would offer, as the test sets it, and the ceilings it was asked with. */
    var lighterSource: PlaybackSource? = null
    val lighterAsked = ArrayList<Double>()
    override fun lighter(maxMbps: Double): PlaybackSource? {
        lighterAsked += maxMbps
        return lighterSource?.takeIf { (it.facts.avgMbps ?: Double.MAX_VALUE) <= maxMbps }
    }

    override fun lookUpNext(next: EpisodeRef, onList: (SourceList) -> Unit): Cancel {
        nextAsked += next
        return Cancel { }
    }

    /** Why the resume didn't take the copy it left, as the test sets it ([SourceSession.resumeMiss]). */
    var resumeMissWords: String? = null
    override fun resumeMiss(): String? = resumeMissWords

    /**
     * The offers that open the copy left, as the test sets them (the engine's same file from another add-on,
     * [SourceSession.opensLeft]); null: the default, its release or its very key.
     */
    var opensLeftBy: ((PlaybackSource, Offer) -> Boolean)? = null
    override fun opensLeft(s: PlaybackSource, o: Offer): Boolean = opensLeftBy?.invoke(s, o) ?: super.opensLeft(s, o)

    override fun close() {
        closed = true
    }
}

/** A repository whose look-ups the test answers ([answer]). */
class FakeSourcesRepository : SourcesRepository {
    val asked = ArrayList<Pair<SourceTarget, LookupWhy>>()
    val cancelled = ArrayList<SourceTarget>()
    var kept = HashMap<String, SourceList>()
    private val listeners = HashMap<String, (SourceList) -> Unit>()

    override val addons = object : AddonSettings {
        override fun isSet(addon: Addon) = true
        override fun summary(addon: Addon) = "link.invalid · …abcd"
        override fun save(addon: Addon, link: String) = AddonSave.SAVED
        override fun remove(addon: Addon) = Unit
    }

    override val thisTv = object : ThisTv {
        override fun capabilities(): TvCapabilities? = null
        override fun checkAgain(onDone: (TvCapabilities) -> Unit) = Unit
        override fun plan() = TvPlan(true, emptyList(), 2160, emptySet())
        override fun setPlan(plan: TvPlan) = Unit
        override fun useAutomatic() = Unit
    }

    val fakeLibrary = FakeLibrary()
    override val library: PlaybackLibrary get() = fakeLibrary

    override fun cached(target: SourceTarget): SourceList? = kept[target.key]

    override fun lookUp(target: SourceTarget, why: LookupWhy, only: Addon?, onList: (SourceList) -> Unit): Cancel {
        asked += target to why
        listeners[target.key] = onList
        return Cancel {
            cancelled += target
            if (listeners[target.key] === onList) listeners.remove(target.key)
        }
    }

    /** An answer for [target] to whoever still listens. */
    fun answer(list: SourceList) {
        kept[list.target.key] = list
        listeners[list.target.key]?.invoke(list)
    }

    override fun session(request: PlayRequest): SourceSession = FakeSession(request)

    override fun forget() = Unit
}
