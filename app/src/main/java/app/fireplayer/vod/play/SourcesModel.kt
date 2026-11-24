package app.fireplayer.vod.play

import app.fireplayer.player.CopyLanguage
import app.fireplayer.player.MediaSpec
import app.fireplayer.vod.Addon
import app.fireplayer.vod.Attempt
import app.fireplayer.vod.BestWhy
import app.fireplayer.vod.Hdr
import app.fireplayer.vod.PlaybackSource
import app.fireplayer.vod.Readiness
import app.fireplayer.vod.SourceGroup
import app.fireplayer.vod.SourceList
import app.fireplayer.vod.TvFit
import app.fireplayer.vod.TvPlan
import app.fireplayer.vod.sources.SourceRank

/**
 * What the Sources window lists, the same from a title page and from the player (design-brief.md §6.5, the user's
 * layout of 2026-11), pure. One note (the add-ons' states). Then, in the player, the copy playing, marked "Best" when it
 * is ("Best so far" while an add-on still looks). Then Best for this TV, with what decided it and what it beat; or, when
 * the Best is the copy playing, what Next source plays. Then the copies by kind, each kind's best first ("Best for this
 * TV" order): 4K IMAX, 4K, 1080p, 720p, 3D, Resolution not stated, Cinema recordings, Not cached yet and, only when the
 * title has nothing at 720p or above ([SourceRank.withoutSd]), Lower quality. A heading shows only with rows, with
 * their count. 4K IMAX, 4K, 1080p and Lower quality start open with 5 rows and "Show N more", as does the first kind
 * listed; the others start closed with one "Show N" row, and so do 1080p and the kinds under it while a 4K copy plays
 * smoothly. A row's first line is what it is on this TV ([PlayRules.mainLine]), its second only what may change the
 * choice ([PlayRules.noteLine]); Details (Right) has the rest ([details]). Every item has a stable [Item.key], so rows
 * keep focus as answers arrive. [view] is the same list as a model.
 */
object SourcesModel {

    enum class Kind { NOTE, HEADING, SOURCE, SHOW_MORE }

    /** The window's kinds of copy, in order; one [startsOpen] false stays closed until "Show N". */
    enum class Section(val label: String, val startsOpen: Boolean) {
        /** 2160p IMAX copies of a film ([PlayRules.imax]). */
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

    /** Rows an open kind shows before "Show N more". */
    const val FIRST_ROWS = 5

    const val BEST_LABEL = "Best for this TV"
    const val BEST_SO_FAR = "Best so far"
    /** The mark of the Best's own rows (and of the copy playing, when it is the Best). */
    const val BEST = "Best"
    const val PLAYING = "Playing"
    const val NEXT_SOURCE = "Next source"
    const val LAST_USED = "Last used"

    /** The top rows' keys: the same rows while better copies arrive, so focus stays on them. */
    const val PLAYING_KEY = "src:playing"
    const val BEST_KEY = "src:best"
    const val NEXT_KEY = "src:next"

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

    /**
     * A kind of the Sources window: its copies in "Best for this TV" order, so its best ([best]) first; [open] when it
     * starts open.
     */
    class Group(val section: Section, val rows: List<PlaybackSource>, val open: Boolean = section.startsOpen) {
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
     * failed); [smooth]: the copy playing plays without stalling, so 1080p and the kinds under it start closed when it
     * is a 4K one. Next source is the next copy down the list from the one playing (or the Best), its own kind first
     * ([SourceRank.nextOrder]).
     */
    fun view(list: SourceList, playingKey: String? = null, attempts: List<Attempt> = emptyList(), smooth: Boolean = false): View {
        val film = film(list)
        val playing = list.sources.firstOrNull { it.key == playingKey }
        val best = list.best
        val playingIsBest = playing != null && playing.key == best?.key
        val failed = attempts.filter { it.failure != null }.mapTo(HashSet()) { it.source.key }
        val next = SourceRank.nextOrder(list.sources, (playing ?: best)?.key, film).firstOrNull { it.key !in failed }
        val fine = smooth && playing != null && sectionOf(playing, film).let { it == Section.IMAX || it == Section.UHD }
        val bySection = list.sources.groupBy { sectionOf(it, film) }
        val groups = Section.entries.mapNotNull { section ->
            bySection[section]?.takeIf { it.isNotEmpty() }?.let { Group(section, it, section.startsOpen && !(fine && section >= Section.FHD)) }
        }
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
     * The window's entries for [list] (null: nothing asked yet). [opened] are kinds the viewer opened (a closed one's
     * "Show N", or "Show N more" on an open one, which then lists all). [attempts] are this play's tries and
     * [playingKey] the copy playing (the player's window), [smooth] whether it plays without stalling ([view]); [time]
     * prints a try's time ("20:41"), [day] a played date. [lineChars] and [noteChars] are what a row's two lines have
     * room for ([PlayRules.mainLine], [PlayRules.noteLine]).
     */
    fun items(
        list: SourceList?,
        now: Long,
        opened: Set<Section>,
        attempts: List<Attempt> = emptyList(),
        playingKey: String? = null,
        smooth: Boolean = false,
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
        val v = view(list, playingKey, attempts, smooth)
        val film = film(list)
        val bestKey = list.best?.key
        val lastKey = list.lastUsed?.key
        val tried = attempts.associateBy({ it.source.key }, { it })

        fun line(s: PlaybackSource) = PlayRules.mainLine(s, lineChars, film, list.plan).ifEmpty { s.offers.firstOrNull()?.text.orEmpty() }
        fun note(s: PlaybackSource) = PlayRules.noteLine(s, list.plan, list.wanted, noteChars).ifEmpty { null }

        // A row's small marks, two lines at most: what this play did with it, then Best and Last used, then whether
        // FirePlayer has played it on this TV. The rows at the top say only what their heading doesn't.
        fun marks(s: PlaybackSource, top: Boolean): String? {
            val a = tried[s.key]
            val state = when {
                top -> emptyList()
                s.key == playingKey -> listOf(PLAYING)
                a != null -> listOfNotNull("Tried ${time(a.at)}", if (a.failure != null) "didn't play" else null)
                else -> emptyList()
            }
            val lines = state + listOfNotNull(
                if (!top && s.key == bestKey) BEST else null,
                if (s.key == lastKey && s.key != playingKey) LAST_USED else null,
                s.measured?.at?.let { "✓ played ${day(it)}" },
            )
            return lines.take(2).joinToString("\n").ifEmpty { null }
        }

        // The copy playing (the player's), and whether it is the Best: a mark of its own.
        v.playing?.let { p ->
            out += Item("head:playing", Kind.HEADING, PLAYING)
            val mark = if (v.playingIsBest) (if (v.soFar) BEST_SO_FAR else BEST) else null
            out += Item(PLAYING_KEY, Kind.SOURCE, line(p), if (v.playingIsBest) bestNote(p, list, noteChars) else note(p), mark, p, chosen = true)
        }
        // Best for this TV, with what decided it; when it is the copy playing, what Next source plays instead.
        val best = v.best
        val next = v.next
        if (best != null) {
            out += Item("head:best", Kind.HEADING, if (v.soFar) BEST_SO_FAR else BEST_LABEL)
            out += Item(BEST_KEY, Kind.SOURCE, line(best), bestNote(best, list, noteChars), marks(best, top = true), best)
        } else if (v.playingIsBest && next != null) {
            out += Item("head:next", Kind.HEADING, NEXT_SOURCE)
            out += Item(NEXT_KEY, Kind.SOURCE, line(next), note(next), marks(next, top = true), next)
        }
        // The first kind listed is open even when its kind starts closed (a film with nothing above 720p).
        val first = v.groups.firstOrNull { it.section != Section.CINEMA && it.section != Section.NOT_CACHED }?.section
        for (g in v.groups) {
            val section = g.section
            out += Item("head:${section.name}", Kind.HEADING, "${section.label} · ${g.rows.size}", section = section)
            val shown = when {
                section in opened -> g.rows.size
                g.open || section == first -> minOf(FIRST_ROWS, g.rows.size)
                else -> 0
            }
            for (s in g.rows.take(shown)) {
                out += Item("src:${section.name}:${s.key}", Kind.SOURCE, line(s), note(s), marks(s, top = false), s, section,
                    chosen = s.key == playingKey)
            }
            val left = g.rows.size - shown
            if (left > 0) {
                val words = if (shown == 0) "Show $left" else "Show $left more"
                out += Item("more:${section.name}", Kind.SHOW_MORE, words, section = section)
            }
        }
        return out
    }

    /**
     * The Best's second line: what decided it and what it beat ([decided]: "Cached · ahead of the IMAX copy (no
     * HDR10+)"), then its row's own words as room allows ([PlayRules.noteLine]).
     */
    private fun bestNote(best: PlaybackSource, list: SourceList, chars: Int): String? =
        PlayRules.noteLine(best, list.plan, list.wanted, chars, decided(best, list))
            .replaceFirstChar { it.uppercaseChar() }.ifEmpty { null }

    /**
     * Why [best] is the copy Play uses, in plain words: what it is (quality, the HDR this TV shows of it, what it was
     * made from), then [decided]: "4K · HDR10+ · Remux · cached · ahead of the IMAX copy (no HDR10+)". When even the
     * best likely won't play well here, its warning comes before what it beat. A list without the engine's why (made by
     * hand) says "Lighter 4K: your connection keeps up with it" when a heavier copy was passed over for this connection.
     */
    fun reason(best: PlaybackSource, list: SourceList): String {
        val why = why(best, list)
        val quality = PlayRules.quality(best, film(list))
        if (why == null && lighter(best, list)) return "Lighter $quality: your connection keeps up with it"
        val hdr = PlayRules.hdrWords(if (why != null) best.facts.copy(hdr = why.hdr) else SourceRank.asPlayedHere(best.facts, best.measured))
        val warning = best.warning?.takeIf { best.fit == TvFit.LIKELY_NOT }?.let { "⚠ $it" }
        return listOfNotNull(quality, hdr, PlayRules.kind(best.facts.origin), stateOf(best), warning, ahead(best, list, why)).joinToString(" · ")
    }

    /**
     * What decided the Best, after what it is: cached or played here, and what it beat ("cached", "ahead of the IMAX copy
     * (no HDR10+)"); "Lighter 4K: your connection keeps up with it" for a list without the engine's why. Empty when
     * nothing more is known.
     */
    fun decided(best: PlaybackSource, list: SourceList): List<String> {
        val why = why(best, list)
        if (why == null && lighter(best, list)) return listOf("Lighter ${PlayRules.quality(best, film(list))}: your connection keeps up with it")
        return listOfNotNull(stateOf(best), ahead(best, list, why))
    }

    private fun why(best: PlaybackSource, list: SourceList): BestWhy? = list.why?.takeIf { it.bestKey == best.key }

    private fun stateOf(best: PlaybackSource): String? = when {
        best.fit == TvFit.MEASURED_OK -> "played here"
        best.readiness == Readiness.PROBABLY_READY -> "cached"
        else -> null
    }

    /** "ahead of the IMAX copy (no HDR10+)": the copy the engine's [why] says the Best beat, and what that one lacks. */
    private fun ahead(best: PlaybackSource, list: SourceList, why: BestWhy?): String? {
        if (why == null) return null
        val beaten = why.beatenKey?.let { k -> list.sources.firstOrNull { it.key == k } } ?: return null
        return "ahead of ${nameOf(beaten, best, film(list))}" + (why.lacks?.let { " ($it)" } ?: "")
    }

    /** A heavier copy of at least its height was set aside for this connection, and the Best wasn't. */
    private fun lighter(best: PlaybackSource, list: SourceList): Boolean {
        val height = best.facts.height ?: 0
        return PlayRules.quality(best) != null && best.facts.avgMbps != null && !slowLine(best) &&
            list.sources.any { it.key != best.key && slowLine(it) && (it.facts.height ?: 0) >= height }
    }

    /** A copy beaten, by what tells it from the Best: "the IMAX copy", "the 1080p Remux", "the 81.4 GB Remux". */
    private fun nameOf(b: PlaybackSource, best: PlaybackSource, film: Boolean): String {
        if (PlayRules.imax(b, film) && !PlayRules.imax(best, film)) return "the IMAX copy"
        val what = if (b.facts.height != best.facts.height) PlayRules.resolution(b.facts.height) else b.facts.sizeBytes?.let { PlayRules.size(it) }
        return "the " + listOfNotNull(what, PlayRules.kind(b.facts.origin) ?: "copy").joinToString(" ")
    }

    /** Set aside because this connection was measured too slow for it. */
    private fun slowLine(s: PlaybackSource): Boolean = s.fit == TvFit.LIKELY_NOT && s.warning?.contains(SLOW_LINE_WORDS) == true

    /** Whether OK on [s] first asks before the debrid service fetches it ([Readiness.NOT_READY]). */
    fun asksFirst(s: PlaybackSource): Boolean = s.readiness == Readiness.NOT_READY

    /**
     * Details for a source (Right on its row; no links): what its row leaves out. Its picture as the file says and as
     * this TV shows it ([plan]), its Dolby Vision profile, sound and what's heard of it, size and data rates (the
     * picture's as measured here), the server's speed while it plays ([server], the player's), every sound and subtitle
     * language, seeders, how ready it is, the add-ons and each one's own words. Lines, the first a heading-like summary.
     */
    fun details(s: PlaybackSource, plan: TvPlan? = null, server: String? = null, day: (Long) -> String): List<Pair<String, String>> {
        val f = s.facts
        val spec = s.measured?.spec
        val out = ArrayList<Pair<String, String>>()
        out += "Picture" to (listOfNotNull(PlayRules.resolution(f.height), Hdr.entries.filter { it in f.hdr }.joinToString(" ") { it.label }.ifEmpty { null },
            f.codec, f.bitDepth?.let { "$it-bit" }).joinToString(" · ").ifEmpty { "Not stated" })
        if (plan != null) PlayRules.tvHdr(s, plan)?.let { out += "This TV shows" to it }
        (SourceRank.asPlayedHere(f, s.measured).dvProfile ?: f.dvProfile ?: spec?.dvProfile?.takeIf { it > 0 })?.let { p ->
            val how = when {
                spec?.dvBaseLayer?.let { it > 0 } == true -> " · played here as its HDR10 base layer"
                spec?.decoder == MediaSpec.Decoder.PLAIN -> " · played here as HDR10"
                spec?.decoder == MediaSpec.Decoder.DOLBY_VISION -> " · played here as Dolby Vision"
                else -> ""
            }
            out += "Dolby Vision" to "Profile $p$how"
        }
        val sound = listOfNotNull(f.audio, f.channels).joinToString(" ").ifEmpty { null }
        val heard = PlayRules.soundWord(s, plan)
        val atmosLost = f.audio?.contains(PlayRules.ATMOS) == true && heard != PlayRules.ATMOS && plan != null
        out += "Sound" to ((sound ?: "Not stated") + if (atmosLost) " · heard here as ${heard ?: "sound"}: Atmos not kept on this TV" else "")
        f.sizeBytes?.let { out += "Size" to PlayRules.size(it) }
        f.avgMbps?.let { out += "Data rate" to "≈${it.toInt()} Mb/s average (from size)" }
        spec?.takeIf { it.videoHow == MediaSpec.How.MEASURED && it.videoBps > 0 }?.let { out += "Picture data" to "${MediaSpec.mbps(it.videoBps)} · measured here" }
        server?.let { out += "Server" to it }
        f.origin?.let { out += "Source" to it }
        f.group?.let { out += "Release group" to it }
        // Its tracks' once it played here, else the add-ons' (by name: every one, not only those its row names).
        PlayRules.languageCodes(s).takeIf { it.isNotEmpty() }?.let { codes ->
            out += "Audio languages" to codes.joinToString(", ") { if (it == CopyLanguage.MULTI) "Multi" else CopyLanguage.name(it) }
        }
        val subs = spec?.subLangs.orEmpty().split(',').mapNotNull { CopyLanguage.code(it) }.ifEmpty { f.subs }.distinct()
        if (subs.isNotEmpty()) out += "Subtitles" to subs.joinToString(", ") { CopyLanguage.name(it) }
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
