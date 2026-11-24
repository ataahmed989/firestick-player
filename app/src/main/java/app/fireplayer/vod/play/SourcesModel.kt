package app.fireplayer.vod.play

import app.fireplayer.player.CopyLanguage
import app.fireplayer.vod.Addon
import app.fireplayer.vod.Attempt
import app.fireplayer.vod.PlaybackSource
import app.fireplayer.vod.Readiness
import app.fireplayer.vod.SourceGroup
import app.fireplayer.vod.SourceList
import app.fireplayer.vod.TvFit
import app.fireplayer.vod.sources.SourceRank
import java.util.Locale

/**
 * What the Sources panel lists (design-brief.md §6.5, r3_sources-organize §4), pure: at the top the add-ons' states and
 * the totals, then one "Best for this TV" row (the copy Play uses, and why in plain words), then the copies by kind:
 * 4K IMAX, 4K, 1080p, 720p, 3D, Resolution not stated, Cinema recordings, Not cached yet and, only when the title has
 * nothing at 720p or above ([SourceRank.withoutSd]), Lower quality. A heading shows only with rows, with their count.
 * 4K IMAX, 4K, 1080p and Lower quality start open with 5 rows and "Show N more", as does the first kind listed; the
 * others start closed with one "Show N" row. Every item has a stable [Item.key], so rows keep focus as answers arrive.
 * [view] is the same list as a model for a window to draw itself.
 */
object SourcesModel {

    enum class Kind { NOTE, HEADING, SOURCE, SHOW_MORE }

    /** The panel's sections under the Best row, in order; one [startsOpen] false stays closed until "Show N". */
    enum class Section(val label: String, val startsOpen: Boolean) {
        /** 2160p IMAX, IMX and open-matte copies ([PlayRules.imax]). */
        IMAX("4K IMAX", true),
        UHD("4K", true),
        FHD("1080p", true),
        /** 720p and up to 1080p. */
        HD("720p", false),
        THREE_D("3D", false),
        UNSTATED("Resolution not stated", false),
        CINEMA("Cinema recordings", false),
        NOT_CACHED("Not cached yet", false),
        /** Under 720p: only there when the title has nothing better ([SourceRank.withoutSd]). */
        LOWER("Lower quality", true),
    }

    /**
     * One entry. A [Kind.SOURCE] row has [source], its two lines ([label], and the quieter [note]) and its small marks on
     * the right ([value], two lines at most: "Playing", "Tried 20:41" / "didn't play", "Best", "Last used",
     * "✓ played Nov 12"). A [Kind.SHOW_MORE] row opens [section].
     */
    data class Item(
        val key: String,
        val kind: Kind,
        val label: String,
        val note: String? = null,
        val value: String? = null,
        val source: PlaybackSource? = null,
        val section: Section? = null,
        val chosen: Boolean = false,
    )

    /** Rows an open section shows before "Show N more". */
    const val FIRST_ROWS = 5

    const val BEST_LABEL = "Best for this TV"
    const val BEST_SO_FAR = "Best so far"

    /** The row of the copy playing, at the top of the player's list, and its mark. */
    const val PLAYING_KEY = "src:playing"
    const val PLAYING_NOW = "Playing now"

    /** The Best row's key: the same row while a better copy arrives, so focus stays on it. */
    const val BEST_KEY = "src:best"

    /** The readiness note under the totals. */
    const val READY_NOTE = "Cached on your debrid service unless under Not cached yet (the add-ons' guess) · ✓ played: played on this TV"

    const val NOT_ON_RD_NOTE = "Your debrid service has to download these first: they can't play straight away and use a download slot."

    /** The words [SourceRank.fit] gives a copy this connection was measured too slow for ("Needs 69 Mb/s · this connection brought 72"). */
    private const val SLOW_LINE_WORDS = "this connection brought"

    /** The section [s] is listed under; 4K IMAX for a [film]'s IMAX copies only ([SourceRank.isImaxCopy]). */
    fun sectionOf(s: PlaybackSource, film: Boolean = true): Section = when (s.group) {
        SourceGroup.NOT_ON_RD -> Section.NOT_CACHED
        SourceGroup.CINEMA -> Section.CINEMA
        SourceGroup.THREE_D -> Section.THREE_D
        SourceGroup.OTHER -> Section.UNSTATED
        SourceGroup.UHD -> if (PlayRules.imax(s, film)) Section.IMAX else Section.UHD
        SourceGroup.FHD -> Section.FHD
        SourceGroup.LOWER -> if (SourceRank.isSd(s)) Section.LOWER else Section.HD
    }

    /** Whether [list] is a film's (IMAX is a film's only). */
    fun film(list: SourceList): Boolean = list.target.episode == null

    /** A group of the Sources window: its copies in "Best for this TV" order, so its best ([best]) first. */
    class Group(val section: Section, val rows: List<PlaybackSource>) {
        val best: PlaybackSource get() = rows.first()
    }

    /**
     * The Sources window as a model to draw: the copy [playing] (the player's; null on the title page) and whether it is
     * the Best ([playingIsBest]); the [best] otherwise ("Best so far" while [soFar]) with its [reason]; what Next source
     * plays ([next], in [nextSection]); and the [groups] in order, each its best first.
     */
    class View(
        val playing: PlaybackSource?,
        val playingIsBest: Boolean,
        /** Best for this TV; null when it is the copy playing. */
        val best: PlaybackSource?,
        val soFar: Boolean,
        val reason: String?,
        val next: PlaybackSource?,
        val nextSection: Section?,
        val groups: List<Group>,
    )

    /**
     * [list] as a [View]. [playingKey]: the copy playing; [attempts]: this play's tries (Next source skips those that
     * failed). Next source is the next copy down the list from the one playing (or the Best), its own group first
     * ([SourceRank.nextOrder]).
     */
    fun view(list: SourceList, playingKey: String? = null, attempts: List<Attempt> = emptyList()): View {
        val film = film(list)
        val playing = list.sources.firstOrNull { it.key == playingKey }
        val best = list.best
        val playingIsBest = playing != null && playing.key == best?.key
        val failed = attempts.filter { it.failure != null }.mapTo(HashSet()) { it.source.key }
        val next = SourceRank.nextOrder(list.sources, (playing ?: best)?.key, film).firstOrNull { it.key !in failed }
        val bySection = list.sources.groupBy { sectionOf(it, film) }
        val groups = Section.entries.mapNotNull { section -> bySection[section]?.takeIf { it.isNotEmpty() }?.let { Group(section, it) } }
        return View(
            playing = playing,
            playingIsBest = playingIsBest,
            best = best?.takeIf { !playingIsBest },
            soFar = !list.complete,
            reason = best?.let { reason(it, list) }?.ifEmpty { null },
            next = next,
            nextSection = next?.let { sectionOf(it, film) },
            groups = groups,
        )
    }

    /**
     * The panel's entries for [list] (null: nothing asked yet). [opened] are sections the viewer opened (a closed one's
     * "Show N", or "Show N more" on an open one, which then lists all). [attempts] are this play's tries (the player's
     * panel), [playingKey] the source playing; [time] prints a try's time ("20:41"), [day] a measured date. [lineChars]
     * and [noteChars] are what a row's two lines have room for ([PlayRules.mainLine], [PlayRules.detailLine]).
     */
    fun items(
        list: SourceList?,
        now: Long,
        opened: Set<Section>,
        attempts: List<Attempt> = emptyList(),
        playingKey: String? = null,
        time: (Long) -> String = { "" },
        day: (Long) -> String = { "" },
        lineChars: Int = Int.MAX_VALUE,
        noteChars: Int = Int.MAX_VALUE,
    ): List<Item> {
        val out = ArrayList<Item>()
        if (list == null) {
            out += Item("note:looking", Kind.NOTE, "Looking for sources…")
            return out
        }
        val status = Addon.entries.mapNotNull { a -> list.states[a]?.let { PlayRules.stateText(a, it, now) } }
        out += Item("note:status", Kind.NOTE, status.joinToString(" · ").ifEmpty { PlayRules.NO_ADDONS_LINE })
        if (list.sources.isEmpty()) {
            out += Item("note:none", Kind.NOTE, if (list.complete) "No sources found." else "Looking for sources…")
            return out
        }
        val both = list.sources.count { it.bothAddons }
        val totals = buildString {
            append("${list.sources.size} source${if (list.sources.size == 1) "" else "s"}")
            if (both > 0) append(" · $both offered by more than one add-on")
        }
        out += Item("note:totals", Kind.NOTE, totals)
        out += Item("note:ready", Kind.NOTE, READY_NOTE)

        val best = list.best
        val film = film(list)
        val lastKey = list.lastUsed?.key
        val tried = attempts.associateBy({ it.source.key }, { it })

        // A row's small marks, two lines at most: what this play did with it, then Best and Last used, then whether
        // FirePlayer has played it on this TV.
        fun marks(s: PlaybackSource, bestRow: Boolean): String? {
            val a = tried[s.key]
            val state = when {
                s.key == playingKey -> listOf("Playing")
                a != null -> listOfNotNull("Tried ${time(a.at)}", if (a.failure != null) "didn't play" else null)
                else -> emptyList()
            }
            val lines = state + listOfNotNull(
                if (!bestRow && s.key == best?.key) "Best" else null,
                if (s.key == lastKey) "Last used" else null,
                s.measured?.at?.let { "✓ played ${day(it)}" },
            )
            return lines.take(2).joinToString("\n").ifEmpty { null }
        }

        fun row(s: PlaybackSource, section: Section) = Item(
            "src:${section.name}:${s.key}", Kind.SOURCE,
            PlayRules.mainLine(s, lineChars, film).ifEmpty { s.offers.firstOrNull()?.text.orEmpty() },
            PlayRules.detailLine(s, noteChars).ifEmpty { null },
            marks(s, bestRow = false), s, section, chosen = s.key == playingKey,
        )

        // In the player: the copy playing, with its details, first (the user's wish), then Best for this TV.
        list.sources.firstOrNull { it.key == playingKey }?.let { s ->
            out += Item(PLAYING_KEY, Kind.SOURCE,
                PlayRules.mainLine(s, lineChars, film).ifEmpty { s.offers.firstOrNull()?.text.orEmpty() },
                PlayRules.detailLine(s, noteChars).ifEmpty { null },
                PLAYING_NOW, s, chosen = true)
        }
        // Best for this TV: the copy Play uses, with why. Last used is only a mark on its own row below.
        if (best != null) {
            out += Item(BEST_KEY, Kind.SOURCE, if (list.complete) BEST_LABEL else BEST_SO_FAR, reason(best, list).ifEmpty { null },
                marks(best, bestRow = true), best, chosen = best.key == playingKey)
        }
        val bySection = list.sources.groupBy { sectionOf(it, film) }
        // The first kind listed is open even when its kind starts closed (a film with nothing above 720p).
        val first = Section.entries.firstOrNull { it != Section.CINEMA && it != Section.NOT_CACHED && !bySection[it].isNullOrEmpty() }
        for (section in Section.entries) {
            val rows = bySection[section].orEmpty()
            if (rows.isEmpty()) continue
            out += Item("head:${section.name}", Kind.HEADING, "${section.label} · ${rows.size}", section = section)
            if (section == Section.NOT_CACHED) out += Item("note:${section.name}", Kind.NOTE, NOT_ON_RD_NOTE, section = section)
            val shown = when {
                section in opened -> rows.size
                section.startsOpen || section == first -> minOf(FIRST_ROWS, rows.size)
                else -> 0
            }
            for (s in rows.take(shown)) out += row(s, section)
            val left = rows.size - shown
            if (left > 0) {
                val words = if (shown == 0) "Show $left" else "Show $left more"
                out += Item("more:${section.name}", Kind.SHOW_MORE, words, section = section)
            }
        }
        return out
    }

    /**
     * Why [best] is the copy Play uses, in plain words: what it is (quality, the HDR this TV shows of it, what it was
     * made from, cached or played here), then what decided it and what it beat, from the engine's [SourceList.why]:
     * "4K · HDR10+ · Remux · cached · ahead of the IMAX copy (no HDR10+)". When even the best likely won't play well
     * here, its warning comes before that. A list without the engine's why (made by hand) says it as before: "Lighter
     * 4K: your connection keeps up with it" when a heavier copy was passed over for this connection.
     */
    fun reason(best: PlaybackSource, list: SourceList): String {
        val why = list.why?.takeIf { it.bestKey == best.key } ?: return plainReason(best, list)
        val film = film(list)
        val state = when {
            best.fit == TvFit.MEASURED_OK -> "played here"
            best.readiness == Readiness.PROBABLY_READY -> "cached"
            else -> null
        }
        val warning = best.warning?.takeIf { best.fit == TvFit.LIKELY_NOT }?.let { "⚠ $it" }
        val hdr = PlayRules.hdrWords(best.facts.copy(hdr = why.hdr))
        val beaten = why.beatenKey?.let { k -> list.sources.firstOrNull { it.key == k } }
        val ahead = beaten?.let { b -> "ahead of ${nameOf(b, best, film)}" + (why.lacks?.let { " ($it)" } ?: "") }
        return listOfNotNull(PlayRules.quality(best, film), hdr, PlayRules.kind(best.facts.origin), state, warning, ahead).joinToString(" · ")
    }

    /** A copy beaten, by what tells it from the Best: "the IMAX copy", "the 1080p Remux", "the 81.4 GB Remux". */
    private fun nameOf(b: PlaybackSource, best: PlaybackSource, film: Boolean): String {
        if (PlayRules.imax(b, film) && !PlayRules.imax(best, film)) return "the IMAX copy"
        val what = if (b.facts.height != best.facts.height) PlayRules.resolution(b.facts.height) else b.facts.sizeBytes?.let { PlayRules.size(it) }
        return "the " + listOfNotNull(what, PlayRules.kind(b.facts.origin) ?: "copy").joinToString(" ")
    }

    private fun plainReason(best: PlaybackSource, list: SourceList): String {
        val quality = PlayRules.quality(best)
        val height = best.facts.height ?: 0
        val lighter = quality != null && best.facts.avgMbps != null && !slowLine(best) &&
            list.sources.any { it.key != best.key && slowLine(it) && (it.facts.height ?: 0) >= height }
        if (lighter) return "Lighter $quality: your connection keeps up with it"
        val state = when {
            best.fit == TvFit.MEASURED_OK -> "played here"
            best.readiness == Readiness.PROBABLY_READY -> "cached"
            else -> null
        }
        val warning = best.warning?.takeIf { best.fit == TvFit.LIKELY_NOT }?.let { "⚠ $it" }
        val hdr = PlayRules.hdrWords(SourceRank.asPlayedHere(best.facts, best.measured))
        return listOfNotNull(quality, hdr, PlayRules.kind(best.facts.origin), state, warning).joinToString(" · ")
    }

    /** Set aside because this connection was measured too slow for it. */
    private fun slowLine(s: PlaybackSource): Boolean = s.fit == TvFit.LIKELY_NOT && s.warning?.contains(SLOW_LINE_WORDS) == true

    /** Whether OK on [s] first asks before the debrid service fetches it ([Readiness.NOT_READY]). */
    fun asksFirst(s: PlaybackSource): Boolean = s.readiness == Readiness.NOT_READY

    /**
     * Details for a source (no links): its facts, the add-ons' own words per add-on, and how ready it is. Lines, the
     * first a heading-like summary.
     */
    fun details(s: PlaybackSource, day: (Long) -> String): List<Pair<String, String>> {
        val f = s.facts
        val out = ArrayList<Pair<String, String>>()
        out += "Picture" to (listOfNotNull(PlayRules.resolution(f.height), f.hdr.joinToString(" ") { it.label }.ifEmpty { null }, f.codec,
            f.bitDepth?.let { "$it-bit" }).joinToString(" · ").ifEmpty { "Not stated" })
        out += "Sound" to (listOfNotNull(f.audio, f.channels).joinToString(" ").ifEmpty { "Not stated" })
        f.sizeBytes?.let { out += "Size" to PlayRules.size(it) }
        f.avgMbps?.let { out += "Data rate" to "≈${it.toInt()} Mb/s (average, from size)" }
        f.origin?.let { out += "Source" to it }
        f.group?.let { out += "Release group" to it }
        // Its tracks' once it played here, else the add-ons' (as the row's second line says them).
        PlayRules.languageCodes(s).takeIf { it.isNotEmpty() }?.let { codes ->
            out += "Audio languages" to codes.joinToString(", ") { if (it == CopyLanguage.MULTI) "Multi" else it.uppercase(Locale.ROOT) }
        }
        f.seeders?.let { out += "Seeders" to "$it" }
        out += "Debrid" to when (s.readiness) {
            Readiness.PROBABLY_READY -> "Probably on your debrid service (the add-on's guess)"
            Readiness.NOT_READY -> "Not on your debrid service yet: it has to download it first"
            Readiness.UNKNOWN -> "Not said"
        }
        out += "Offered by" to PlayRules.addonsOf(s) + if (s.copies > 1) " · ×${s.copies} copies" else ""
        out += "Facts" to PlayRules.factsFrom(s, day)
        s.warning?.let { out += "Warning" to it }
        for (o in s.offers) out += "${o.addon.label} says" to listOfNotNull(o.text, o.filename).distinct().joinToString("\n")
        return out
    }
}
