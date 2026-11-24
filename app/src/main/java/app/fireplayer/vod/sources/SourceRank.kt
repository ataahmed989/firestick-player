package app.fireplayer.vod.sources

import app.fireplayer.player.CopyLanguage
import app.fireplayer.player.CopyRank
import app.fireplayer.player.ImaxAspect
import app.fireplayer.player.MediaSpec
import app.fireplayer.vod.BestWhy
import app.fireplayer.vod.Hdr
import app.fireplayer.vod.Measured
import app.fireplayer.vod.PlaybackSource
import app.fireplayer.vod.RankStep
import app.fireplayer.vod.Readiness
import app.fireplayer.vod.SoundFormat
import app.fireplayer.vod.SourceFacts
import app.fireplayer.vod.SourceGroup
import app.fireplayer.vod.TvFit
import app.fireplayer.vod.TvPlan
import kotlin.math.floor
import kotlin.math.ln

/** What "Best for this TV" weighs a list against. */
class RankInput(
    val plan: TvPlan,
    /** [CopyLanguage.wanted]: the film's own language first, then the viewer's (one when they're the same or unknown). */
    val wanted: List<String>,
    /** What FirePlayer measured of a release, by [app.fireplayer.vod.ReleaseKey.storeKey]; null when never played. */
    val measured: (String) -> Measured?,
    val runtimeMin: Int?,
    val episode: Boolean,
    /** This TV's connection, steady ([LineRate.current]), in Mb/s; null when nothing was measured lately. */
    val lineMbps: Double? = null,
    /** Settings › This TV › IMAX first: always ([app.fireplayer.vod.ThisTv.imaxAlways]). */
    val imaxAlways: Boolean = false,
    /** The viewer's subtitle language ("en"): a copy known to carry it gets a small plus; null for none. */
    val subtitles: String? = null,
    /** Now, epoch ms: "Buffered here before" stops counting [SourceRank.TROUBLE_KEEP_MS] after; 0 never. */
    val now: Long = 0L,
)

/**
 * "Best for this TV" (r3_sources-organize §4.4, the user's order of 2026-11), the same order in every group, each step
 * ([RankStep]) only breaking ties of the ones before:
 *
 * 1. Plays here: likely, or played here (the same: a short test play doesn't decide Best), then unknown, then "likely
 *    not" ([fit]: showed no picture here, buffered here lately, Dolby Vision this TV can't show, over the data limit,
 *    faster than the connection, taller than the TV).
 * 2. Cached (played here smoothly, or the debrid service probably has it), then unknown, then not cached.
 * 3. Not known to lack both the film's own language and the viewer's.
 * 4. Room on the connection ([LineRate.room], steady figure; only once one was measured).
 * 5. Picture data: bits per pixel with codec efficiency, measured video rate once played, 4K counting up to [FULL_4K]
 *    (about 40 Mb/s), copies within 15 % equal ([CopyRank.CLOSE]), HDR this TV prefers given its edge.
 * 6. Height. 7. The HDR this TV shows ([shownHdr]: Dolby Vision by what this TV does with each profile).
 * 8. Source kind (Remux, BluRay encode, WEB-DL, WEBRip, HDTV), then bit depth (10-bit taken for HDR and 4K HEVC).
 * 9. HDR10+ where it reaches the TV ([TvPlan.hdr10PlusReaches]; Dolby Vision on a Dolby Vision TV); a picture that
 *    played here without HDR10's brightness data last.
 * 10. IMAX (films only: the name says IMAX, or a play measured 1.90:1, [ImaxAspect]); first of all with
 *    [RankInput.imaxAlways].
 * 11. The film's own language just above the viewer's dub ([language]), subtitles in the viewer's language a little
 *    more; of equal quality the best of each sit side by side ([pairLanguages]).
 * 12. Sound as heard: capped at what the TV or receiver takes as it is ([sound]). 13. Played smoothly here.
 * 14. Size. 15. Seeders. Then the add-ons' order.
 *
 * Pure.
 */
object SourceRank {

    /** With no connection measured, above this average a row says "High data rate". */
    const val HIGH_MBPS = 60.0

    /** For ranking a 4K copy, data counts up to this many bits per pixel (about 40 Mb/s of HEVC), not [CopyRank.FULL]. */
    const val FULL_4K = 0.20

    /** "Buffered here before" stops counting this long after the copy last played here... */
    const val TROUBLE_KEEP_MS = 7L * 24 * 60 * 60 * 1000

    /** ...or once the connection brings this many times its average (a stall-free evening for it). */
    const val TROUBLE_LINE = 1.33

    const val DV7_WORDS = "Dolby Vision 7: may not play here"
    const val NEEDS_DV = "Needs a Dolby Vision TV"
    const val BUFFERED = "Buffered here before"
    const val NO_PICTURE = "Showed no picture here"
    const val CLOSE_TO_LINE = "Close to your connection"
    const val HIGH_RATE = "High data rate"

    /** A film's or an episode's length when the add-ons' size is known but the runtime isn't (ranking only, never shown). */
    private const val FILM_MIN = 120
    private const val EPISODE_MIN = 45

    /** Ranks [sources] best first, without SD copies when the title has better ([withoutSd]). */
    fun rank(sources: List<PlaybackSource>, input: RankInput): List<PlaybackSource> {
        val scored = withoutSd(sources).mapIndexed { i, s ->
            val measured = measuredOf(s, input)
            val (fit, warning) = fit(asPlayedHere(s.facts, measured), input.plan, measured, input.lineMbps, input.now)
            val ranked = s.copy(fit = fit, warning = warning, measured = measured)
            Scored(ranked, i, keyOf(ranked, input))
        }
        return pairLanguages(scored.sortedWith(ORDER), input).map { it.source }
    }

    /** What was measured of a row's file: its own release's, else a merged offer's (the same file from another add-on). */
    private fun measuredOf(s: PlaybackSource, input: RankInput): Measured? {
        s.release?.let { r -> input.measured(r.storeKey)?.let { return it } }
        for (o in s.offers) o.release?.let { r -> if (r != s.release) input.measured(r.storeKey)?.let { return it } }
        return null
    }

    /** Below this height a copy is SD (480p, 576p): the user's "480p should not appear". */
    const val HD_HEIGHT = 720

    /** Whether [s] is SD: its stated height is under [HD_HEIGHT] (an unstated height isn't). */
    fun isSd(s: PlaybackSource): Boolean = s.facts.height?.let { it < HD_HEIGHT } == true

    /**
     * The SD rule: SD copies are left out (not listed, never the best or Last used, never played by itself) whenever
     * the film or episode has any copy at 720p or above that isn't a cinema recording, cached or not. Only when it has
     * none do they stay (the Sources panel's "Lower quality").
     */
    fun withoutSd(sources: List<PlaybackSource>): List<PlaybackSource> {
        val hd = sources.any { (it.facts.height ?: 0) >= HD_HEIGHT && it.group != SourceGroup.CINEMA }
        return if (hd) sources.filterNot { isSd(it) } else sources
    }

    /** The automatic pick: the first ranked source that may be picked by itself. */
    fun best(ranked: List<PlaybackSource>): PlaybackSource? = ranked.firstOrNull { it.automatic }

    /** One value per [RankStep], higher first; [dub]: the viewer's language without the film's own. */
    private class Key(val v: LongArray, val dub: Boolean)

    private class Scored(val source: PlaybackSource, val index: Int, val key: Key)

    private val STEPS = RankStep.entries

    private val ORDER = Comparator<Scored> { a, b ->
        for (i in STEPS.indices) {
            val c = b.key.v[i].compareTo(a.key.v[i])
            if (c != 0) return@Comparator c
        }
        a.index.compareTo(b.index)
    }

    /** The first step [a] and [b] differ in, from [from] on; null when they're equal there. */
    private fun stepBetween(a: Key, b: Key, from: Int = 0, until: Int = STEPS.size): RankStep? {
        for (i in from until until) if (a.v[i] != b.v[i]) return STEPS[i]
        return null
    }

    private fun keyOf(s: PlaybackSource, input: RankInput): Key {
        val f = asPlayedHere(s.facts, s.measured)
        val spec = s.measured?.spec
        val plan = input.plan
        val playedWell = spec != null && !troubled(s.measured, f.avgMbps, input.lineMbps, input.now)
        val hdr = hdrRank(f, plan)
        val known = knownLanguages(f.languages, spec?.audioLangs)
        val level = language(f.languages, spec?.audioLangs, input.wanted)
        val subs = input.subtitles?.let { CopyLanguage.code(it) }?.let { want ->
            want in f.subs || CopyLanguage.hasSubs(spec?.subLangs.orEmpty(), want)
        } == true
        val v = LongArray(STEPS.size)
        v[RankStep.IMAX_ALWAYS.ordinal] = if (input.imaxAlways && imaxFirst(s, !input.episode)) 1 else 0
        v[RankStep.PLAYS.ordinal] = when (s.fit) {
            TvFit.MEASURED_OK, TvFit.LIKELY -> 2
            TvFit.UNKNOWN -> 1
            TvFit.LIKELY_NOT -> 0
        }
        v[RankStep.CACHED.ordinal] = when {
            playedWell || s.readiness == Readiness.PROBABLY_READY -> 2
            s.readiness == Readiness.UNKNOWN -> 1
            else -> 0
        }
        v[RankStep.LANGUAGE_KNOWN.ordinal] = if (level == 0) 0 else 1
        v[RankStep.ROOM.ordinal] = if (LineRate.room(f.avgMbps, input.lineMbps, f.origin == REMUX)) 1 else 0
        v[RankStep.PICTURE.ordinal] = pictureBucket(f, spec, input, hdr).toLong()
        v[RankStep.HEIGHT.ordinal] = (f.height ?: 0).toLong()
        v[RankStep.HDR.ordinal] = hdr.toLong()
        v[RankStep.KIND.ordinal] = originRank(f.origin) * 100L + bitDepth(f)
        v[RankStep.DYNAMIC_HDR.ordinal] = dynamicHdr(shownHdr(f, plan), spec, plan).toLong()
        v[RankStep.IMAX.ordinal] = if (isImaxCopy(s, !input.episode)) 1 else 0
        v[RankStep.LANGUAGE.ordinal] = level * 2L + if (subs) 1 else 0
        v[RankStep.SOUND.ordinal] = sound(f, plan).toLong()
        v[RankStep.SMOOTH.ordinal] = if (playedWell) 1 else 0
        v[RankStep.SIZE.ordinal] = f.sizeBytes ?: 0L
        v[RankStep.SEEDERS.ordinal] = (f.seeders ?: 0).toLong()
        val dub = input.wanted.size > 1 && input.wanted[1] in known && input.wanted[0] !in known
        return Key(v, dub)
    }

    /**
     * Of equal quality (every step before [RankStep.LANGUAGE] equal), the best copy in the film's own language and the
     * best one dubbed in the viewer's sit side by side, the film's own first; the others keep their order.
     */
    private fun pairLanguages(sorted: List<Scored>, input: RankInput): List<Scored> {
        if (input.wanted.size < 2) return sorted
        val out = ArrayList(sorted)
        val lang = RankStep.LANGUAGE.ordinal
        var start = 0
        while (start < out.size) {
            var end = start + 1
            while (end < out.size && stepBetween(out[start].key, out[end].key, until = lang) == null) end++
            val own = (start until end).firstOrNull { out[it].key.v[lang] >= 4 }
            val dub = (start until end).firstOrNull { out[it].key.dub }
            if (own != null && dub != null && dub > own + 1) out.add(own + 1, out.removeAt(dub))
            start = end
        }
        return out
    }

    // ---- Why the Best is first ----

    /** The steps that are the picture: a copy better in these lost on an earlier one. */
    private val QUALITY = RankStep.PICTURE.ordinal..RankStep.IMAX.ordinal

    /**
     * What put [ranked]'s Best first, and what it beat: a copy with a better picture that an earlier step put after it
     * (not cached, no room on the connection…), else the first copy after it that lost on picture, HDR, source, IMAX or
     * language. Copies that differ from it only in sound, size or seeders aren't named. Null without a Best.
     */
    fun why(ranked: List<PlaybackSource>, input: RankInput): BestWhy? {
        val best = best(ranked) ?: return null
        val at = ranked.indexOf(best)
        val keys = ranked.map { keyOf(it, input) }
        val mine = keys[at]
        val shown = shownHdr(asPlayedHere(best.facts, best.measured), input.plan)
        var rival = -1
        // Never a cinema recording, nor a copy whose resolution isn't stated: its picture data has no frame to be judged by.
        for (i in ranked.indices) {
            if (i == at || ranked[i].group == SourceGroup.CINEMA || ranked[i].fit == TvFit.UNKNOWN) continue
            if (better(keys[i], mine) && (rival < 0 || better(keys[i], keys[rival]))) rival = i
        }
        if (rival < 0) {
            for (i in at + 1 until ranked.size) {
                if (ranked[i].group == SourceGroup.CINEMA) continue
                val step = stepBetween(mine, keys[i]) ?: continue
                if (step.ordinal in RankStep.PICTURE.ordinal..RankStep.LANGUAGE.ordinal) {
                    rival = i
                    break
                }
            }
        }
        if (rival < 0) return BestWhy(best.key, shown)
        val step = stepBetween(mine, keys[rival]) ?: return BestWhy(best.key, shown)
        return BestWhy(best.key, shown, ranked[rival].key, step, lacks(step, ranked[rival], keys[rival], input))
    }

    /** Whether [a]'s picture steps beat [b]'s. */
    private fun better(a: Key, b: Key): Boolean {
        for (i in QUALITY) if (a.v[i] != b.v[i]) return a.v[i] > b.v[i]
        return false
    }

    /** What the copy beaten at [step] lacks, in plain words ("no HDR10+", "not cached yet"). */
    private fun lacks(step: RankStep, b: PlaybackSource, key: Key, input: RankInput): String? {
        val f = asPlayedHere(b.facts, b.measured)
        return when (step) {
            RankStep.IMAX_ALWAYS, RankStep.IMAX -> "not IMAX"
            RankStep.PLAYS -> b.warning ?: if (b.fit == TvFit.UNKNOWN) "resolution not stated" else "more than this TV shows"
            RankStep.CACHED -> if (b.readiness == Readiness.NOT_READY) "not cached yet" else "may not be cached"
            RankStep.LANGUAGE_KNOWN -> "no " + input.wanted.joinToString(" or ") { CopyLanguage.name(it) } + " sound"
            RankStep.ROOM -> {
                val avg = f.avgMbps?.let { Math.round(it) }
                val line = input.lineMbps?.let { Math.round(it) }
                if (avg != null && line != null) "≈$avg Mb/s needs more room than your ≈$line Mb/s connection"
                else "needs more room on your connection"
            }
            RankStep.PICTURE -> "less picture data"
            RankStep.HEIGHT -> "lower resolution"
            RankStep.HDR -> {
                val shown = shownHdr(f, input.plan)
                when {
                    shown.isEmpty() -> "no HDR"
                    Hdr.DOLBY_VISION in shown && input.plan.hdrOrder.none { Hdr.DOLBY_VISION in it } -> "Dolby Vision this TV can't show"
                    Hdr.HLG in shown && Hdr.HDR10 !in shown && Hdr.HDR10_PLUS !in shown -> "HLG"
                    else -> "HDR this TV ranks lower"
                }
            }
            RankStep.KIND -> when (f.origin) {
                null -> "source not stated"
                REMUX -> "lower bit depth"
                "BluRay", "DVD" -> "re-encoded"
                "WEB-DL", "WEB", "WEBRip" -> "a streaming copy"
                "HDTV" -> "a TV recording"
                else -> f.origin
            }
            RankStep.DYNAMIC_HDR -> when {
                key.v[RankStep.DYNAMIC_HDR.ordinal] == 0L -> "no HDR brightness data"
                input.plan.hdrOrder.any { Hdr.DOLBY_VISION in it } -> "no Dolby Vision"
                else -> "no HDR10+"
            }
            RankStep.LANGUAGE -> {
                val level = key.v[RankStep.LANGUAGE.ordinal] / 2
                if (level < 2 && input.wanted.isNotEmpty()) "not in ${CopyLanguage.name(input.wanted[0])}"
                else input.subtitles?.let { "no ${CopyLanguage.name(it)} subtitles" }
            }
            else -> null
        }
    }

    // ---- Plays here ----

    /**
     * Whether [facts] likely play well under [plan], and at most one warning for the row. [lineMbps] is this
     * connection's steady figure ([LineRate.current]; null: nothing measured), [now] for how old a stall is.
     */
    fun fit(facts: SourceFacts, plan: TvPlan, measured: Measured?, lineMbps: Double? = null, now: Long = 0L): Pair<TvFit, String?> {
        val avg = facts.avgMbps
        val cap = plan.maxMbps
        val dv = dvProblem(facts, plan)
        return when {
            // Its picture never showed on this Stick ([app.fireplayer.player.CopyHealth.noPicture]): never picked by itself.
            (measured?.health?.noPicture ?: 0) > 0 -> TvFit.LIKELY_NOT to NO_PICTURE
            troubled(measured, avg, lineMbps, now) -> TvFit.LIKELY_NOT to BUFFERED
            dv != null -> TvFit.LIKELY_NOT to dv
            cap != null && avg != null && avg > cap -> TvFit.LIKELY_NOT to "Over your $cap Mb/s limit"
            // Measured on this TV, not guessed: what this connection keeps bringing.
            LineRate.tooFast(avg, lineMbps) ->
                TvFit.LIKELY_NOT to "Needs ${Math.round(avg ?: 0.0)} Mb/s · this connection brought ${Math.round(lineMbps ?: 0.0)}"
            facts.height != null && facts.height > plan.topHeight -> TvFit.LIKELY_NOT to null
            measured?.spec != null -> TvFit.MEASURED_OK to rate(facts, lineMbps)
            facts.height == null -> TvFit.UNKNOWN to rate(facts, lineMbps)
            else -> TvFit.LIKELY to rate(facts, lineMbps)
        }
    }

    /** With a connection measured: "Close to your connection" when it leaves no room; without, the old 60 Mb/s note. */
    private fun rate(f: SourceFacts, lineMbps: Double?): String? {
        val avg = f.avgMbps ?: return null
        if (lineMbps != null && lineMbps > 0.0) return if (LineRate.room(avg, lineMbps, f.origin == REMUX)) null else CLOSE_TO_LINE
        return if (avg > HIGH_MBPS) HIGH_RATE else null
    }

    /**
     * Whether a copy counts as one that buffered or dropped frames here ([app.fireplayer.player.CopyHealth.troubled]):
     * not once it last played [TROUBLE_KEEP_MS] ago or more, nor, for buffering, once the connection ([lineMbps])
     * brings [TROUBLE_LINE] times its average. A record with no date is judged by the connection only.
     */
    fun troubled(measured: Measured?, avgMbps: Double?, lineMbps: Double?, now: Long): Boolean {
        val h = measured?.health ?: return false
        if (!h.troubled) return false
        if (now > 0 && h.at > 0 && now - h.at >= TROUBLE_KEEP_MS) return false
        if (!h.choppy && avgMbps != null && lineMbps != null && lineMbps >= avgMbps * TROUBLE_LINE) return false
        return true
    }

    /** Why this TV can't show a Dolby Vision copy as it is ([DV7_WORDS], [NEEDS_DV]); null when it can, or it isn't one. */
    private fun dvProblem(f: SourceFacts, plan: TvPlan): String? {
        if (Hdr.DOLBY_VISION !in f.hdr) return null
        val tvDv = plan.hdrOrder.any { Hdr.DOLBY_VISION in it }
        if (f.dvProfile == 7) return if (plan.dv7AsHdr10 == false && !tvDv) DV7_WORDS else null
        if (tvDv) return null
        val base = Hdr.HDR10 in f.hdr || Hdr.HDR10_PLUS in f.hdr
        return if (f.dvProfile == 5 || (f.dvProfile == null && !base)) NEEDS_DV else null
    }

    /**
     * [facts] as this TV played the file, by what FirePlayer [measured] when it played here: a Dolby Vision file played
     * on a plain decoder (as its HDR10 base layer, or Media3's own way back to HEVC) is HDR10 here, not Dolby Vision; its
     * profile is filled in from the play; HDR10+ seen in the picture is added. Otherwise [facts] as the add-ons have them.
     */
    fun asPlayedHere(facts: SourceFacts, measured: Measured?): SourceFacts {
        val spec = measured?.spec ?: return facts
        var f = facts
        if (Hdr.DOLBY_VISION in f.hdr) {
            val plain = spec.dvBaseLayer > 0 || (spec.decoder == MediaSpec.Decoder.PLAIN && spec.dvProfile != 5)
            val profile = spec.dvProfile.takeIf { it > 0 }
            f = when {
                plain -> f.copy(hdr = f.hdr - Hdr.DOLBY_VISION + Hdr.HDR10, dvProfile = null)
                profile != null && f.dvProfile == null -> f.copy(dvProfile = profile)
                else -> f
            }
        }
        if (spec.hdr10Plus && Hdr.HDR10_PLUS !in f.hdr) f = f.copy(hdr = f.hdr + Hdr.HDR10_PLUS)
        return f
    }

    /**
     * The HDR this TV shows of [f] (as played here, [asPlayedHere]). Dolby Vision by what this TV does with each
     * profile: profile 7 is the HDR10 (or HDR10+) picture inside it when this Stick plays its base layer
     * ([TvPlan.dv7AsHdr10], on any TV: its decoders don't take profile 7); on a TV without Dolby Vision, profile 8, or a
     * file that also names HDR10 or HDR10+, is that picture; profile 5 and a Dolby Vision file naming nothing else stay
     * Dolby Vision (which such a TV can't show). On a Dolby Vision TV, Dolby Vision as it is. On an HDR TV without
     * HDR10+, HDR10+ is HDR10.
     */
    fun shownHdr(f: SourceFacts, plan: TvPlan): Set<Hdr> {
        val kinds = dolbyVisionShown(f, plan)
        val noPlus = plan.hdrOrder.isNotEmpty() && plan.hdrOrder.none { Hdr.HDR10_PLUS in it }
        return if (noPlus && Hdr.HDR10_PLUS in kinds) kinds - Hdr.HDR10_PLUS + Hdr.HDR10 else kinds
    }

    private fun dolbyVisionShown(f: SourceFacts, plan: TvPlan): Set<Hdr> {
        if (Hdr.DOLBY_VISION !in f.hdr) return f.hdr
        val base = f.hdr - Hdr.DOLBY_VISION + Hdr.HDR10
        if (f.dvProfile == 7 && plan.dv7AsHdr10 != false) return base
        if (plan.hdrOrder.any { Hdr.DOLBY_VISION in it }) return f.hdr
        return when {
            f.dvProfile == 8 -> base
            f.dvProfile == null && (Hdr.HDR10 in f.hdr || Hdr.HDR10_PLUS in f.hdr) -> base
            else -> f.hdr
        }
    }

    /** How well the HDR this TV shows of [facts] ([shownHdr]) suits the plan: the plan's first tier highest. On a TV showing no HDR, SDR first. */
    fun hdrRank(facts: SourceFacts, plan: TvPlan): Int {
        val tiers = plan.hdrOrder
        if (tiers.isEmpty()) return if (facts.hdr.isEmpty()) 2 else 1
        if (facts.hdr.isEmpty()) return 1
        val kinds = shownHdr(facts, plan)
        val i = tiers.indexOfFirst { tier -> tier.any { it in kinds } }
        return if (i < 0) 0 else tiers.size - i + 1
    }

    /**
     * The HDR10+ step: 2 for the dynamic HDR this TV takes (HDR10+ on a TV that reports it without Dolby Vision, when
     * it reaches the TV: [TvPlan.hdr10PlusReaches]; Dolby Vision on a Dolby Vision TV), 0 for a picture that played
     * here without HDR10's brightness data, 1 otherwise.
     */
    private fun dynamicHdr(shown: Set<Hdr>, spec: MediaSpec?, plan: TvPlan): Int {
        if (shown.isEmpty()) return 1
        val top = when {
            plan.hdrOrder.any { Hdr.DOLBY_VISION in it } -> Hdr.DOLBY_VISION in shown
            plan.hdrOrder.any { Hdr.HDR10_PLUS in it } -> plan.hdr10PlusReaches && Hdr.HDR10_PLUS in shown
            else -> false
        }
        return when {
            top -> 2
            spec?.hdrInfo == false -> 0
            else -> 1
        }
    }

    // ---- IMAX ----

    /** IMAX or IMX as a word ("Film.2023.IMAX.2160p", "[IMX]"), any case. Open matte isn't: it is mostly a 16:9 picture. */
    private val IMAX = Regex("""(?<![a-z0-9])(imax|imx)(?![a-z0-9])""", RegexOption.IGNORE_CASE)

    /** Whether a release's name or file name ([text]) says IMAX or IMX. */
    fun isImax(text: String?): Boolean = text != null && IMAX.containsMatchIn(text)

    /**
     * Whether [s] is an IMAX copy of a [film] (never a show's episode): its name says IMAX (trusted), or a play here
     * measured its picture steadily at 1.90:1 ([ImaxAspect]). Other copies of the film aren't moved by that measure.
     */
    fun isImaxCopy(s: PlaybackSource, film: Boolean): Boolean {
        if (!film) return false
        if (s.offers.any { isImax(it.text) || isImax(it.filename) }) return true
        return ImaxAspect.isImax(s.measured?.spec?.aspect ?: 0f)
    }

    /**
     * IMAX first: always (the user's earlier wish, now a setting): a 2160p IMAX copy of a [film] ranks above every other
     * source, only when this TV plays it by the usual rules ([fit] measured or likely, never "likely not"). [s] has its
     * [fit] set.
     */
    fun imaxFirst(s: PlaybackSource, film: Boolean = true): Boolean {
        if ((s.facts.height ?: 0) < 2160) return false
        if (s.fit != TvFit.MEASURED_OK && s.fit != TvFit.LIKELY) return false
        return isImaxCopy(s, film)
    }

    // ---- Picture, source, sound, language ----

    /** The picture's data for its frame, in 15 % steps ([CopyRank.CLOSE]): close figures tie and the next keys decide. */
    private fun pictureBucket(f: SourceFacts, spec: MediaSpec?, input: RankInput, hdr: Int): Int {
        val played = spec?.takeIf { it.videoHow == MediaSpec.How.MEASURED && it.videoBps > 0 }?.videoBps
        val bps = played ?: bitsPerSecond(f, input) ?: return 0
        val height = f.height ?: 0
        val codec = f.codec ?: if (height >= 2160) "HEVC" else null
        val frame = MediaSpec(videoCodec = codec, width = widthOf(height), height = height, bitDepth = f.bitDepth ?: 0, overallBps = bps)
        var score = bps * (CopyRank.EFFICIENCY[codec] ?: 1.0)
        val full = if (height >= 2160) FULL_4K else CopyRank.FULL
        CopyRank.perPixel(frame, bps)?.let { each -> if (each > full) score = score * full / each }
        if (CopyRank.starved(frame, bits = bps)) score *= CopyRank.FED_SHARE
        // HDR the TV prefers wins when it has about as much data (CopyRank.HDR_FLOOR); on an SDR TV, SDR does.
        val top = if (input.plan.hdrOrder.isEmpty()) 2 else input.plan.hdrOrder.size + 1
        score *= when {
            hdr >= top -> 1 / CopyRank.HDR_FLOOR
            hdr >= 2 -> 1 / 0.85
            hdr == 0 -> 0.85
            else -> 1.0
        }
        if (score <= 1.0) return 0
        return floor(ln(score) / ln(1 + CopyRank.CLOSE)).toInt()
    }

    /** The average data rate, from size and runtime; with no runtime, an assumed length (ranking only). */
    private fun bitsPerSecond(f: SourceFacts, input: RankInput): Long? {
        f.avgMbps?.let { return (it * 1_000_000).toLong() }
        val size = f.sizeBytes ?: return null
        val minutes = input.runtimeMin ?: if (input.episode) EPISODE_MIN else FILM_MIN
        return (size * 8.0 / (minutes * 60.0)).toLong()
    }

    fun widthOf(height: Int): Int = when {
        height >= 2160 -> 3840
        height >= 1440 -> 2560
        height >= 1080 -> 1920
        height >= 720 -> 1280
        height > 0 -> height * 16 / 9
        else -> 0
    }

    private const val REMUX = "REMUX"

    private fun originRank(origin: String?): Int = when (origin) {
        REMUX -> 6
        "BluRay" -> 5
        "WEB-DL", "WEB" -> 4
        "WEBRip" -> 3
        "HDTV" -> 2
        "DVD" -> 1
        else -> 0
    }

    /** The bit depth stated, at least 10 for HDR and for 4K HEVC (they are 10-bit), so a "10bit" name doesn't win. */
    private fun bitDepth(f: SourceFacts): Int {
        val hevc4k = (f.height ?: 0) >= 2160 && (f.codec == null || f.codec == "HEVC")
        return maxOf(f.bitDepth ?: 0, if (f.hdr.isNotEmpty() || hevc4k) 10 else 0)
    }

    /**
     * Sound as heard ([CopyRank.soundRank]): no better than the best the TV or receiver takes as it is (lossless only
     * with TrueHD or DTS-HD passed through, DD+ Atmos with Atmos passed through; else the usual lossy: what's decoded on
     * the Stick loses the rest), a little more for a format it takes as it is, then channels.
     */
    private fun sound(f: SourceFacts, plan: TvPlan): Int {
        val audio = f.audio ?: return 0
        val atmos = audio.contains("Atmos")
        val codec = audio.removeSuffix(" Atmos").takeUnless { it == "Atmos" }
        val base = CopyRank.soundRank(MediaSpec(audioCodec = codec ?: if (atmos) "DD+" else null, atmos = atmos))
        val format = when (codec) {
            "TrueHD" -> SoundFormat.TRUEHD
            "DTS-HD MA", "DTS:X", "DTS-HD" -> SoundFormat.DTS_HD
            "DTS" -> SoundFormat.DTS
            "DD+" -> if (atmos) SoundFormat.EAC3_JOC else SoundFormat.EAC3
            "DD" -> SoundFormat.AC3
            else -> null
        }
        val cap = when {
            SoundFormat.TRUEHD in plan.sound || SoundFormat.DTS_HD in plan.sound -> 4
            SoundFormat.EAC3_JOC in plan.sound -> 3
            else -> 2
        }
        // DD+ Atmos goes through as DD+ where only that does (the Atmos part is then lost, so it gets no more).
        val passes = format != null && (format in plan.sound || (format == SoundFormat.EAC3_JOC && SoundFormat.EAC3 in plan.sound))
        val through = if (passes) 1 else 0
        val channels = f.channels?.substringBefore('.')?.toIntOrNull() ?: 0
        return minOf(base, cap) * 20 + through * 10 + channels
    }

    /** The sound languages known of a copy: its tracks' once played here, else the add-ons' named ones. */
    private fun knownLanguages(languages: List<String>, measuredLangs: String?): List<String> =
        if (!measuredLangs.isNullOrEmpty()) measuredLangs.split(',').mapNotNull { CopyLanguage.code(it) }
        else languages.filter { it != Words.MULTI }

    /**
     * 2 has the film's own language ([wanted] first), 1 the viewer's only, or may have either (none named, or several);
     * 0 known to have neither. Its tracks once it played here, else what the add-ons say.
     */
    fun language(languages: List<String>, measuredLangs: String?, wanted: List<String>): Int {
        if (wanted.isEmpty()) return 1
        val known = knownLanguages(languages, measuredLangs)
        return when {
            known.isEmpty() -> 1
            wanted[0] in known -> 2
            wanted.size > 1 && wanted[1] in known -> 1
            measuredLangs.isNullOrEmpty() && Words.MULTI in languages -> 1
            else -> 0
        }
    }

    // ---- Next source ----

    /**
     * Next source's order after [currentKey] (the copy playing, or the Best): the copies below it in [ranked] in its own
     * group first ([sameGroup]), then those below it in the others, then those above it the same way; only those that
     * may be picked by themselves. With no [currentKey] in the list, [ranked]'s order.
     */
    fun nextOrder(ranked: List<PlaybackSource>, currentKey: String?, film: Boolean): List<PlaybackSource> {
        val at = ranked.indexOfFirst { it.key == currentKey }
        if (at < 0) return ranked.filter { it.automatic }
        val current = ranked[at]
        val below = ranked.subList(at + 1, ranked.size).filter { it.automatic }
        val above = ranked.subList(0, at).filter { it.automatic }
        val (belowSame, belowOther) = below.partition { sameGroup(it, current, film) }
        val (aboveSame, aboveOther) = above.partition { sameGroup(it, current, film) }
        return belowSame + belowOther + aboveSame + aboveOther
    }

    /** Whether [a] and [b] are listed in the same group of the Sources window (4K IMAX apart from 4K, SD apart from HD). */
    fun sameGroup(a: PlaybackSource, b: PlaybackSource, film: Boolean): Boolean = a.group == b.group &&
        (a.group != SourceGroup.UHD || isImaxCopy(a, film) == isImaxCopy(b, film)) &&
        (a.group != SourceGroup.LOWER || isSd(a) == isSd(b))

    // ---- The same kind of source (next episode, §6.5) and the closest match (a Continue card's file gone) ----

    /**
     * How alike [a] is to [like]: the same binge group (Torrentio's release group of episodes) first, then the same
     * add-on, resolution, HDR kinds, codec, audio and size class.
     */
    fun likeness(a: PlaybackSource, like: PlaybackSource): Int {
        var score = 0
        val groups = like.offers.mapNotNull { it.bingeGroup }.toSet()
        if (a.offers.any { it.bingeGroup != null && it.bingeGroup in groups }) score += 100
        if (a.addons.any { it in like.addons }) score += 16
        if (a.facts.height != null && a.facts.height == like.facts.height) score += 8
        if (a.facts.hdr == like.facts.hdr) score += 4
        if (a.facts.codec != null && a.facts.codec == like.facts.codec) score += 2
        if (a.facts.audio != null && a.facts.audio == like.facts.audio) score += 1
        val x = a.facts.avgMbps ?: a.facts.sizeBytes?.toDouble()
        val y = like.facts.avgMbps ?: like.facts.sizeBytes?.toDouble()
        if (x != null && y != null && x > 0 && y > 0 && x / y in 0.66..1.5) score += 1
        return score
    }

    /** Same add-on and same resolution at least: below this a source isn't "the same kind". */
    const val SAME_KIND = 24

    /**
     * The source most like [like] among [candidates] (ranked, best first), or null when none is the same kind (then
     * the caller takes the best).
     */
    fun mostLike(candidates: List<PlaybackSource>, like: PlaybackSource): PlaybackSource? {
        var best: PlaybackSource? = null
        var score = SAME_KIND - 1
        for (c in candidates) {
            val s = likeness(c, like)
            if (s > score) {
                best = c
                score = s
            }
        }
        return best
    }
}
