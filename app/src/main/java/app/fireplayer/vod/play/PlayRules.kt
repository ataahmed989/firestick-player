package app.fireplayer.vod.play

import app.fireplayer.player.CopyLanguage
import app.fireplayer.player.MediaSpec
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
import app.fireplayer.vod.SourceGroup
import app.fireplayer.vod.SourceList
import app.fireplayer.vod.SoundFormat
import app.fireplayer.vod.StartAt
import app.fireplayer.vod.TvFit
import app.fireplayer.vod.TvPlan
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
            list.lastUsed != null && list.lastUsed.key != list.best?.key -> "Last used"
            list.complete -> "Best for this TV"
            else -> "Best so far"
        }
        return listOf(lead, mainLine(pick, film = list.target.episode == null, plan = list.plan), "from ${addonsOf(pick)}")
            .filter { it.isNotEmpty() }.joinToString(" · ")
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
     * title page's source line use the shorter [mainLine] and [noteLine].
     */
    fun badges(s: PlaybackSource): String {
        val f = SourceRank.asPlayedHere(s.facts, s.measured)
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

    /**
     * A 2160p IMAX copy of a [film] ([SourceRank.isImaxCopy]: its name says IMAX or IMX, or the viewer moved it there):
     * the "4K IMAX" ones.
     */
    fun imax(s: PlaybackSource, film: Boolean = true): Boolean = (s.facts.height ?: 0) >= 2160 && SourceRank.isImaxCopy(s, film)

    /** "4K IMAX", "4K", "1080p 3D", "1080p", "720p"…; null when the add-ons didn't say. */
    fun quality(s: PlaybackSource, film: Boolean = true): String? = resolution(s.facts.height)?.let {
        when {
            imax(s, film) -> "$it IMAX"
            s.group == SourceGroup.THREE_D -> "$it 3D"
            else -> it
        }
    }

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

    /**
     * What this TV shows of a copy's HDR ([SourceRank.shownHdr] of the copy as played here), one word: "HDR10+",
     * "HDR10", "HLG"; "DV" only on a Dolby Vision TV, for a Dolby Vision file this TV can't show (its row warns), or
     * for a profile 8 file whose base layer isn't known until it plays ([SourceRank.dv8Unmeasured]: the add-on's word);
     * null for SDR. Without a [plan] (a list not ranked), the add-ons' words ([hdrKind]).
     */
    fun tvHdr(s: PlaybackSource, plan: TvPlan?): String? {
        val f = SourceRank.asPlayedHere(s.facts, s.measured)
        if (plan == null) return hdrKind(f)
        if (SourceRank.dv8Unmeasured(f, plan)) return "DV"
        val shown = SourceRank.shownHdr(f, plan)
        return when {
            Hdr.DOLBY_VISION in shown -> "DV"
            Hdr.HDR10_PLUS in shown -> "HDR10+"
            Hdr.HDR10 in shown -> "HDR10"
            Hdr.HLG in shown -> "HLG"
            else -> null
        }
    }

    /**
     * What a copy was made from, in plain words: "Remux", "Encode" (a Blu-ray re-encoded), "WEB" (WEB-DL, WEBRip), else
     * the add-on's word ("HDTV", "DVD", "CAM").
     */
    fun kind(origin: String?): String? = when (origin) {
        null -> null
        "REMUX" -> "Remux"
        "BluRay" -> "Encode"
        "WEB-DL", "WEBRip", "WEB" -> "WEB"
        else -> origin
    }

    /**
     * A Sources row's first line, and the title page's: quality · the HDR this TV shows ([tvHdr]) · what it was made
     * from · size ("4K · HDR10+ · Remux · 81.4 GB"). Without a [plan], the add-ons' HDR words, which over [chars]
     * characters (what the row has room for, so it's never cut inside a word) keep their first part.
     */
    fun mainLine(s: PlaybackSource, chars: Int = Int.MAX_VALUE, film: Boolean = true, plan: TvPlan? = null): String {
        val f = SourceRank.asPlayedHere(s.facts, s.measured)
        fun line(hdr: String?) = listOfNotNull(quality(s, film), hdr, kind(f.origin), f.sizeBytes?.let { size(it) }).joinToString(" · ")
        if (plan != null) return line(tvHdr(s, plan))
        return line(hdrKind(f)).takeIf { it.length <= chars } ?: line(hdrKind(f, one = true))
    }

    /**
     * A Sources row's quieter second line: only what may change the choice. The one real warning ([warning]), the
     * language that matters ([languageWord]), the one sound word for what's heard ([soundWord]), seeders on a copy not
     * cached yet, and the add-ons offering it ("English · 7.1 · Torrentio + MediaFusion"). [lead] comes after the warning
     * (the Best's: what decided it). Over [chars] characters (its two lines), the sound goes first, then the language
     * (then, after a [lead], the add-ons); the warning, the lead and seeders stay.
     */
    fun noteLine(s: PlaybackSource, plan: TvPlan?, wanted: List<String>, chars: Int = Int.MAX_VALUE, lead: List<String> = emptyList()): String {
        val seeds = if (s.readiness == Readiness.NOT_READY) s.facts.seeders?.let { "$it seeders" } else null
        val parts = (listOf(warning(s)?.let { "⚠ $it" } to KEEP) + lead.map { it to KEEP } + listOf(
            languageWord(s, wanted) to 2,
            soundWord(s, plan) to 1,
            seeds to KEEP,
            addonsOf(s).ifEmpty { null } to if (lead.isEmpty()) KEEP else 3,
        )).mapNotNull { (text, stays) -> text?.let { it to stays } }.toMutableList()
        fun joined() = parts.joinToString(" · ") { it.first }
        while (joined().length > chars) {
            val drop = parts.filter { it.second < KEEP }.minByOrNull { it.second } ?: break
            parts.remove(drop)
        }
        return joined()
    }

    /**
     * A copy's warning worth a row: the engine's ([PlaybackSource.warning]) but "High data rate", which every remux has
     * (Details keeps it); "More than this TV shows" for a copy set aside only for that. Null when there's none.
     */
    fun warning(s: PlaybackSource): String? = when {
        s.warning == SourceRank.HIGH_RATE -> null
        s.warning != null -> s.warning
        s.fit == TvFit.LIKELY_NOT -> MORE_THAN_TV
        else -> null
    }

    /**
     * The sound language that matters, by name: the film's own language ([wanted] first, when it isn't the viewer's) and
     * the viewer's ("Japanese + English", "Japanese", "English"; the film's own with subtitles in the viewer's:
     * "Japanese, English subtitles"); "No English" for a copy known to have neither. Its tracks once it played here,
     * else the add-ons' words; null when nothing is known, or only that it has several.
     */
    fun languageWord(s: PlaybackSource, wanted: List<String>): String? {
        val codes = languageCodes(s)
        val known = codes.filter { it != CopyLanguage.MULTI }
        if (known.isEmpty()) return null
        val mine = wanted.lastOrNull() ?: DEFAULT_LANGUAGE
        val own = wanted.firstOrNull()?.takeIf { wanted.size > 1 }
        val ownName = own?.takeIf { it in known }?.let { CopyLanguage.name(it) }
        val hasMine = mine in known
        return when {
            ownName != null && hasMine -> "$ownName + ${CopyLanguage.name(mine)}"
            ownName != null && subtitled(s, mine) -> "$ownName, ${CopyLanguage.name(mine)} subtitles"
            ownName != null -> ownName
            hasMine -> CopyLanguage.name(mine)
            // The add-ons say there are several, maybe more than those named.
            CopyLanguage.MULTI in codes -> null
            else -> "No ${CopyLanguage.name(mine)}"
        }
    }

    /** Whether a copy has subtitles in [language] inside it: its tracks once it played here, else its name says so. */
    private fun subtitled(s: PlaybackSource, language: String): Boolean =
        language in s.facts.subs || CopyLanguage.hasSubs(s.measured?.spec?.subLangs.orEmpty(), language)

    /**
     * The one sound word for what's heard on this TV: "Atmos" when the TV or receiver takes the Atmos as it is ([plan]'s
     * sound: TrueHD for TrueHD Atmos, Dolby Atmos (DD+) for DD+ Atmos), else the channels ("7.1", "5.1", "Stereo"): what
     * the Stick decodes keeps them, not the Atmos. What played here, else the add-ons' words; null when neither says.
     */
    fun soundWord(s: PlaybackSource, plan: TvPlan?): String? {
        val spec = s.measured?.spec?.takeIf { it.audioCodec != null }
        val codec = spec?.audioCodec ?: s.facts.audio?.removeSuffix(" Atmos")
        val atmos = spec?.atmos ?: (s.facts.audio?.contains(ATMOS) == true)
        if (atmos) {
            val through = plan == null ||
                (codec == "TrueHD" && SoundFormat.TRUEHD in plan.sound) ||
                (codec != "TrueHD" && SoundFormat.EAC3_JOC in plan.sound)
            if (through) return ATMOS
        }
        val channels = spec?.channels?.takeIf { it > 0 } ?: s.facts.channels?.let { c ->
            val main = c.substringBefore('.').toIntOrNull() ?: return@let null
            main + (c.substringAfter('.', "").toIntOrNull() ?: 0)
        }
        return channels?.let { MediaSpec.channelsLabel(it) }
    }

    /**
     * A copy's sound languages as codes ("en", "fr", or [CopyLanguage.MULTI]): its tracks' once FirePlayer has played it
     * ([PlaybackSource.measured]), else the add-ons' ([SourceFacts.languages]); empty when neither says.
     */
    fun languageCodes(s: PlaybackSource): List<String> {
        val measured = s.measured?.spec?.audioLangs.orEmpty().split(',').mapNotNull { CopyLanguage.code(it) }.distinct()
        return measured.ifEmpty { s.facts.languages }
    }

    private const val KEEP = Int.MAX_VALUE
    const val ATMOS = "Atmos"
    const val MORE_THAN_TV = "More than this TV shows"
    /** The viewer's language when a list doesn't say ([SourceList.wanted] empty: a list not ranked). */
    private const val DEFAULT_LANGUAGE = "en"

    /** The add-ons offering a copy, merged: "Torrentio", "Torrentio + MediaFusion", or "3 add-ons". */
    fun addonsOf(s: PlaybackSource): String = when {
        s.addons.size > 2 -> "${s.addons.size} add-ons"
        else -> s.addons.joinToString(" + ") { it.label }
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
