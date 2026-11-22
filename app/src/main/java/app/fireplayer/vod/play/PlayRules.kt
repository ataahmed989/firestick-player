package app.fireplayer.vod.play

import app.fireplayer.player.CopyLanguage
import app.fireplayer.vod.Addon
import app.fireplayer.vod.AddonState
import app.fireplayer.vod.Episode
import app.fireplayer.vod.EpisodeRef
import app.fireplayer.vod.Hdr
import app.fireplayer.vod.LastPlayed
import app.fireplayer.vod.LookupWhy
import app.fireplayer.vod.PlaybackSource
import app.fireplayer.vod.Position
import app.fireplayer.vod.Readiness
import app.fireplayer.vod.SourceFacts
import app.fireplayer.vod.SourceList
import app.fireplayer.vod.StartAt
import app.fireplayer.vod.UpNext
import app.fireplayer.vod.sources.SourceRank
import java.util.Locale

/**
 * The words and small decisions of playing a film or an episode from the add-ons (design-brief.md §6.4, 6.5): what the
 * waiting strip and the Play pill say while the add-ons answer, the title page's source line, the Sources button's
 * count, a source's two lines in the Sources panel, where playback starts and which episode is next. Pure, so the
 * pages and the player only show what these say (unit-tested).
 *
 * Never a link here: only what the add-ons wrote about a file ([PlaybackSource.facts], [app.fireplayer.vod.Offer.text]).
 */
object PlayRules {

    /** The title page's look-up: 0.5 s with a resume point, or opened from Next Episodes or a Continue card's menu. */
    fun pageLookup(hasResumePoint: Boolean, soon: Boolean): LookupWhy =
        if (hasResumePoint || soon) LookupWhy.PAGE_SOON else LookupWhy.PAGE

    /** Where playback starts: a resumable [position] (FirePlayer 1.0's rule), else the beginning; never a question. */
    fun startAt(position: Position?, fromBeginning: Boolean): StartAt =
        if (!fromBeginning && position != null && position.resumable) StartAt.At(position.positionMs) else StartAt.Beginning

    /** A film's start from its Continue Watching record. */
    fun startAt(last: LastPlayed?): StartAt = if (last != null && last.resumable) StartAt.At(last.position.positionMs) else StartAt.Beginning

    // ---- Waiting for the add-ons (stage 1) -------------------------------------------------------------------------

    /** One add-on's part of the strip: "Torrentio ✓ 33", "MediaFusion still looking 9 s of 25"; null when not set. */
    fun stateText(addon: Addon, state: AddonState, now: Long): String? = when (state) {
        AddonState.NotSet -> null
        AddonState.NotAsked -> "${addon.label} still looking"
        is AddonState.Asking -> {
            val gone = ((now - state.since).coerceAtLeast(0) / 1000)
            val limit = ((state.deadline - state.since).coerceAtLeast(0) + 999) / 1000
            "${addon.label} still looking $gone s of $limit"
        }
        is AddonState.Answered -> "${addon.label} ✓ ${state.count}"
        is AddonState.Failed -> "${addon.label} didn't answer"
        is AddonState.Busy -> "${addon.label} busy"
    }

    /**
     * The strip under the header while Play waits: each add-on's state, then what the keys do ("OK: play best so far"
     * only once there is one).
     */
    fun strip(list: SourceList, now: Long): String {
        val parts = Addon.entries.mapNotNull { a -> list.states[a]?.let { stateText(a, it, now) } }.toMutableList()
        if (list.best != null) parts += "OK: play best so far"
        parts += "Back: cancel"
        return parts.joinToString(" · ")
    }

    /** The Play pill while waiting: "Waiting for MediaFusion · 9 s", "Waiting for the add-ons · 4 s". */
    fun waitingLabel(list: SourceList, now: Long, pressedAt: Long): String {
        val still = list.stillLooking
        val who = when (still.size) {
            0 -> "sources"
            1 -> still[0].label
            else -> "the add-ons"
        }
        val secs = ((now - pressedAt).coerceAtLeast(0) / 1000)
        return "Waiting for $who · $secs s"
    }

    /** The Play pill's thin bar while waiting: how far toward the last add-on's answer limit, 0..1. */
    fun waitFraction(list: SourceList, now: Long): Float {
        val asking = list.states.values.filterIsInstance<AddonState.Asking>()
        if (asking.isEmpty()) return 1f
        val since = asking.minOf { it.since }
        val deadline = asking.maxOf { it.deadline }
        if (deadline <= since) return 1f
        return ((now - since).toFloat() / (deadline - since)).coerceIn(0f, 1f)
    }

    /**
     * Whether the wait is over though the list isn't [SourceList.complete]: every add-on still asking is [GRACE_MS] past
     * its answer limit. The engine marks those as failed itself; this keeps a stuck answer from holding Play forever.
     */
    fun waitedOut(list: SourceList, now: Long): Boolean {
        if (list.complete) return true
        return list.states.values.all { s ->
            s.final || (s is AddonState.Asking && now > s.deadline + GRACE_MS)
        }
    }

    /** What OK plays after the wait: the best automatic pick, or (none ready) any source, which the player asks about. */
    fun playable(list: SourceList): Boolean = list.best != null || list.sources.isNotEmpty()

    // ---- The title page's source line and Sources button -----------------------------------------------------------

    /**
     * The source line once an add-on has answered: "Best for this TV · 4K IMAX · DV · Remux · 58.3 GB · from Torrentio"
     * (the copy's [mainLine]; "Best so far" while the other still looks; "Last used" when that release is offered again,
     * never an SD one: [SourceRank.withoutSd]). "No sources found" when every add-on answered with none; null before any
     * answer (the line stays empty).
     */
    fun sourceLine(list: SourceList?): String? {
        if (list == null) return null
        val answered = list.states.values.any { it is AddonState.Answered }
        if (!answered) {
            if (list.complete && list.states.values.all { it == AddonState.NotSet }) return NO_ADDONS_LINE
            if (list.complete) return "The add-ons didn't answer · Sources to try again"
            return null
        }
        val pick = list.lastUsed ?: list.best
        if (pick == null) {
            return if (list.sources.isEmpty()) {
                if (list.complete) "No sources found" else null
            } else "None ready on your debrid service · ${list.sources.size} to fetch in Sources"
        }
        val lead = when {
            list.lastUsed != null -> "Last used"
            list.complete -> "Best for this TV"
            else -> "Best so far"
        }
        return listOf(lead, mainLine(pick), "from ${addonsOf(pick)}").filter { it.isNotEmpty() }.joinToString(" · ")
    }

    /** The Sources button: "Sources", then "Sources · 40" once an add-on has answered. */
    fun sourcesLabel(list: SourceList?): String {
        val answered = list?.states?.values?.any { it is AddonState.Answered } == true
        return if (answered) "Sources · ${list!!.sources.size}" else "Sources"
    }

    // ---- A source, as the Sources panel and the player show it ------------------------------------------------------

    /** "4K", "1080p", "720p"…; null when the add-ons didn't say. */
    fun resolution(height: Int?): String? = when {
        height == null -> null
        height >= 2000 -> "4K"
        else -> "${height}p"
    }

    /**
     * A copy in full on one line, for the player's notes and questions ("Switch to …?"): resolution, HDR kinds, origin,
     * size and average rate, pack ("4K · DV · HDR10+ · WEB-DL · 29.3 GB · ≈25 Mb/s"). The Sources panel's rows and the
     * title page's source line use the shorter [mainLine] and [detailLine].
     */
    fun badges(s: PlaybackSource): String {
        val f = s.facts
        val hdr = Hdr.entries.filter { it in f.hdr }.map { h ->
            if (h == Hdr.DOLBY_VISION && f.dvProfile != null) "DV${f.dvProfile}" else h.label
        }
        return listOfNotNull(
            resolution(f.height),
            *hdr.toTypedArray(),
            f.origin,
            f.sizeBytes?.let { size(it) },
            f.avgMbps?.let { "≈${it.toInt()} Mb/s" },
            if (f.pack) "Pack" else null,
            if (s.copies > 1) "×${s.copies}" else null,
        ).joinToString(" · ")
    }

    // ---- A Sources row's two lines (and the title page's source line) ----------------------------------------------

    /** A 2160p copy whose name or file name says IMAX, IMX or Open Matte ([SourceRank.isImax]): the "4K IMAX" ones. */
    fun imax(s: PlaybackSource): Boolean =
        (s.facts.height ?: 0) >= 2160 && s.offers.any { SourceRank.isImax(it.text) || SourceRank.isImax(it.filename) }

    /** "4K IMAX", "4K", "1080p", "720p"…; null when the add-ons didn't say. */
    fun quality(s: PlaybackSource): String? = resolution(s.facts.height)?.let { if (imax(s)) "$it IMAX" else it }

    /**
     * The HDR kind, short: "DV", "DV · HDR10+" (what a TV without Dolby Vision plays of it), "HDR10+", "HDR10", "HLG";
     * null for SDR. With [one], the first kind only.
     */
    fun hdrKind(f: SourceFacts, one: Boolean = false): String? = when {
        Hdr.DOLBY_VISION in f.hdr -> if (Hdr.HDR10_PLUS in f.hdr && !one) "DV · HDR10+" else "DV"
        Hdr.HDR10_PLUS in f.hdr -> "HDR10+"
        Hdr.HDR10 in f.hdr -> "HDR10"
        Hdr.HLG in f.hdr -> "HLG"
        else -> null
    }

    /** The HDR kind in plain words, for the Best row's reason: "Dolby Vision", "HDR10+", "HDR10", "HLG"; null for SDR. */
    fun hdrWords(f: SourceFacts): String? = if (Hdr.DOLBY_VISION in f.hdr) "Dolby Vision" else hdrKind(f)

    /** What a copy was made from: "Remux", "BluRay", "WEB" (WEB-DL, WEBRip), else the add-on's word ("HDTV", "CAM"). */
    fun kind(origin: String?): String? = when (origin) {
        null -> null
        "REMUX" -> "Remux"
        "WEB-DL", "WEBRip", "WEB" -> "WEB"
        else -> origin
    }

    /**
     * A Sources row's main line, and the title page's: quality · HDR kind · what it was made from · size ("4K IMAX · DV ·
     * HDR10+ · Remux · 58.3 GB"). Over [chars] characters (what the row has room for, so it's never cut inside a word),
     * the HDR kind keeps its first part.
     */
    fun mainLine(s: PlaybackSource, chars: Int = Int.MAX_VALUE): String {
        val f = s.facts
        fun line(one: Boolean) = listOfNotNull(quality(s), hdrKind(f, one), kind(f.origin), f.sizeBytes?.let { size(it) }).joinToString(" · ")
        return line(one = false).takeIf { it.length <= chars } ?: line(one = true)
    }

    /**
     * A Sources row's quieter second line: the one warning, codec, sound, audio languages, pack, seeders (copies not
     * cached yet) and the add-on ("HEVC 10-bit · TrueHD Atmos 7.1 · EN + FR · Torrentio"). Over [chars] characters (its
     * two lines), the languages first shorten to the first and a count ("EN + 4 more"), then the codec, pack and sound
     * go, the languages last; the warning, seeders and add-on stay.
     */
    fun detailLine(s: PlaybackSource, chars: Int = Int.MAX_VALUE): String {
        val f = s.facts
        val codec = listOfNotNull(f.codec, f.bitDepth?.takeIf { it > 8 }?.let { "$it-bit" }).joinToString(" ").ifEmpty { null }
        val sound = listOfNotNull(f.audio, f.channels).joinToString(" ").ifEmpty { null }
        val seeds = if (s.readiness == Readiness.NOT_READY) f.seeders?.let { "$it seeders" } else null
        // Each part, and how long it stays when the line is too long (KEEP: always).
        val parts = listOf(
            s.warning?.let { "⚠ $it" } to KEEP,
            codec to 1,
            sound to 3,
            audioLanguages(s) to LANGS,
            (if (f.pack) "Pack" else null) to 2,
            seeds to KEEP,
            addonsOf(s).ifEmpty { null } to KEEP,
        ).mapNotNull { (text, stays) -> text?.let { it to stays } }.toMutableList()
        fun joined() = parts.joinToString(" · ") { it.first }
        val short = audioLanguages(s, short = true)
        while (joined().length > chars) {
            val at = parts.indexOfFirst { it.second == LANGS }
            if (at >= 0 && short != null && parts[at].first != short) {
                parts[at] = short to LANGS
                continue
            }
            val drop = parts.filter { it.second < KEEP }.minByOrNull { it.second } ?: break
            parts.remove(drop)
        }
        return joined()
    }

    /**
     * A copy's sound languages, short, for its row: what FirePlayer measured of its tracks once it played here, else
     * what the add-ons say ([languageCodes]). "EN", "EN + FR", "EN + FR + DE + 2 more"; "Multi" when an add-on says only
     * that there are several, "EN + more" when it names just one of them. With [short], three or more come as the first
     * and a count ("EN + 4 more"). Null when nothing is known.
     */
    fun audioLanguages(s: PlaybackSource, short: Boolean = false): String? {
        val codes = languageCodes(s)
        val named = codes.filter { it != CopyLanguage.MULTI }.map { it.uppercase(Locale.ROOT) }
        val multi = named.size < 2 && CopyLanguage.MULTI in codes
        return when {
            named.isEmpty() -> if (multi) MULTI_WORD else null
            multi -> "${named[0]} + more"
            short && named.size > 2 -> "${named[0]} + ${named.size - 1} more"
            named.size > LANGUAGES -> named.take(LANGUAGES).joinToString(" + ") + " + ${named.size - LANGUAGES} more"
            else -> named.joinToString(" + ")
        }
    }

    /**
     * A copy's sound languages as codes ("en", "fr", or [CopyLanguage.MULTI]): its tracks' once FirePlayer has played it
     * ([PlaybackSource.measured]), else the add-ons' ([SourceFacts.languages]); empty when neither says.
     */
    fun languageCodes(s: PlaybackSource): List<String> {
        val measured = s.measured?.spec?.audioLangs.orEmpty().split(',').mapNotNull { CopyLanguage.code(it) }.distinct()
        return measured.ifEmpty { s.facts.languages }
    }

    /** Languages a row names before "+ N more". */
    private const val LANGUAGES = 3
    /** How long the languages stay on a long line: after the codec, pack and sound. */
    private const val LANGS = 4
    private const val KEEP = Int.MAX_VALUE
    private const val MULTI_WORD = "Multi"

    /** "Torrentio", "MediaFusion", or "Both add-ons". */
    fun addonsOf(s: PlaybackSource): String = when {
        s.addons.size == 2 -> "Both add-ons"
        s.addons.size > 2 -> "All ${s.addons.size} add-ons"
        else -> s.addons.firstOrNull()?.label.orEmpty()
    }

    /** Where a source's facts came from: "from Torrentio" until FirePlayer measured it ("✓ played Nov 12"). */
    fun factsFrom(s: PlaybackSource, day: (Long) -> String): String {
        val at = s.measured?.at
        return if (at != null) "✓ played ${day(at)}" else "from ${addonsOf(s)}"
    }

    /** "29.3 GB", "870 MB". */
    fun size(bytes: Long): String {
        val gb = bytes / 1_000_000_000.0
        return if (gb >= 1.0) String.format(Locale.ROOT, "%.1f GB", gb) else "${(bytes / 1_000_000L).coerceAtLeast(1)} MB"
    }

    /** "1:02:03" or "42:10", for "Resumed near 1:02:03". */
    fun clock(ms: Long): String {
        val s = (ms.coerceAtLeast(0) / 1000)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec) else String.format(Locale.ROOT, "%d:%02d", m, sec)
    }

    // ---- After a switch, and the next episode --------------------------------------------------------------------

    /** A switch starts this much before where the old source was (§6.5, "5 s early"). */
    const val SWITCH_BACK_MS = 5_000L

    /** Where a switched-to source starts: 5 s before [positionMs], never below 0. */
    fun switchStart(positionMs: Long): Long = (positionMs - SWITCH_BACK_MS).coerceAtLeast(0L)

    /** The next episode's sources are looked up in this last part of an episode (§6.5, "the last ~4 minutes"). */
    const val NEXT_LOOKUP_MS = 4 * 60_000L

    /**
     * The player's next episode (the end card, Next episode): the aired regular episode after [current] in [episodes]
     * (any order), across seasons, in [UpNext.later]'s order, so a season's last episode goes on to the next season's
     * first, as Continue Watching does. In order, watched or not (a show watched again goes on in order); specials never,
     * and none after a special. Null when nothing after it has aired by [now].
     */
    fun nextEpisode(episodes: List<Episode>, current: EpisodeRef, now: Long): Episode? {
        if (current.season <= 0) return null
        return UpNext.later(episodes, current, now).firstOrNull()
    }

    /** Seconds past an answer limit before the title page stops waiting on its own ([waitedOut]). */
    const val GRACE_MS = 2_000L

    const val NO_ADDONS_LINE = "No add-ons yet · add Torrentio or MediaFusion in Settings › Add-ons"
}
