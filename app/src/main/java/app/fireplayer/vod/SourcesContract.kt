package app.fireplayer.vod

import android.content.Context
import android.content.Intent
import android.net.Uri
import app.fireplayer.PlayerActivity
import app.fireplayer.player.CopyHealth
import app.fireplayer.player.MediaSpec
import app.fireplayer.vod.data.VodRepositories
import java.util.UUID

/*
 * Films & Shows step 3: where a title plays from, and how FirePlayer's player gets it (design-brief.md §6.1, 6.2,
 * 6.4, 6.5). Two engineers build against this file: the sources engine (vod/sources, vod/data) implements
 * [SourcesRepository], [SourceSession], [ThisTv], [AddonSettings] and [PlaybackLibrary]; the pages and the player use
 * them. Until the engine lands, [Sources.get] answers with a stub that knows no add-ons.
 *
 * Privacy (hard rule): an add-on's link carries the user's debrid key (Real-Debrid, TorBox…) or config, and a checked
 * link is a debrid download. Both live only in memory, inside [SecretUrl], whose toString shows the host and nothing else.
 * Neither is ever logged, printed, stored, put in an Intent or a test fixture, or shown in full; error text goes
 * through [redactLinks]. What is stored about a release is its [ReleaseKey] (info hash + file index) and add-on name.
 *
 * Threading: as in VodContract.kt, callbacks arrive on the main thread, none after [Cancel.cancel]; the network runs on
 * the engine's own background threads.
 */

// ---- Add-ons (Settings › Add-ons) -----------------------------------------------------------------------------------

/**
 * The playback add-ons (each with the user's debrid service: Real-Debrid, TorBox…), with stage 1's answer limit counted
 * from the first ask (§6.5). StremThru Torz searches its own database and, for at most 10 s, its indexers, then asks
 * the debrid service what it holds: 15 s.
 */
enum class Addon(val label: String, val answerLimitMs: Long) {
    TORRENTIO("Torrentio", 10_000L),
    MEDIAFUSION("MediaFusion", 25_000L),
    TORZ("StremThru Torz", 15_000L),
}

/**
 * A link holding a secret: an add-on's base link (debrid key or encrypted config in its path) or a checked
 * playback link. [toString] shows only the host; [reveal] is for the one place that opens it (the HTTP call, the
 * player's MediaItem) and its result must never reach a log, a preference, a database, an Intent or the screen.
 */
class SecretUrl private constructor(private val value: String) {
    /** The full link, to open it. Never log, store or show what this returns. */
    fun reveal(): String = value

    /** Where it points ("torrentio.strem.fun"), safe to show; null when it has no host. */
    val host: String? get() = parseLink(value)?.host?.removePrefix("www.")

    override fun toString(): String = "SecretUrl(${host ?: "?"})"
    override fun equals(other: Any?): Boolean = other is SecretUrl && other.value == value
    override fun hashCode(): Int = value.hashCode()

    companion object {
        /** [url] as a secret when it is an http(s) link; null otherwise. */
        fun of(url: String): SecretUrl? {
            val clean = url.trim()
            val ok = clean.startsWith("https://", ignoreCase = true) || clean.startsWith("http://", ignoreCase = true)
            return if (ok && clean.length > 8) SecretUrl(clean) else null
        }
    }
}

/**
 * Encodes the characters a strict URI parser refuses but add-on links carry: Torrentio's options are separated by
 * "|" (e.g. "…/providers=yts|realdebrid=…/manifest.json"), and a pasted link may hold spaces or braces. Characters
 * already percent-encoded stay as they are.
 */
fun encodeLinkChars(url: String): String {
    val sb = StringBuilder(url.length + 8)
    for (ch in url.trim()) {
        when (ch) {
            '|', ' ', '"', '<', '>', '\\', '^', '`', '{', '}' -> sb.append('%').append(String.format(java.util.Locale.ROOT, "%02X", ch.code))
            else -> sb.append(ch)
        }
    }
    return sb.toString()
}

/** [url] parsed leniently (see [encodeLinkChars]); null when it still isn't a link. */
fun parseLink(url: String): java.net.URI? =
    runCatching { java.net.URI(encodeLinkChars(url)) }.getOrNull()

private val LINK = Regex("""(?i)\b(?:https?|stremio)://\S+""")

/** [text] with every link replaced by "<link>": for any error text that might quote an add-on or a resolved link. */
fun redactLinks(text: String?): String = text?.replace(LINK, "<link>").orEmpty()

/** What saving an add-on's link did ([AddonSettings.save]). */
enum class AddonSave {
    SAVED,
    /** Not an http(s) or stremio:// link. */
    NOT_A_LINK,
    /** A link, but not to this add-on (its manifest's id, when checked, or its shape). */
    NOT_THIS_ADDON,
    /** The encrypted store refused it (Android Keystore unavailable). */
    COULD_NOT_STORE,
}

/**
 * The add-ons' links as the user entered them in Settings › Add-ons: stored encrypted (Android Keystore), never
 * logged, never shown in full. Main thread; writes are small and synchronous, as [app.fireplayer.data.KeyStore]'s.
 */
interface AddonSettings {
    fun isSet(addon: Addon): Boolean

    /**
     * What Settings shows for a saved link: its host and the last 4 characters, "torrentio.strem.fun · …x7Qa"; null
     * when none is saved. Never more of the link.
     */
    fun summary(addon: Addon): String?

    /**
     * Saves [link] for [addon]: "stremio://" becomes "https://", a trailing "/manifest.json" or "/configure" is
     * dropped, and blank changes nothing (returns [AddonSave.NOT_A_LINK]). A change drops the sources kept in memory.
     */
    fun save(addon: Addon, link: String): AddonSave

    /** Forgets [addon]'s link; its sources stop at once. */
    fun remove(addon: Addon)
}

// ---- Sources: what the add-ons offer for a film or an episode -----------------------------------------------------

/**
 * A release file: torrent info hash (40 lowercase hex) and file index inside it (null: the add-on didn't say, a
 * single-file torrent). The only thing stored about a source (last release used, measured facts, health).
 */
data class ReleaseKey(val infoHash: String, val fileIdx: Int?) {
    /**
     * The key of what FirePlayer measured about this file ("quality_seen", CopyHealth): "rd|<hash>|<idx or -1>". The
     * "rd" is only a label (any debrid service): it stays, so what was measured before is still found.
     */
    val storeKey: String get() = "rd|$infoHash|${fileIdx ?: -1}"

    /**
     * The key of what FirePlayer measured about this file as [episode]'s (a film's: null): [storeKey], but for an
     * episode in a torrent whose file index the add-on didn't give (MediaFusion's season packs) "rd|<hash>|-1|<s>:<e>".
     * Every episode of such a pack has the same [storeKey], so what one episode's file measured is never another's.
     * Films and files with an index keep [storeKey], so what was measured before is still found. A record an earlier
     * version kept under an index-less [storeKey] is read only through [earlierKey].
     */
    fun measuredKey(episode: EpisodeRef?): String =
        if (fileIdx == null && episode != null) "$storeKey|${episode.season}:${episode.episode}" else storeKey

    /**
     * Where 1.0.360 and earlier kept what they measured of [episode]'s file when it has no index ([storeKey]), read while
     * nothing is kept under [measuredKey] yet; what's measured from then on goes there. Only for a torrent of that one
     * file, [single]: Torrentio gave it no index ([indexesEveryFile]). MediaFusion's season packs come without one as
     * well, and so do StremThru Torz's whose files it doesn't know yet ("-1" in its link): that record is then any of
     * their episodes'. Null where [measuredKey] is [storeKey] already.
     */
    fun earlierKey(episode: EpisodeRef?, single: Boolean): String? =
        if (fileIdx == null && episode != null && single) storeKey else null

    companion object {
        private val HEX40 = Regex("[0-9a-f]{40}")

        /**
         * Whether [addon] gives each file of a torrent of several its index, so a file it gives none is its torrent's only
         * one ([earlierKey]): Torrentio. MediaFusion doesn't for an episode of a season pack; StremThru Torz writes "-1"
         * for any torrent whose files it doesn't know yet (an indexer's result, a pack not looked into), and its file is
         * then picked by the episode as the link is opened.
         */
        fun indexesEveryFile(addon: Addon): Boolean = addon == Addon.TORRENTIO

        /** A key from an info hash in any case; null unless it is 40 hex digits. A negative index counts as none. */
        fun of(infoHash: String?, fileIdx: Int?): ReleaseKey? {
            val hash = infoHash?.trim()?.lowercase() ?: return null
            if (!HEX40.matches(hash)) return null
            return ReleaseKey(hash, fileIdx?.takeIf { it >= 0 })
        }

        /** Reads a [storeKey] back; null when it isn't one. */
        fun parse(storeKey: String?): ReleaseKey? {
            val p = storeKey?.split('|') ?: return null
            if (p.size != 3 || p[0] != "rd") return null
            return of(p[1], p[2].toIntOrNull())
        }
    }
}

/** HDR kinds a source or a TV has. SDR is none of them. */
enum class Hdr(val label: String) { DOLBY_VISION("DV"), HDR10_PLUS("HDR10+"), HDR10("HDR"), HLG("HLG") }

/** Sound formats a TV or receiver can take as they are (passthrough). PCM and AAC always play. */
enum class SoundFormat(val label: String) {
    AC3("Dolby Digital"), EAC3("Dolby Digital Plus"), EAC3_JOC("Dolby Atmos (DD+)"),
    TRUEHD("Dolby TrueHD"), DTS("DTS"), DTS_HD("DTS-HD"),
}

/**
 * Whether the debrid service (Real-Debrid, TorBox…) probably has the file cached. The add-ons' marks are guesses
 * (§6.5: "probably on Real-Debrid").
 */
enum class Readiness {
    /** Torrentio `[RD+]` / `[TB+]` (any service's "+"), MediaFusion's and StremThru Torz's ⚡: probably cached. */
    PROBABLY_READY,
    /** Torrentio `[RD download]` / `[TB download]`, MediaFusion ⏳, Torz without ⚡, or "uncached" words: never picked automatically. */
    NOT_READY,
    UNKNOWN,
}

/** The Sources panel's groups after "Best for this TV", in order (§6.5); [startsOpen] false for the last four. */
enum class SourceGroup(val label: String, val startsOpen: Boolean) {
    UHD("4K", true),
    FHD("1080p", true),
    LOWER("720p and lower", false),
    /** 3D (side by side, over-under), whatever its resolution. */
    THREE_D("3D", false),
    /** Resolution not stated. */
    OTHER("Resolution not stated", false),
    /** CAM, TeleSync, TeleCine, screener: never picked automatically. */
    CINEMA("Cinema recordings", false),
    /** Not cached by the debrid service yet (the name stays: it is only an id). */
    NOT_ON_RD("Not cached yet", false),
}

/** How likely a source plays well on this TV under its [TvPlan] (ranked first). */
enum class TvFit { MEASURED_OK, LIKELY, UNKNOWN, LIKELY_NOT }

/**
 * The steps of "Best for this TV" ([app.fireplayer.vod.sources.SourceRank]), in order: each only breaks ties of the
 * ones before. [IMAX_ALWAYS] is first only with Settings › This TV › IMAX first: always.
 */
enum class RankStep {
    IMAX_ALWAYS, PLAYS, CACHED, LANGUAGE_KNOWN, ROOM, HEIGHT, HDR, RATE, KIND, DYNAMIC_HDR, IMAX, LANGUAGE, SOUND,
    SMOOTH, SIZE, SEEDERS,
}

/**
 * Why the Best is first: the copy it beat ([beatenKey], a row of the same list) and the [step] that decided it, with
 * what the beaten copy lacks in plain words ([lacks]: "no HDR10+", "not cached yet"), and the HDR this TV shows of the
 * Best ([hdr]: a Dolby Vision file played as HDR10+ on a TV without Dolby Vision is HDR10+). No beaten copy when the
 * others differ from it only in sound, size or seeders.
 */
data class BestWhy(
    val bestKey: String,
    val hdr: Set<Hdr>,
    val beatenKey: String? = null,
    val step: RankStep? = null,
    val lacks: String? = null,
)

/**
 * What the add-ons say about a file, parsed from their text (r3_sources-organize §1.3). Missing facts are null or empty,
 * never guessed. Labelled "from Torrentio/MediaFusion" until FirePlayer measures them ([PlaybackSource.measured]).
 */
data class SourceFacts(
    /** 2160, 1080, 720, 576, 480; null when not stated. */
    val height: Int? = null,
    val hdr: Set<Hdr> = emptySet(),
    /** Dolby Vision profile when stated or measured (5, 7, 8). */
    val dvProfile: Int? = null,
    /** "REMUX", "BluRay", "WEB-DL", "WEBRip", "HDTV", "CAM"… */
    val origin: String? = null,
    /** "HEVC", "H.264", "AV1". */
    val codec: String? = null,
    val bitDepth: Int? = null,
    /** "DD+ Atmos", "TrueHD Atmos", "DTS-HD MA", "AAC". */
    val audio: String? = null,
    /** "5.1", "7.1", "2.0". */
    val channels: String? = null,
    val sizeBytes: Long? = null,
    /** Average Mb/s from size × 8 ÷ runtime ("≈25 Mb/s"); null without both. */
    val avgMbps: Double? = null,
    /** Release group ("FLUX"). */
    val group: String? = null,
    /** Short language codes ("en", "ru"); empty when not stated. */
    val languages: List<String> = emptyList(),
    /** Subtitle languages the name states ("ESub": "en"); empty when not stated. */
    val subs: List<String> = emptyList(),
    val seeders: Int? = null,
    /** The file sits in a season pack or collection. */
    val pack: Boolean = false,
    /** [sizeBytes] is the file's to the byte (an add-on's videoSize, or what a play here measured), not a rounded "29.26 GB". */
    val sizeExact: Boolean = false,
    /**
     * The only size the add-ons gave is the torrent's (an episode's season pack in all), or may be ([packUnclear]), and
     * no estimate was made of the file's, so [sizeBytes] and [avgMbps] are null: the episode's own file could be any
     * size. Memory only.
     */
    val torrentTotal: Boolean = false,
    /**
     * [sizeBytes] is an estimate, never the file's: its season pack's total ([packBytes]) shared by [packEpisodes]
     * episodes. Ranked as a stated size; never merges or tells files apart. Memory only.
     */
    val sizeEstimated: Boolean = false,
    /**
     * An episode's add-on's one size when it is its season pack's total, or may be ([packUnclear]), while nothing better
     * is known of the file ("(pack 10.2 GB)", "(10.2 GB)"); beside the file's size a play here measured, the pack's total
     * when it is known to be one ("1.9 GB (pack 18.9 GB)": [app.fireplayer.vod.sources.SourceMerge.packBeside]); null
     * otherwise. Memory only.
     */
    val packBytes: Long? = null,
    /** The episodes [packBytes] was shared by for [sizeBytes]'s estimate; null without one. Memory only. */
    val packEpisodes: Int? = null,
    /** [packBytes] may be the pack's or the episode's: nothing tells. Memory only. */
    val packUnclear: Boolean = false,
)

/**
 * What FirePlayer measured itself when this file played here before, keyed by [ReleaseKey.measuredKey] (a copy of no
 * release known: by its [Offer.copyKey]).
 */
data class Measured(val spec: MediaSpec?, val health: CopyHealth?, val at: Long?)

/** One add-on's offer of a file: its own words (for Details, no links), its ready mark, and the link to check. */
data class Offer(
    val addon: Addon,
    val readiness: Readiness,
    /** The release name / description as the add-on wrote it (never a link). */
    val text: String,
    val filename: String? = null,
    /** behaviorHints.bingeGroup: next episode prefers the same one (§6.5). */
    val bingeGroup: String? = null,
    /** The add-on's play link: memory only, opened only at Play (stage 2). */
    val link: SecretUrl,
    /** The file this offer opens (a merged row's offers can be different re-uploads); null: the row's [PlaybackSource.release]. */
    val release: ReleaseKey? = null,
    /**
     * The size of the file this offer opens, to the byte: as a play here measured it, else the add-on's exact one; null
     * when only rounded or not known. Its own, not its row's ([app.fireplayer.vod.sources.FileSet]). Memory only.
     */
    val size: Long? = null,
    /**
     * For an offer of no file known (its add-on gave no info hash: [release] null, and none in its row), what a play here
     * measured of it is kept and found by ([app.fireplayer.vod.sources.CopyKey]: "x|mediafusion|<print>|tt…:1:1", from its
     * words, never its link); null for an offer of a release. Memory only.
     */
    val copyKey: String? = null,
)

/**
 * One row of the Sources panel: a file, merged across add-ons and re-uploads (same [release], or same normalised
 * filename within 1 % size). [offers] are in the order to try: ready mark first, then Torrentio.
 */
data class PlaybackSource(
    /** Stable within a [SourceList] (rows keep focus as answers arrive). */
    val key: String,
    val release: ReleaseKey?,
    val facts: SourceFacts,
    val offers: List<Offer>,
    /** The same release under several hashes ("×3"). */
    val copies: Int = 1,
    val readiness: Readiness,
    val group: SourceGroup,
    val fit: TvFit = TvFit.UNKNOWN,
    /** At most one amber warning ("Dolby Vision 7: may not play here"). */
    val warning: String? = null,
    val measured: Measured? = null,
    /**
     * A film's copy the viewer moved in its Details: true to 4K IMAX, false to 4K, whatever its name says; null when
     * never moved ([app.fireplayer.vod.sources.SourceRank.isImaxCopy]).
     */
    val imaxMove: Boolean? = null,
) {
    val addons: List<Addon> get() = offers.map { it.addon }.distinct()
    val bothAddons: Boolean get() = addons.size > 1

    /** Never picked by itself: not cached by the debrid service yet, or a cinema recording (§6.5). */
    val automatic: Boolean get() = readiness != Readiness.NOT_READY && group != SourceGroup.CINEMA
}

/** One add-on's part in a look-up. [final] states count as answered for the waiting rule (r3_prefetch §2.7). */
sealed class AddonState {
    abstract val final: Boolean

    /** No link saved for it. */
    data object NotSet : AddonState() { override val final = true }

    /** Not asked (the page's wait hasn't passed, or its budget is spent for early look-ups). */
    data object NotAsked : AddonState() { override val final = false }

    /** Asked at [since]; the waiting rule gives up at [deadline] (both elapsedRealtime ms). */
    data class Asking(val since: Long, val deadline: Long) : AddonState() { override val final = false }

    data class Answered(val count: Int) : AddonState() { override val final = true }

    /** Down, timed out, or refused; [why] is already redacted. */
    data class Failed(val why: String) : AddonState() { override val final = true }

    /** Said it is busy (ElfHosted 🐢 or HTTP 429): skipped until [until] (elapsedRealtime ms). */
    data class Busy(val until: Long) : AddonState() { override val final = true }
}

/** What a look-up is for: a film, or one episode of a show. */
data class SourceTarget(val title: Title, val episode: EpisodeRef? = null) {
    /** The Stremio stream type: "movie" or "series". */
    val streamType: String get() = if (episode == null && title.kind == Kind.MOVIE) "movie" else "series"

    /** "tt0133093", or "tt1695360:2:5" for an episode. */
    val streamId: String get() = episode?.let { Episode.idOf(title.id, it.season, it.episode) } ?: title.id

    /** The memory cache's key, and the path after the add-on's base: "stream/series/tt1695360:2:5.json". */
    val key: String get() = "stream/$streamType/$streamId.json"
}

/**
 * The merged, ranked sources for one [target] so far, and each add-on's state. [sources] are best first ("Best for
 * this TV" order, which every group keeps).
 */
data class SourceList(
    val target: SourceTarget,
    val states: Map<Addon, AddonState>,
    val sources: List<PlaybackSource>,
    /** The top automatic pick, or null (none ready). Shows as "Best so far" until [complete]. */
    val best: PlaybackSource?,
    /**
     * The copy used last time for this film or episode (its release, or a copy of no release known by its key), when
     * offered (it may be [best] too).
     */
    val lastUsed: PlaybackSource? = null,
    /** What put [best] first and what it beat ([app.fireplayer.vod.sources.SourceRank.why]); null when not worked out. */
    val why: BestWhy? = null,
    /** The plan it was ranked for: what this TV shows of each copy, in the Sources window's words; null when not ranked. */
    val plan: TvPlan? = null,
    /** The sound languages it was ranked for ([app.fireplayer.player.CopyLanguage.wanted]: the film's own first); empty when not known. */
    val wanted: List<String> = emptyList(),
) {
    /** Every add-on is final: Play goes straight to checking the link. */
    val complete: Boolean get() = states.values.all { it.final }

    /** For the strip: who is still looking ("MediaFusion · still looking"). */
    val stillLooking: List<Addon> get() = states.filterValues { !it.final }.keys.toList()

    fun inGroup(group: SourceGroup): List<PlaybackSource> = sources.filter { it.group == group }
}

/** Why a look-up runs: it sets the wait, the budget and whether a newer one may cancel it (§6.4, r3_prefetch §2). */
enum class LookupWhy(val automatic: Boolean) {
    /** The title page's Play target, after 1.5 s on the page. Within MediaFusion's early budget only. */
    PAGE(true),
    /** The same after 0.5 s: a resume point, or opened from Next Episodes or a Continue card's hold menu. */
    PAGE_SOON(true),
    /** The next episode, once, in the last ~4 minutes of this one. */
    NEXT_EPISODE(true),
    /** Play, Resume, an episode's OK, OK on a Continue card: now, never blocked by a budget. */
    PLAY(false),
    /** The Sources button or panel: now, never blocked by a budget. */
    SOURCES(false),
    /**
     * The viewer rests on what OK would play (a Continue card, a title page's Play) and its link is checked ahead
     * ([SourcesRepository.prepare]): now (the rest was the wait), within MediaFusion's early budget as a page's is.
     */
    AHEAD(true),
}

/**
 * The sources engine. One per process ([Sources.get]). Lists are kept in memory only (10 min, 6 titles; an empty
 * answer 60 s) and dropped on a network change, an add-on change or a debrid account stop. A link is opened ahead of
 * Play only for the one copy OK would play, cached, while the viewer rests on it ([prepare]).
 */
interface SourcesRepository {
    val addons: AddonSettings
    val thisTv: ThisTv
    val library: PlaybackLibrary

    /** Whether each saved add-on and its debrid service work (Settings › Add-ons); null where nothing checks them. */
    val checks: app.fireplayer.vod.sources.AddonChecks? get() = null

    /** What is kept for [target] now, complete or not; null when nothing was asked or it expired. */
    fun cached(target: SourceTarget): SourceList?

    /**
     * Sources for [target]. [onList] is called at once with what is known (kept answers, or every add-on [AddonState.
     * NotAsked]/[AddonState.Asking]), then on each change; the last call has [SourceList.complete]. An automatic [why]
     * waits first (PAGE 1.5 s, PAGE_SOON 0.5 s): cancelling during the wait sends nothing. A request already running
     * for the same target is joined, never sent twice; a newer automatic look-up cancels an older automatic one on the
     * same add-on; requests the user started are never cancelled by that. Cancelling stops the callbacks only: a sent
     * request finishes and is kept. With [only], just that add-on is asked (a Continue card's same file).
     */
    fun lookUp(target: SourceTarget, why: LookupWhy, only: Addon? = null, onList: (SourceList) -> Unit): Cancel

    /** The engine for one play of a film or episode (stages 1-4), handed to the player in [PlayRequest]. */
    fun session(request: PlayRequest): SourceSession

    /** Drops every kept list (the add-ons changed, a debrid account problem). */
    fun forget()

    /**
     * The viewer moved [source], a 4K copy of the film [target], to the 4K IMAX group ([imax]) or to 4K (its Details):
     * kept for that film's file, and its list ranks again at once.
     */
    fun moveImax(target: SourceTarget, source: PlaybackSource, imax: Boolean) = Unit

    /**
     * The viewer rests on what OK would play, [request] (a Continue card's, a title page's Play): the one copy its play
     * would pick has its link checked now when that copy is cached (never one the debrid service would have to fetch),
     * so OK starts from it ([app.fireplayer.vod.sources.LinkPrep]). One at a time, never while a film plays, kept in
     * memory briefly and never stored. Cancelling (focus moved on, the page went) drops it unless a play took it. Main
     * thread.
     */
    fun prepare(request: PlayRequest): Cancel = Cancel {}
}

// ---- Handing a play to the player ----------------------------------------------------------------------------------

/** Where playback starts. Explicit either way: FirePlayer's own launches never ask "Resume from?". */
sealed class StartAt {
    data object Beginning : StartAt()
    data class At(val positionMs: Long) : StartAt()
}

/** Where Play was pressed: the player's Back returns there, and Continue asks the same add-on first. */
enum class PlayOrigin { TITLE_PAGE, SOURCES_PANEL, CONTINUE_CARD, NEXT_EPISODE }

/**
 * Everything the player needs to play a film or episode from the add-ons. Held in memory ([PlayHandoff]); the Intent
 * carries only a token. Ids go to subtitles (OpenSubtitles by IMDb id), Skip Intro and history.
 */
data class PlayRequest(
    val target: SourceTarget,
    val start: StartAt,
    val origin: PlayOrigin,
    /**
     * What stage 1 found before the player opened (the title page waited); null: the player looks it up. Not yet
     * [SourceList.complete] when OK was pressed again while it waited: the player then plays the best in it without
     * waiting for the add-on still looking.
     */
    val list: SourceList? = null,
    /** A row chosen in Sources, or "OK again plays the best so far"; null: the session picks. */
    val chosen: PlaybackSource? = null,
    /**
     * On a resume only (Resume, a Continue card, the Episodes sheet's resume): the copy left to play again (its release, or
     * a copy of no release known by its key), that very file first, from its add-on first.
     */
    val preferred: LastRelease? = null,
    /** The source the previous episode played from: the next episode prefers the same kind (§6.5). */
    val like: PlaybackSource? = null,
    /** The episode after this one, when the page knows it (Next episode); null: the player finds it. */
    val next: EpisodeRef? = null,
    /** Room was made before the film started (film mode, §6.6): the page restores its snapshot on return. */
    val filmMode: Boolean = false,
    /** When OK was pressed for this play (elapsedRealtime ms; 0: not known), for the start's timing in the log. */
    val pressedAt: Long = 0L,
) {
    val imdbId: String get() = target.title.id
    val tmdbId: Int? get() = target.title.tmdbId
    val season: Int? get() = target.episode?.season
    val episode: Int? get() = target.episode?.episode
}

/**
 * Hands a [PlayRequest] to PlayerActivity without any link in the Intent: [intent] puts the request here under a
 * random token and starts the player with [ACTION] and a "fireplayer-vod:" data Uri holding only the token. The player
 * reads it with [get] (again after a recreate) and drops it with [done]. At most [KEEP] are kept; a process restart
 * loses them, and the player then closes as it does for an old run.
 */
object PlayHandoff {
    const val ACTION = "app.fireplayer.action.PLAY_SOURCES"
    const val EXTRA_TOKEN = "app.fireplayer.extra.HANDOFF_TOKEN"
    const val SCHEME = "fireplayer-vod"
    const val KEEP = 4

    private val waiting = LinkedHashMap<String, PlayRequest>()
    private val early = HashMap<String, EarlyOpen>()

    @Synchronized
    fun put(request: PlayRequest): String {
        val token = UUID.randomUUID().toString()
        waiting[token] = request
        while (waiting.size > KEEP) {
            val old = waiting.keys.first()
            waiting.remove(old)
            early.remove(old)?.drop()
        }
        return token
    }

    @Synchronized
    fun get(token: String?): PlayRequest? = token?.let { waiting[it] }

    @Synchronized
    fun done(token: String?) {
        if (token != null) {
            waiting.remove(token)
            early.remove(token)?.drop()
        }
    }

    /**
     * Starts [request]'s play now, before the player's screen exists: its session picks the source and checks its
     * link (stage 2) while the page makes room and the player comes up, which is half a second or so on a Fire TV
     * Stick. The player takes it over with [takeEarly]. Not for a row chosen in Sources that isn't cached
     * (that one is fetched, which the player starts itself). Main thread.
     */
    fun startEarly(token: String, request: PlayRequest, sources: SourcesRepository, now: Long) {
        val chosen = request.chosen
        if (chosen != null && chosen.readiness == Readiness.NOT_READY && request.origin == PlayOrigin.SOURCES_PANEL) return
        val open = runCatching { EarlyOpen(sources.session(request), chosen, now) }.getOrNull() ?: return
        synchronized(this) {
            if (waiting.containsKey(token)) early[token] = open else open.drop()
        }
    }

    /** The play begun by [startEarly] for [token], once; null when there's none (or it was taken before). */
    @Synchronized
    fun takeEarly(token: String?): EarlyOpen? = token?.let { early.remove(it) }

    /** The token of an Intent made by [intent]; null for any other. */
    fun tokenOf(intent: Intent?): String? =
        intent?.takeIf { it.action == ACTION }?.getStringExtra(EXTRA_TOKEN)

    /**
     * The Intent for PlayerActivity, explicit, in FirePlayer's own task. With [sources], the play's link check starts
     * now ([startEarly]) rather than when the player's screen is up; [now] is elapsedRealtime.
     */
    fun intent(context: Context, request: PlayRequest, sources: SourcesRepository? = null, now: Long = 0L): Intent {
        val token = put(request)
        if (sources != null) startEarly(token, request, sources, now)
        return Intent(ACTION, Uri.parse("$SCHEME:$token"), context, PlayerActivity::class.java)
            .putExtra(EXTRA_TOKEN, token)
            .putExtra("title", request.target.title.name)
    }
}

/**
 * A play's first open, begun at OK ([PlayHandoff.startEarly]) so the link is checked while the player's screen comes
 * up. What the session answers before the player is there is kept and handed over, in order, when it [attach]es; after
 * that the answers go straight to it. [drop] ends it when no player takes it. Main thread.
 */
class EarlyOpen(val session: SourceSession, chosen: PlaybackSource?, val startedAt: Long) {
    private val kept = ArrayList<Opened>()
    private var sink: ((Opened) -> Unit)? = null
    private var dropped = false

    /** Cancels the open (the player's own "opening"). */
    val cancel: Cancel = session.open(chosen, OpenWhy.START) { o ->
        val s = sink
        if (s != null) s(o) else kept += o
    }

    /** The player takes over: what was answered so far, then the rest as it comes. */
    fun attach(onOpened: (Opened) -> Unit) {
        if (dropped) return
        sink = onOpened
        val so = kept.toList()
        kept.clear()
        so.forEach(onOpened)
    }

    /** Nobody took it (the player never came up): the open and its session end. */
    fun drop() {
        if (dropped) return
        dropped = true
        cancel.cancel()
        if (sink == null) session.close()
    }
}

// ---- One play: stages 2-4 (the player drives, the session decides) -------------------------------------------------

/** Why the player asks for a source. */
enum class OpenWhy {
    /** The first start. */
    START,
    /** Stage 3: the first load passed its limit and the user chose "Try next source". */
    FIRST_LOAD_SLOW,
    /** Stage 4: [StallWatch.shouldSwitch]; counts against the 2 switches. */
    STALLS,
    /** A failure whose [Step] was [Step.TryNext]. */
    FAILED,
    /** The user's Next source (Picture & sound) or a Sources row: always allowed, at the current position. */
    USER,
}

/** Progress and outcome of [SourceSession.open]; [Looking] and [Checking] come before one final answer. */
sealed class Opened {
    /** Stage 1, in the player (a Continue card): who is still looking. */
    data class Looking(val list: SourceList) : Opened()

    /** Stage 2: checking [source]'s link (≤ 20 s, redirects off). */
    data class Checking(val source: PlaybackSource) : Opened()

    /**
     * Stage 2 passed: play [link] (memory only). [startMs] is where to start (null: [PlayRequest.start] or the current
     * position); [resumedNear] when it isn't the same file, for "Resumed near 1:02:03".
     */
    data class Ready(
        val source: PlaybackSource,
        val offer: Offer,
        val link: SecretUrl,
        val startMs: Long?,
        val resumedNear: Boolean,
    ) : Opened()

    /** No ready source worked: ask once whether the debrid service should fetch [source] ([SourceSession.fetch]). */
    data class AskFetch(val source: PlaybackSource) : Opened()

    /** Stop and tell the user ([message], plain words, no links). */
    data class Stopped(val failure: Failure, val message: String) : Opened()

    /** Nothing left to try within the limits; [message] points to Sources / Next source. */
    data class NoneLeft(val message: String) : Opened()
}

/** The failure table's categories (§6.5, rev_source-failures §6). */
enum class FailureKind {
    /** About the file (dead, archive, too big, refused for copyright): skip it and its copies in both add-ons. */
    FILE,
    /** The debrid service is fetching it ("being downloaded" notice): skip, don't come back this session. */
    NOT_READY,
    /** This device can't decode it: another format. */
    FORMAT,
    /**
     * This Stick showed no picture for it (its video decoder failed, none takes its picture, or no frame came while its
     * sound played: [app.fireplayer.player.PictureWatch]): skip it, the next one; the player remembers it for the copy.
     */
    NO_PICTURE,
    /**
     * Not the whole film or episode: it ended without really playing (a switch started it at or past its end), or a
     * film's copy is far shorter than the film ([app.fireplayer.vod.play.PlayRules.copyEnd]), most often another video
     * from the same torrent. Skip it, the next one; the player remembers it for the copy.
     */
    NOT_WHOLE,
    /** The receiver/TV refused the sound: decode on the Stick, same source. */
    AUDIO,
    /** Link expired or bound to another connection: re-ask and re-check once. */
    LINK_EXPIRED,
    /** One add-on is down: use the other. */
    ADDON_DOWN,
    /** "Copyright" right after a new add: wait about 30 s, retry once. */
    JUST_ADDED,
    /** MediaFusion's config is invalid: stop MediaFusion, Torrentio still plays. */
    CONFIG,
    /** No answer in time (stage 2 or stage 3). */
    SLOW,
    /** Account, subscription, limits, rate limits, outage: stop and tell. */
    ACCOUNT, LIMITS, RATE_LIMITED, OUTAGE,
    /** No internet at all: stop and tell. */
    NO_INTERNET,
    /** Unknown, often transient: retry once. */
    UNKNOWN,
}

/** A failure, by add-on when known; [detail] is already redacted, for the attempted list's Details. */
data class Failure(val kind: FailureKind, val addon: Addon? = null, val detail: String? = null) {
    companion object {
        /** [FailureKind.NO_PICTURE]'s [detail] when none of this TV's decoders takes the copy's format. */
        const val NO_DECODER = "no decoder for its format"
    }
}

/** What the player does after [SourceSession.failed]. */
sealed class Step {
    /** [SourceSession.open] with [OpenWhy.FAILED]. */
    data object TryNext : Step()
    /** The same source again after [afterMs]. */
    data class Retry(val afterMs: Long) : Step()
    /** The same source re-asked and re-checked ([SourceSession.open] with it), once. */
    data object Refresh : Step()
    /** Sound passthrough off for this play, same source. */
    data object DecodeOnDevice : Step()
    data class Stop(val message: String) : Step()
}

/** One try in the attempted list ("Tried 20:41 · didn't start"); [at] epoch ms. */
data class Attempt(val source: PlaybackSource, val at: Long, val why: OpenWhy, val failure: Failure? = null)

/**
 * Stage 4's watch (§6.5), fed by the player's buffering events (elapsedRealtime ms). Counts only after 30 s of
 * playing; a stall starting within 10 s of a jump (seek, source change, resume) doesn't count.
 */
interface StallWatch {
    /** Playback started or carried on after a stall. */
    fun playing(now: Long)
    /** A seek, a source change or a resume. */
    fun jumped(now: Long)
    fun buffering(now: Long, on: Boolean)
    /** The wait under way isn't the source's (the skip preview was loading beside the film): it isn't counted. */
    fun excuse(now: Long)
    /** 3 stalls in 2 minutes, one over 20 s, or over 25 % of 2 minutes buffering. */
    fun shouldSwitch(now: Long): Boolean
}

/**
 * The engine for one play of a film or episode. The player drives it: [open], then [started] at the first frame or
 * [failed]; stage 3 uses [firstLoadLimitMs]; stage 4 uses [stalls] and [maySwitch]. Limits: 3 start tries and 2
 * automatic switches per film or episode, switches at least 3 minutes apart; a source not cached is never
 * opened without [fetch].
 */
interface SourceSession {
    val request: PlayRequest

    /** The latest list (late answers join it, for Sources and Next source); null before any answer. */
    val list: SourceList?

    /** Every try so far, oldest first. */
    val attempts: List<Attempt>

    val stalls: StallWatch

    /** Called (main thread) when [list] or [attempts] change. */
    fun setListener(listener: (() -> Unit)?)

    /**
     * Picks [source] (or the next one by the rules: the preferred release, then "Best for this TV", skipping attempted
     * files and their copies) and checks its link. [onOpened] gets progress, then one final answer.
     */
    fun open(source: PlaybackSource? = null, why: OpenWhy = OpenWhy.START, onOpened: (Opened) -> Unit): Cancel

    /** The user agreed the debrid service may fetch [source] (asked once): adds it, never plays it automatically. */
    fun fetch(source: PlaybackSource, onOpened: (Opened) -> Unit): Cancel

    /** Stage 3's limit for [source]: [firstLoadLimitMs] by its data rate. */
    fun firstLoadLimitMs(source: PlaybackSource): Long = firstLoadLimitMs(source.facts.avgMbps)

    /**
     * [source]'s link expired: [addon] is asked again for the title and the same file's new link is checked, once. Not
     * an automatic switch. Default: the same source opened again.
     */
    fun refresh(source: PlaybackSource, addon: Addon?, onOpened: (Opened) -> Unit): Cancel = open(source, OpenWhy.FAILED, onOpened)

    /** The first frame of [source]: records it as the last release used and resets the stall watch. */
    fun started(source: PlaybackSource, offer: Offer)

    /** A failure of [source] (stage 2, 3 or a player error): adds it to the attempted list and says what to do. */
    fun failed(source: PlaybackSource, failure: Failure): Step

    /**
     * [source], chosen by hand, turned out not to be the whole film but plays on: it isn't the copy the title goes on
     * from (its release isn't kept, or the one kept before it is put back), and the list ranks again with its mark.
     */
    fun notWhole(source: PlaybackSource) = Unit

    /** A player error as a failure (PlaybackException code, HTTP status when the load error had one). */
    fun failureOf(errorCode: Int, httpStatus: Int?): Failure

    /** Whether the TV has a network now (asked while a play waits for it to come back). */
    fun online(): Boolean = true

    /** Whether an automatic switch is allowed at [now] (elapsedRealtime ms); after the last one, a note points to Next source. */
    fun maySwitch(now: Long): Boolean

    /**
     * The best source, by "Best for this TV", that needs no more than [maxMbps] on average and isn't the one playing:
     * what a film that keeps stalling for want of download speed is switched to ([app.fireplayer.vod.play.FilmSupply]).
     * Only sources that may be picked by themselves, whose rate is known. Null when there is none.
     */
    fun lighter(maxMbps: Double): PlaybackSource? = null

    /**
     * The key [source]'s file has in [list] now: rows merge as plays learn a file's exact size (the same file from two
     * add-ons), a merged row keeping the key of the add-on that answered first; when its release is in no row any more
     * (that add-on's answer is gone), the row holding the same file. [source]'s own key when unchanged.
     */
    fun rowKey(source: PlaybackSource): String = source.key

    /** What Next source plays now ([open] with no source): the one Picture & sound and the Sources window name. */
    fun nextPick(): PlaybackSource? = null

    /**
     * Why a resume isn't playing the copy it left ([PlayRequest.preferred]), in plain words for the log ("not cached
     * any more"; never a link); null when it is, or there was none to play.
     */
    fun resumeMiss(): String? = null

    /**
     * Whether [o], an offer of [s], opens the copy a resume left ([PlayRequest.preferred]): its release, from any add-on;
     * a copy of no release known by its key, or as the same file from another add-on (its size to the byte as a play here
     * measured it: [app.fireplayer.vod.sources.CopyLeft]). False when there's none.
     */
    fun opensLeft(s: PlaybackSource, o: Offer): Boolean = request.preferred?.openedBy(s, o) == true

    /**
     * How this play's first link came when it was checked ahead as the viewer rested on OK's copy
     * ([SourcesRepository.prepare]), in plain words for the start's line in the log ("link checked ahead: ready 2400 ms
     * before OK"); null when the play checked it itself. Never a link.
     */
    fun aheadNote(): String? = null

    /** The next episode's sources, once, in the last ~4 minutes ([LookupWhy.NEXT_EPISODE]); same-kind first. */
    fun lookUpNext(next: EpisodeRef, onList: (SourceList) -> Unit): Cancel

    /**
     * Every add-on is asked now, when only one was (a Continue card's resume asks the add-on of its copy first) or one's
     * answer was dropped since (past its time, at a refresh): the viewer opened the player's Sources window. Nothing is
     * asked again that is already in [list].
     */
    fun widen() = Unit

    /** The play is over: stops its requests. */
    fun close()
}

/** Stage 3: 30 / 45 / 60 s for sources under 20, 20–50 and over 50 Mb/s; 45 s when the rate isn't known (proposed). */
fun firstLoadLimitMs(avgMbps: Double?): Long = when {
    avgMbps == null || avgMbps <= 0.0 -> 45_000L
    avgMbps < 20.0 -> 30_000L
    avgMbps <= 50.0 -> 45_000L
    else -> 60_000L
}

// ---- This TV (Settings › This TV) ----------------------------------------------------------------------------------

/** What this Stick read from its TV ([ThisTv.checkAgain]); [checkedAt] epoch ms. */
data class TvCapabilities(
    /** HDR kinds the TV reports now; empty for SDR or HDR off on its input (e.g. Samsung's Input Signal Plus). */
    val hdr: Set<Hdr>,
    /** The largest display mode. */
    val maxWidth: Int,
    val maxHeight: Int,
    /** Sound the TV or receiver takes as it is. */
    val passthrough: Set<SoundFormat>,
    val checkedAt: Long,
    /**
     * Whether this Stick plays Dolby Vision profile 7 as its HDR10 base layer (its Dolby Vision decoders are
     * single-layer, or it has none: [app.fireplayer.player.DvBaseLayer.baseLayerInstead]); false when a decoder takes
     * profile 7 itself; null for a reading from before it was read.
     */
    val dv7AsHdr10: Boolean? = null,
)

/**
 * How "Best for this TV" ranks: [automatic] from [TvCapabilities], or the user's own. [hdrOrder] is in tiers, best
 * first, each tier equal (the user's Samsung: [[HDR10, HDR10_PLUS], [HLG]]; on a TV without Dolby Vision a Dolby Vision
 * file counts as the HDR10 or HDR10+ picture inside it: profile 8 always, profile 7 when [dv7AsHdr10]; profile 5 last;
 * SDR first when the TV shows no HDR).
 */
data class TvPlan(
    val automatic: Boolean,
    val hdrOrder: List<Set<Hdr>>,
    /** The tallest picture worth picking (2160, 1080). */
    val topHeight: Int,
    val sound: Set<SoundFormat>,
    /** An optional data-rate cap (Mb/s), never a file size. */
    val maxMbps: Int? = null,
    /** [TvCapabilities.dv7AsHdr10] of the TV's reading, whatever the plan: what this Stick does with profile 7. */
    val dv7AsHdr10: Boolean? = null,
    /**
     * [ThisTv.hdr10PlusReaches], whatever the plan: only then is HDR10+ preferred, and said to be what this TV shows.
     * Off by default: on the user's Sticks HDR10+ never reached the TV from FirePlayer (it gets the HDR10 inside).
     */
    val hdr10PlusReaches: Boolean = false,
)

/** Settings › This TV: the TV's capabilities, Check again, Automatic or a custom plan. Main thread. */
interface ThisTv {
    /** The last reading; null before the first. */
    fun capabilities(): TvCapabilities?

    /** Reads the TV again (display HDR types and modes, audio passthrough). */
    fun checkAgain(onDone: (TvCapabilities) -> Unit)

    fun plan(): TvPlan
    fun setPlan(plan: TvPlan)
    fun useAutomatic()

    /** Settings › This TV › IMAX first: always: a 4K IMAX copy this TV plays ranks first, as before (off: a tie-break). */
    fun imaxAlways(): Boolean = false

    fun setImaxAlways(on: Boolean) = Unit

    /** Settings › This TV › HDR10+ reaches this TV: off unless the TV was seen to show HDR10+ from FirePlayer. */
    fun hdr10PlusReaches(): Boolean = false

    fun setHdr10PlusReaches(on: Boolean) = Unit
}

// ---- The library: what playback records (stored on this TV; no syncing) --------------------------------------------

/**
 * The copy a film or episode last played from (the copy left), never a link: its [release] (info hash + file index) and
 * the add-on it played from; or, for a copy of no release known (its add-on gave no info hash: MediaFusion's, at times),
 * its [copyKey] ([app.fireplayer.vod.sources.CopyKey]: "x|mediafusion|<print>|tt…:1:9", the same across look-ups and
 * restarts) with [release] null. One of the two, never both.
 */
data class LastRelease(val addon: Addon, val release: ReleaseKey?, val at: Long, val copyKey: String? = null) {

    /** What it is known by: [release]'s [ReleaseKey.storeKey], else [copyKey]; null for neither (nothing to find). */
    val key: String? get() = release?.storeKey ?: copyKey

    /**
     * Whether [o], an offer of row [s], opens this copy: [release] its own file, else its row's (a merged row's offer of
     * no release known opens its row's file); a copy of no release known by that very [copyKey], whichever row it joined
     * since, never another of its add-on's copies. The same file from another add-on, known by its size to the byte as a
     * play here measured it, is [SourceSession.opensLeft]'s ([app.fireplayer.vod.sources.CopyLeft]).
     */
    fun openedBy(s: PlaybackSource, o: Offer): Boolean {
        val r = release
        if (r != null) return (o.release ?: s.release) == r
        val c = copyKey ?: return false
        return o.copyKey == c
    }

    companion object {
        /** A copy of no release known that played from [addon], by its [Offer.copyKey]. */
        fun ofCopy(addon: Addon, copyKey: String, at: Long): LastRelease = LastRelease(addon, null, at, copyKey)
    }
}

/**
 * What the player writes (any thread). Positions and Continue Watching go through [VodRepository.recordProgress]'s
 * rules; listeners are called, so rows and title pages refresh.
 */
interface PlaybackLibrary {
    /** The copy [title] (and [episode]) last played from (a release, or a copy of no release known); null when never. */
    fun lastRelease(title: Title, episode: EpisodeRef?): LastRelease?

    /** Keeps [release] as the copy [title] (and [episode]) last played from, in place of the one kept before. */
    fun recordRelease(title: Title, episode: EpisodeRef?, release: LastRelease)

    /** Forgets the releases [title] last played from (a show: every episode's), so its next resume is the ranking's pick. */
    fun forgetReleases(title: Title) = Unit

    /** Forgets the release [title] (and [episode]) last played from, that one only (a copy that wasn't the whole film). */
    fun forgetRelease(title: Title, episode: EpisodeRef?) = Unit

    /**
     * Where playback stopped, as [VodRepository.recordProgress]; also marks the film or episode watched when
     * [countsAsWatched] by what's known of this file's end: where its credits start ([creditsAtMs]) and where a real
     * scene after them starts ([sceneAtMs]); null: not known.
     */
    fun recordPlayback(title: Title, episode: EpisodeRef?, positionMs: Long, durationMs: Long, creditsAtMs: Long?, sceneAtMs: Long? = null)
}

/**
 * Watched (§6.2), decided where playback stopped ([positionMs] of [durationMs]) by what's known of the end of this very
 * file ([app.fireplayer.player.Segments]):
 * - a real scene after the credits ([sceneAtMs]: a film's post-credits scene; never a preview of the next episode): only
 *   from [WatchedRule.HEAD_START_MS] before it on, or at the very end ([WatchedRule.VERY_END_MS]); 90 % and the last 3
 *   minutes don't count.
 * - the credits' start ([creditsAtMs]) and no such scene: only from [WatchedRule.HEAD_START_MS] before it on, or at the
 *   very end; 90 % and the last 3 minutes don't count.
 * - neither: past 90 %, or the last 3 minutes ([Position.END_MS]), or the very end.
 * A time outside the file's second half isn't trusted ([WatchedRule.endAt]). The decision is stored with the record (its
 * watched mark), so it reads the same everywhere and after a restart ([Position.held]).
 */
fun countsAsWatched(positionMs: Long, durationMs: Long, creditsAtMs: Long?, sceneAtMs: Long? = null): Boolean {
    if (durationMs <= 0) return false
    if (durationMs - positionMs <= WatchedRule.VERY_END_MS) return true
    val end = WatchedRule.endAt(durationMs, creditsAtMs, sceneAtMs)
    if (end != null) return positionMs >= end - WatchedRule.HEAD_START_MS
    if (positionMs * 10 >= durationMs * 9) return true
    return durationMs - positionMs <= Position.END_MS
}

/**
 * Watched to the end, for a video another app opened here (its resume point, and Glass Home's row of them): with its
 * credits' start or a scene after them known, as [countsAsWatched]; without, the last 3 minutes, as always for these
 * (no 90 %).
 */
fun endReached(positionMs: Long, durationMs: Long, creditsAtMs: Long?, sceneAtMs: Long?): Boolean {
    if (WatchedRule.endAt(durationMs, creditsAtMs, sceneAtMs) != null) return countsAsWatched(positionMs, durationMs, creditsAtMs, sceneAtMs)
    return durationMs - positionMs < Position.END_MS
}

/** The numbers of the watched rule ([countsAsWatched]). */
object WatchedRule {
    /** Watched from this long before a known credits' start or scene after them (the viewer's "2 to 5 seconds"). */
    const val HEAD_START_MS = 3_000L

    /** This close to the file's end is its very end: watched whatever is known of the credits. */
    const val VERY_END_MS = 5_000L

    /**
     * Where watching to the end is reached in a file [durationMs] long: a real scene after the credits ([sceneAtMs])
     * when known, else the credits' start ([creditsAtMs]); null when neither is known. Only a time in the file's second
     * half counts (credits and what follows them are there; [app.fireplayer.player.SkipRules.fits] keeps only those).
     */
    fun endAt(durationMs: Long, creditsAtMs: Long?, sceneAtMs: Long?): Long? =
        trusted(durationMs, sceneAtMs) ?: trusted(durationMs, creditsAtMs)

    /**
     * A known real scene after the credits ([sceneAtMs], as [endAt] trusts it) that [positionMs] hasn't reached by the
     * rule ([countsAsWatched]): the end card's Exit then leaves the title where it is, in Continue Watching for the
     * scene, instead of at its end, watched (the first point of the rule). False with no such scene known.
     */
    fun sceneAhead(positionMs: Long, durationMs: Long, creditsAtMs: Long?, sceneAtMs: Long?): Boolean =
        trusted(durationMs, sceneAtMs) != null && !countsAsWatched(positionMs, durationMs, creditsAtMs, sceneAtMs)

    /** [t] when it lies in the second half of a file [durationMs] long ([endAt]); else null. */
    private fun trusted(durationMs: Long, t: Long?): Long? = t?.takeIf { durationMs > 0 && it >= durationMs / 2 && it < durationMs }
}

// ---- The engine's door ----------------------------------------------------------------------------------------------

/** The app's [SourcesRepository]. Until the engine lands it is [StubSources]: no add-ons, nothing to play. */
object Sources {
    @Volatile private var instance: SourcesRepository? = null

    fun get(context: Context): SourcesRepository =
        instance ?: synchronized(this) { instance ?: app.fireplayer.vod.sources.DefaultSourcesRepository.create(context.applicationContext).also { instance = it } }
}

/** Knows no add-ons: every look-up is complete and empty, and a play stops with "No add-ons yet". */
private class StubSources(private val app: Context) : SourcesRepository {
    private val saved = HashMap<Addon, SecretUrl>()

    override val addons = object : AddonSettings {
        override fun isSet(addon: Addon) = saved.containsKey(addon)
        override fun summary(addon: Addon) = saved[addon]?.let { it.host ?: addon.label }
        override fun save(addon: Addon, link: String): AddonSave {
            val url = SecretUrl.of(link.trim().replaceFirst(Regex("^(?i)stremio://"), "https://")) ?: return AddonSave.NOT_A_LINK
            saved[addon] = url
            return AddonSave.SAVED
        }
        override fun remove(addon: Addon) { saved.remove(addon) }
    }

    override val thisTv = object : ThisTv {
        private var plan = TvPlan(true, listOf(setOf(Hdr.HDR10, Hdr.HDR10_PLUS), setOf(Hdr.HLG)), 2160, emptySet())
        override fun capabilities(): TvCapabilities? = null
        override fun checkAgain(onDone: (TvCapabilities) -> Unit) =
            onDone(TvCapabilities(emptySet(), 1920, 1080, emptySet(), System.currentTimeMillis()))
        override fun plan() = plan
        override fun setPlan(plan: TvPlan) { this.plan = plan.copy(automatic = false) }
        override fun useAutomatic() { plan = plan.copy(automatic = true) }
    }

    override val library = object : PlaybackLibrary {
        override fun lastRelease(title: Title, episode: EpisodeRef?): LastRelease? = null
        override fun recordRelease(title: Title, episode: EpisodeRef?, release: LastRelease) = Unit
        override fun recordPlayback(
            title: Title, episode: EpisodeRef?, positionMs: Long, durationMs: Long, creditsAtMs: Long?, sceneAtMs: Long?,
        ) = VodRepositories.get(app).recordProgress(title, episode, positionMs, durationMs)
    }

    private fun empty(target: SourceTarget) =
        SourceList(target, Addon.entries.associateWith { AddonState.NotSet }, emptyList(), null)

    override fun cached(target: SourceTarget): SourceList? = null

    override fun lookUp(target: SourceTarget, why: LookupWhy, only: Addon?, onList: (SourceList) -> Unit): Cancel {
        onList(empty(target))
        return Cancel { }
    }

    override fun session(request: PlayRequest): SourceSession = stubSession(request)

    private fun stubSession(asked: PlayRequest): SourceSession = object : SourceSession {
        override val request: PlayRequest = asked
        override val list: SourceList? = empty(asked.target)
        override val attempts: List<Attempt> = emptyList()
        override val stalls = object : StallWatch {
            override fun playing(now: Long) = Unit
            override fun jumped(now: Long) = Unit
            override fun buffering(now: Long, on: Boolean) = Unit
            override fun excuse(now: Long) = Unit
            override fun shouldSwitch(now: Long) = false
        }
        override fun setListener(listener: (() -> Unit)?) = Unit
        override fun open(source: PlaybackSource?, why: OpenWhy, onOpened: (Opened) -> Unit): Cancel {
            onOpened(Opened.NoneLeft(NO_ADDONS))
            return Cancel { }
        }
        override fun fetch(source: PlaybackSource, onOpened: (Opened) -> Unit): Cancel = open(source, OpenWhy.USER, onOpened)
        override fun started(source: PlaybackSource, offer: Offer) = Unit
        override fun failed(source: PlaybackSource, failure: Failure): Step = Step.Stop(NO_ADDONS)
        override fun failureOf(errorCode: Int, httpStatus: Int?) = Failure(FailureKind.UNKNOWN)
        override fun maySwitch(now: Long) = false
        override fun lookUpNext(next: EpisodeRef, onList: (SourceList) -> Unit): Cancel =
            lookUp(SourceTarget(request.target.title, next), LookupWhy.NEXT_EPISODE, null, onList)
        override fun close() = Unit
    }

    override fun forget() = Unit

    private companion object {
        const val NO_ADDONS = "No add-ons yet: add Torrentio or MediaFusion in Settings › Add-ons."
    }
}
