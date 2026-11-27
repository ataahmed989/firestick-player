package app.fireplayer.vod.sources

import app.fireplayer.player.CopyLanguage
import app.fireplayer.player.CopyRank
import app.fireplayer.player.DvBaseLayer
import app.fireplayer.player.MediaSpec
import app.fireplayer.vod.BestWhy
import app.fireplayer.vod.EpisodeRef
import app.fireplayer.vod.Hdr
import app.fireplayer.vod.Measured
import app.fireplayer.vod.PlaybackSource
import app.fireplayer.vod.RankStep
import app.fireplayer.vod.Readiness
import app.fireplayer.vod.ReleaseKey
import app.fireplayer.vod.SoundFormat
import app.fireplayer.vod.SourceFacts
import app.fireplayer.vod.SourceGroup
import app.fireplayer.vod.TvFit
import app.fireplayer.vod.TvPlan
import kotlin.math.floor

/** What "Best for this TV" weighs a list against. */
class RankInput(
    val plan: TvPlan,
    /** [CopyLanguage.wanted]: the film's own language first, then the viewer's (one when they're the same or unknown). */
    val wanted: List<String>,
    /** What FirePlayer measured of a release, by [app.fireplayer.vod.ReleaseKey.measuredKey]; null when never played. */
    val measured: (String) -> Measured?,
    val runtimeMin: Int?,
    val episode: Boolean,
    /** Settings › This TV › IMAX first: always ([app.fireplayer.vod.ThisTv.imaxAlways]). */
    val imaxAlways: Boolean = false,
    /** The viewer's subtitle language ("en"): a copy known to carry it gets a small plus; null for none. */
    val subtitles: String? = null,
    /** Now, epoch ms: "Buffered here before" stops counting [SourceRank.TROUBLE_KEEP_MS] after; 0 never. */
    val now: Long = 0L,
    /**
     * The viewer's move of a film's file, by [app.fireplayer.vod.ReleaseKey.storeKey] ([ImaxMoves]): true moved to 4K
     * IMAX, false to 4K; null when never moved.
     */
    val imaxMove: (String) -> Boolean? = { null },
    /**
     * Settings › Playback › Preferred bitrate, Mb/s ([SourceRank.PREFERRED_MBPS] unless the viewer changed it): the rate
     * ranking aims at, as it is. What earlier films were measured to arrive at ([LineRate]) isn't weighed here: a file's
     * own speed shows while it plays, where a copy that can't keep up is answered by Ask / Switch / Keep
     * ([app.fireplayer.vod.play.FilmSupply]).
     */
    val preferredMbps: Double = SourceRank.PREFERRED_MBPS.toDouble(),
    /**
     * The episode ranked for (null: a film, or not said): what was measured of a file whose index isn't known is kept
     * for each episode ([app.fireplayer.vod.ReleaseKey.measuredKey]).
     */
    val episodeRef: EpisodeRef? = null,
)

/**
 * "Best for this TV" (r3_sources-organize §4.4, the user's order of 2026-11), the same order in every group, each step
 * ([RankStep]) only breaking ties of the ones before:
 *
 * 1. Plays here: likely, or played here (the same: a short test play doesn't decide Best), then unknown or one that
 *    lost frames here lately ([stuttered]), then "likely not" ([fit]: showed no picture here, buffered here lately,
 *    Dolby Vision this TV can't show, over the data limit, taller than the TV).
 * 2. Cached (played here smoothly, or the debrid service probably has it), then unknown, then not cached.
 * 3. Not known to lack both the film's own language and the viewer's.
 * 4. Height ([shownHeight]: a copy starved of data for its frame, [CopyRank.starved], counts just under the class
 *    below). 5. The HDR this TV shows ([shownHdr]: Dolby Vision by what this TV does with each profile).
 * 6. Rate: nearest the viewer's preferred bitrate first ([rateKey]; the bitrate as set, never lowered by what earlier
 *    films arrived at: one file's server says nothing of another's), the
 *    higher side counting a little less, nearly equal distances tied; an unknown rate after those near it, and after
 *    every copy that played well here at a rate measured here; an episode's copy whose only size was its season pack's
 *    (or may be), with no estimate of its share, after every copy of a known rate ([keysOf]). A share estimated
 *    ([SourceFacts.sizeEstimated]) ranks as a stated size, here and in fit and size.
 * 7. Source kind (Remux, BluRay encode, WEB-DL, WEBRip, HDTV), then bit depth (10-bit taken for HDR and 4K HEVC).
 * 8. HDR10+ where it reaches the TV ([TvPlan.hdr10PlusReaches]; Dolby Vision on a Dolby Vision TV); a picture that
 *    played here without HDR10's brightness data last.
 * 9. IMAX (films only: the name says IMAX or IMX, or the viewer moved it there, [isImaxCopy]); first of all with
 *    [RankInput.imaxAlways].
 * 10. The film's own language just above the viewer's dub ([language]), subtitles in the viewer's language a little
 *    more; of equal quality the best of each sit side by side ([pairLanguages]).
 * 11. Sound as heard: capped at what the TV or receiver takes as it is ([sound]). 12. Played smoothly here.
 * 13. Size. 14. Seeders. Then the add-ons' order.
 *
 * Pure.
 */
object SourceRank {

    /** Settings › Playback › Preferred bitrate: its default, ends and step, Mb/s. */
    const val PREFERRED_MBPS = 60
    const val PREFERRED_MIN = 20
    const val PREFERRED_MAX = 120
    const val PREFERRED_STEP = 5

    /** The preferred bitrate's band ([rateKey]): distances within this share of the target tie (2 Mb/s at 60). */
    const val NEAR_SHARE = 1.0 / 30

    /** Above the target, a Mb/s counts this much of one below it: of two copies as far off, the higher one is nearer. */
    const val HIGH_SIDE = 0.8

    /** A copy that lost frames here ([stuttered]) is judged on at least this much watching... */
    const val STUTTER_AFTER_MS = 2 * 60_000L

    /** [v], a stored preferred bitrate, within its ends. */
    fun preferredMbps(v: Int): Int = v.coerceIn(PREFERRED_MIN, PREFERRED_MAX)

    /**
     * "Buffered here before" stops counting this long after the copy last played here (or sooner, once later plays of
     * it went smoothly: [app.fireplayer.player.CopyHealth.buffers]). Another file arriving quickly doesn't clear it.
     */
    const val TROUBLE_KEEP_MS = 7L * 24 * 60 * 60 * 1000

    /**
     * A record kept before plays were dated (2026-11-24 00:00 UTC, when the date was added) counts as last played then:
     * it stops counting [TROUBLE_KEEP_MS] after, unless the copy is played again (which dates it).
     */
    const val DATED_SINCE = 1_795_478_400_000L

    /** When [h] last played here, for how old its record is: an undated one ([CopyHealth.at] 0) at [DATED_SINCE]. */
    private fun lastPlayed(h: app.fireplayer.player.CopyHealth): Long = h.at.takeIf { it > 0 } ?: DATED_SINCE

    const val DV7_WORDS = "Dolby Vision 7: may not play here"
    const val NEEDS_DV = "Needs a Dolby Vision TV"
    const val BUFFERED = "Buffered here before"
    const val NO_PICTURE = "Showed no picture here"
    const val NOT_WHOLE = "Shorter than the film here"
    const val NOT_WHOLE_EPISODE = "Shorter than the episode here"
    const val HIGH_RATE = "High data rate"

    /** A copy that wasn't the whole film here ([app.fireplayer.player.CopyHealth.notWhole]), in a [film]'s words or an episode's. */
    fun notWholeWords(film: Boolean): String = if (film) NOT_WHOLE else NOT_WHOLE_EPISODE

    /** A film's or an episode's length when the add-ons' size is known but the runtime isn't (ranking only, never shown). */
    private const val FILM_MIN = 120
    private const val EPISODE_MIN = 45

    /** Ranks [sources] best first, without SD copies when the title has better ([withoutSd]). */
    fun rank(sources: List<PlaybackSource>, input: RankInput): List<PlaybackSource> {
        val rows = withoutSd(sources).map { s ->
            val measured = measuredOf(s, input)
            val (fit, warning) = fit(asPlayedHere(s.facts, measured), input.plan, measured, input.now, input.episode)
            val moved = if (input.episode) null else movedOf(s, input)
            s.copy(fit = fit, warning = warning, measured = measured, imaxMove = moved)
        }
        val keys = keysOf(rows, input)
        val scored = rows.mapIndexed { i, s -> Scored(s, i, keys[i]) }
        return pairLanguages(scored.sortedWith(ORDER), input).map { it.source }
    }

    /**
     * What was measured of a row's file: its own release's, else a merged offer's (the same file from another add-on),
     * each under its key for the episode ranked for ([ReleaseKey.measuredKey]; what an earlier version kept of a torrent
     * of one episode file, [ReleaseKey.earlierKey], while there's nothing newer); else an offer's of no release known, by
     * its [app.fireplayer.vod.Offer.copyKey] (MediaFusion's when its answer and link show no info hash).
     */
    private fun measuredOf(s: PlaybackSource, input: RankInput): Measured? {
        s.release?.let { r -> measuredOf(s, r, input)?.let { return it } }
        for (o in s.offers) o.release?.let { r -> if (r != s.release) measuredOf(s, r, input)?.let { return it } }
        for (o in s.offers) if (o.release == null) o.copyKey?.let { k -> input.measured(k)?.let { return it } }
        return null
    }

    private fun measuredOf(s: PlaybackSource, r: ReleaseKey, input: RankInput): Measured? {
        val episode = input.episodeRef
        val m = input.measured(r.measuredKey(episode))
            ?: r.earlierKey(episode, single(s, r))?.let { input.measured(it) }
            ?: return null
        return if (!input.episode && r.fileIdx == null) ownSize(m, s.facts) else m
    }

    /** Whether an add-on that gives each file of a torrent of several its index offers [r] in [s] without one ([ReleaseKey.earlierKey]: Torrentio). */
    private fun single(s: PlaybackSource, r: ReleaseKey): Boolean =
        r.fileIdx == null && s.offers.any { o -> ReleaseKey.indexesEveryFile(o.addon) && (o.release ?: s.release) == r }

    /**
     * [m], measured of a film's file without an index: a collection's films share that key, so a size (and the rate from
     * it) other than the one an add-on gave to the byte ([SourceFacts.sizeExact]) was another film's, and isn't this
     * row's ([asPlayedHere] keeps the add-on's); nor is the HDR its picture played with (HDR10, HDR10+, Dolby Vision and
     * how this TV played it: [asPlayedHere] and Details keep what the name says).
     */
    private fun ownSize(m: Measured, facts: SourceFacts): Measured {
        val spec = m.spec ?: return m
        val stated = facts.sizeBytes?.takeIf { facts.sizeExact } ?: return m
        if (spec.sizeBytes <= 0 || SourceMerge.sizeClose(spec.sizeBytes, stated)) return m
        return m.copy(spec = spec.copy(sizeBytes = 0, overallBps = 0, hdr = null, hdr10Plus = false, hdrInfo = null, dvProfile = 0,
            dvBaseLayer = 0, dvSignal = 0, decoder = MediaSpec.Decoder.UNKNOWN, hdr10PlusHeld = 0))
    }

    /** The viewer's move of a row's file ([RankInput.imaxMove]): its own release's, else a merged offer's. */
    private fun movedOf(s: PlaybackSource, input: RankInput): Boolean? {
        s.release?.let { r -> input.imaxMove(r.storeKey)?.let { return it } }
        for (o in s.offers) o.release?.let { r -> if (r != s.release) input.imaxMove(r.storeKey)?.let { return it } }
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

    /**
     * One value per [RankStep], higher first; [dub]: the viewer's language without the film's own; [rateKnown]: its rate
     * is known ([mbpsOf]); [proven]: it played well here at a rate measured here that feeds its picture; [torrentTotal]:
     * its rate isn't known because its only size was its torrent's ([SourceFacts.torrentTotal]) ([keysOf]).
     */
    private class Key(
        val v: LongArray, val dub: Boolean, val rateKnown: Boolean = true, val proven: Boolean = false, val torrentTotal: Boolean = false,
    )

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
        val stutters = stuttered(s.measured, input.now)
        val playedWell = spec != null && !stutters && !troubled(s.measured, input.now)
        val hdr = hdrRank(f, plan)
        val known = knownLanguages(f.languages, spec?.audioLangs)
        val level = language(f.languages, spec?.audioLangs, input.wanted)
        val subs = input.subtitles?.let { CopyLanguage.code(it) }?.let { want ->
            want in f.subs || CopyLanguage.hasSubs(spec?.subLangs.orEmpty(), want)
        } == true
        val v = LongArray(STEPS.size)
        v[RankStep.IMAX_ALWAYS.ordinal] = if (input.imaxAlways && !stutters && imaxFirst(s, !input.episode)) 1 else 0
        v[RankStep.PLAYS.ordinal] = when {
            s.fit == TvFit.LIKELY_NOT -> 0
            s.fit == TvFit.UNKNOWN || stutters -> 1
            else -> 2
        }
        v[RankStep.CACHED.ordinal] = when {
            playedWell || s.readiness == Readiness.PROBABLY_READY -> 2
            s.readiness == Readiness.UNKNOWN -> 1
            else -> 0
        }
        v[RankStep.LANGUAGE_KNOWN.ordinal] = if (level == 0) 0 else 1
        val shown = shownHeight(f, spec, input)
        v[RankStep.HEIGHT.ordinal] = shown.toLong()
        v[RankStep.HDR.ordinal] = hdr.toLong()
        val mbps = mbpsOf(f, spec, input)
        v[RankStep.RATE.ordinal] = rateKey(mbps, input)
        v[RankStep.KIND.ordinal] = originRank(f.origin) * 100L + bitDepth(f)
        v[RankStep.DYNAMIC_HDR.ordinal] = dynamicHdr(shownHdr(f, plan), spec, plan).toLong()
        v[RankStep.IMAX.ordinal] = if (isImaxCopy(s, !input.episode)) 1 else 0
        v[RankStep.LANGUAGE.ordinal] = level * 2L + if (subs) 1 else 0
        v[RankStep.SOUND.ordinal] = sound(f, plan).toLong()
        v[RankStep.SMOOTH.ordinal] = if (playedWell) 1 else 0
        v[RankStep.SIZE.ordinal] = f.sizeBytes ?: 0L
        v[RankStep.SEEDERS.ordinal] = (f.seeders ?: 0).toLong()
        val dub = input.wanted.size > 1 && input.wanted[1] in known && input.wanted[0] !in known
        // Its rate measured here (the file's size or average), and not starved for its picture ([shownHeight]).
        val measuredRate = spec != null && (spec.overallBps > 0 || spec.sizeBytes > 0)
        val proven = playedWell && measuredRate && mbps != null && f.height != null && shown == f.height
        return Key(v, dub, rateKnown = mbps != null, proven = proven, torrentTotal = mbps == null && f.torrentTotal)
    }

    /**
     * [rows]' keys ([keyOf]). A rate not known ([rateKey]'s guess, near the top) is never put above a copy that played
     * well here at a rate measured here ([Key.proven]) when nothing before [RankStep.RATE] tells them apart: it goes just
     * under the lowest such copy. A rate measured on this TV beats a guess. An episode's copy whose only size was its
     * season pack's ([Key.torrentTotal]: the file could be any size) goes under every copy of a known rate it ties with
     * there, played here or not, until it plays here and is measured.
     */
    private fun keysOf(rows: List<PlaybackSource>, input: RankInput): List<Key> {
        val keys = rows.map { keyOf(it, input) }
        if (keys.all { it.rateKnown }) return keys
        val proven = keys.filter { it.proven }
        val known = keys.filter { it.rateKnown }
        val rate = RankStep.RATE.ordinal
        for (k in keys) {
            if (k.rateKnown) continue
            val above = if (k.torrentTotal) known else proven
            val lowest = above.filter { stepBetween(it, k, until = rate) == null }.minOfOrNull { it.v[rate] } ?: continue
            k.v[rate] = minOf(k.v[rate], lowest - 1)
        }
        return keys
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

    /** The steps that are the picture: a copy better in these lost on an earlier one (not [RankStep.RATE], a liking). */
    private val QUALITY =
        listOf(RankStep.HEIGHT, RankStep.HDR, RankStep.KIND, RankStep.DYNAMIC_HDR, RankStep.IMAX).map { it.ordinal }

    /**
     * What put [ranked]'s Best first, and what it beat: a copy with a better picture that an earlier step put after it
     * (not cached, buffered here…), else the first copy after it that lost on resolution, HDR, source, IMAX
     * or language. Copies that differ from it only in sound, size or seeders aren't named, nor one beaten on the
     * preferred bitrate ([RankStep.RATE]) or that lost frames here ([stuttered]): those stay the ranking's own business.
     * Null without a Best.
     */
    fun why(ranked: List<PlaybackSource>, input: RankInput): BestWhy? {
        val best = best(ranked) ?: return null
        val at = ranked.indexOf(best)
        val keys = keysOf(ranked, input)
        val mine = keys[at]
        val played = asPlayedHere(best.facts, best.measured)
        // A profile 8 file not played yet is said as the add-on has it: its base layer isn't known.
        val shown = if (dv8Unmeasured(played, input.plan)) played.hdr else shownHdr(played, input.plan)
        var rival = -1
        // Never a cinema recording, nor a copy whose resolution isn't stated: its picture data has no frame to be judged by.
        for (i in ranked.indices) {
            if (i == at || ranked[i].group == SourceGroup.CINEMA || ranked[i].fit == TvFit.UNKNOWN) continue
            // Nor one that lost frames here lately: that stays the ranking's own business, never a reason given.
            if (stuttered(ranked[i].measured, input.now)) continue
            if (better(keys[i], mine) && (rival < 0 || better(keys[i], keys[rival]))) rival = i
        }
        if (rival < 0) {
            for (i in at + 1 until ranked.size) {
                if (ranked[i].group == SourceGroup.CINEMA) continue
                val step = stepBetween(mine, keys[i]) ?: continue
                if (step.ordinal in RankStep.HEIGHT.ordinal..RankStep.LANGUAGE.ordinal && step != RankStep.RATE) {
                    rival = i
                    break
                }
            }
        }
        if (rival < 0) return BestWhy(best.key, shown)
        val step = stepBetween(mine, keys[rival]) ?: return BestWhy(best.key, shown)
        if (step == RankStep.RATE) return BestWhy(best.key, shown)
        return BestWhy(best.key, shown, ranked[rival].key, step, lacks(step, ranked[rival], keys[rival], input, best))
    }

    /** Whether [a]'s picture steps beat [b]'s. */
    private fun better(a: Key, b: Key): Boolean {
        for (i in QUALITY) if (a.v[i] != b.v[i]) return a.v[i] > b.v[i]
        return false
    }

    /** What the copy beaten at [step] lacks against [best], in plain words ("no HDR10+", "not cached yet"). */
    private fun lacks(step: RankStep, b: PlaybackSource, key: Key, input: RankInput, best: PlaybackSource): String? {
        val f = asPlayedHere(b.facts, b.measured)
        return when (step) {
            RankStep.IMAX_ALWAYS, RankStep.IMAX -> "not IMAX"
            RankStep.PLAYS -> b.warning ?: if (b.fit == TvFit.UNKNOWN) "resolution not stated" else "more than this TV shows"
            RankStep.CACHED -> if (b.readiness == Readiness.NOT_READY) "not cached yet" else "may not be cached"
            RankStep.LANGUAGE_KNOWN -> "no " + input.wanted.joinToString(" or ") { CopyLanguage.name(it) } + " sound"
            // A starved copy ([shownHeight]) of the Best's own resolution or more.
            RankStep.HEIGHT -> if ((b.facts.height ?: 0) >= (best.facts.height ?: 0)) "less picture data" else "lower resolution"
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
     * Whether [facts] likely play well under [plan], and at most one warning for the row. [now] for how old a stall is;
     * [episode] for the words of a copy that wasn't the whole episode here. What earlier films arrived at isn't weighed:
     * only this copy's own record, the TV, and the viewer's data limit ([TvPlan.maxMbps]).
     */
    fun fit(
        facts: SourceFacts, plan: TvPlan, measured: Measured?, now: Long = 0L, episode: Boolean = false,
    ): Pair<TvFit, String?> {
        val avg = facts.avgMbps
        val cap = plan.maxMbps
        val dv = dvProblem(facts, plan)
        return when {
            // Its picture never showed on this Stick ([app.fireplayer.player.CopyHealth.noPicture]): never picked by itself.
            (measured?.health?.noPicture ?: 0) > 0 -> TvFit.LIKELY_NOT to NO_PICTURE
            // It wasn't the whole film here (another video of its torrent, or it ended at once): never picked by itself.
            (measured?.health?.notWhole ?: 0) > 0 -> TvFit.LIKELY_NOT to notWholeWords(!episode)
            // Frames lost aren't "buffered": a copy that only lost frames goes down a little instead ([stuttered]).
            measured?.health?.buffers == true && troubled(measured, now) -> TvFit.LIKELY_NOT to BUFFERED
            dv != null -> TvFit.LIKELY_NOT to dv
            cap != null && avg != null && avg > cap -> TvFit.LIKELY_NOT to "Over your $cap Mb/s limit"
            facts.height != null && facts.height > plan.topHeight -> TvFit.LIKELY_NOT to null
            measured?.spec != null -> TvFit.MEASURED_OK to null
            facts.height == null -> TvFit.UNKNOWN to null
            else -> TvFit.LIKELY to null
        }
    }

    /**
     * Whether a copy counts as one that buffered or dropped frames here ([app.fireplayer.player.CopyHealth.troubled]):
     * not once it last played [TROUBLE_KEEP_MS] ago or more, nor once later plays of it went smoothly enough
     * ([app.fireplayer.player.CopyHealth.buffers], [app.fireplayer.player.CopyHealth.choppy]). Its own record only:
     * another file arriving quickly says nothing of this one's server. A record kept before plays were dated counts as
     * played at [DATED_SINCE].
     */
    fun troubled(measured: Measured?, now: Long): Boolean {
        val h = measured?.health ?: return false
        if (!h.troubled) return false
        if (now > 0 && now - lastPlayed(h) >= TROUBLE_KEEP_MS) return false
        return true
    }

    /**
     * Whether a copy lost frames here lately, last played less than [TROUBLE_KEEP_MS] ago: in its decoder
     * ([app.fireplayer.player.CopyHealth.lateFrames]: Media3's late drops and frames never decoded) more than one in
     * [app.fireplayer.player.CopyHealth.LOST_SHARE] over [STUTTER_AFTER_MS] or more of watching, or by its whole
     * frame-loss record ([app.fireplayer.player.CopyHealth.choppy]: frames shown twice too, and records kept before
     * [app.fireplayer.player.CopyHealth.lateFrames]). The connection never clears it; later smooth plays dilute it.
     * Ranking only: no warning.
     */
    fun stuttered(measured: Measured?, now: Long): Boolean {
        val h = measured?.health ?: return false
        val late = h.watchedMs >= STUTTER_AFTER_MS && h.lateFrames > 0 &&
            h.lateFrames * app.fireplayer.player.CopyHealth.LOST_SHARE > h.lateFrames + h.renderedFrames
        return (late || h.choppy) && (now <= 0 || now - lastPlayed(h) < TROUBLE_KEEP_MS)
    }

    /** Why this TV can't show a Dolby Vision copy as it is ([DV7_WORDS], [NEEDS_DV]); null when it can, or it isn't one. */
    private fun dvProblem(f: SourceFacts, plan: TvPlan): String? {
        if (Hdr.DOLBY_VISION !in f.hdr) return null
        val tvDv = plan.hdrOrder.any { Hdr.DOLBY_VISION in it }
        if (f.dvProfile == 7) return if (plan.dv7AsHdr10 == false && !tvDv) DV7_WORDS else null
        if (tvDv) return null
        val base = Hdr.HDR10 in f.hdr || Hdr.HDR10_PLUS in f.hdr || Hdr.HLG in f.hdr
        return if (f.dvProfile == 5 || (f.dvProfile == null && !base)) NEEDS_DV else null
    }

    /**
     * [facts] as this TV played the file, by what FirePlayer [measured] when it played here: a Dolby Vision file played
     * on a plain decoder (as its HDR10 base layer, or Media3's own way back to HEVC) is its base layer here, not Dolby
     * Vision: HLG or SDR where the file's record said so ([MediaSpec.dvSignal]: 8.4, 8.2), else HDR10; its profile is
     * filled in from the play; HDR10+ seen in the picture is added, and HDR10+ the play kept from the TV for a reason of
     * the file's own ([MediaSpec.hdr10PlusHeld]: added to a Dolby Vision file, broken, or not on every frame) is the
     * HDR10 inside, whatever its name says; a name that said no HDR takes the HDR the file played
     * with ([playedHdr]: a WEB copy whose name leaves out its HDR10). The file's size and average rate as the play
     * measured them replace the add-ons' (a season pack's total, or its share, isn't the episode's file: the pack's total
     * stays beside it when it is known to be one, [SourceMerge.packBeside]). Otherwise [facts] as the add-ons have them.
     */
    fun asPlayedHere(facts: SourceFacts, measured: Measured?): SourceFacts {
        val spec = measured?.spec ?: return facts
        var f = facts
        if (spec.sizeBytes > 0 && (f.sizeBytes != spec.sizeBytes || !f.sizeExact)) {
            // An episode's file of a season pack: its size, its pack's total beside it when it is known to be one.
            f = f.copy(sizeBytes = spec.sizeBytes, sizeExact = true, torrentTotal = false, sizeEstimated = false,
                packBytes = SourceMerge.packBeside(spec.sizeBytes, f.packBytes, f.packUnclear), packEpisodes = null, packUnclear = false)
        }
        if (spec.overallBps > 0) f = f.copy(avgMbps = spec.overallBps / 1_000_000.0)
        // A name that said no HDR: the HDR the file played with here (a WEB copy whose name leaves out its HDR10).
        if (f.hdr.isEmpty()) {
            val played = playedHdr(spec)
            if (played.isNotEmpty()) f = f.copy(hdr = played, dvProfile = f.dvProfile ?: spec.dvProfile.takeIf { it > 0 })
        }
        if (Hdr.DOLBY_VISION in f.hdr) {
            val plain = spec.dvBaseLayer > 0 || (spec.decoder == MediaSpec.Decoder.PLAIN && spec.dvProfile != 5)
            val profile = spec.dvProfile.takeIf { it > 0 }
            f = when {
                plain -> f.copy(hdr = baseOf(f.hdr - Hdr.DOLBY_VISION, spec.dvSignal), dvProfile = null)
                profile != null && f.dvProfile == null -> f.copy(dvProfile = profile)
                else -> f
            }
        }
        f = when {
            // HDR10+ this TV isn't given for a reason of the file's own: the HDR10 inside is what plays here.
            spec.hdr10PlusHeld != 0 -> if (Hdr.HDR10_PLUS in f.hdr) f.copy(hdr = f.hdr - Hdr.HDR10_PLUS + Hdr.HDR10) else f
            spec.hdr10Plus && Hdr.HDR10_PLUS !in f.hdr -> f.copy(hdr = f.hdr + Hdr.HDR10_PLUS)
            else -> f
        }
        return f
    }

    /**
     * The HDR kinds of the file as it played here ([MediaSpec.hdr], what its picture's track declares): Dolby Vision (its
     * base layer as this TV played it is worked out from there), HDR10, HLG; empty for SDR or when it didn't say. HDR10+
     * is added where it was seen ([asPlayedHere]).
     */
    fun playedHdr(spec: MediaSpec): Set<Hdr> = when {
        spec.dvProfile > 0 || spec.dvBaseLayer > 0 || spec.hdr == "Dolby Vision" -> setOf(Hdr.DOLBY_VISION)
        spec.hdr == "HDR10" -> setOf(Hdr.HDR10)
        spec.hdr == "HLG" -> setOf(Hdr.HLG)
        else -> emptySet()
    }

    /** A Dolby Vision file's base layer, by its record's signal ([MediaSpec.dvSignal]): HLG, SDR, else HDR10. */
    private fun baseOf(rest: Set<Hdr>, signal: Int): Set<Hdr> = when (signal) {
        DvBaseLayer.SIGNAL_HLG -> rest - Hdr.HDR10 - Hdr.HDR10_PLUS + Hdr.HLG
        DvBaseLayer.SIGNAL_SDR -> rest - Hdr.HDR10 - Hdr.HDR10_PLUS - Hdr.HLG
        else -> rest + Hdr.HDR10
    }

    /**
     * A profile 8 file not played here whose name says nothing of its base layer, on a TV without Dolby Vision: ranked
     * as its likely HDR10 ([shownHdr]), but not called HDR10 until a play reads its record ([MediaSpec.dvSignal]).
     */
    fun dv8Unmeasured(f: SourceFacts, plan: TvPlan): Boolean =
        Hdr.DOLBY_VISION in f.hdr && f.dvProfile == 8 && Hdr.HDR10 !in f.hdr && Hdr.HDR10_PLUS !in f.hdr && Hdr.HLG !in f.hdr &&
            plan.hdrOrder.none { Hdr.DOLBY_VISION in it }

    /**
     * The HDR this TV shows of [f] (as played here, [asPlayedHere]). Dolby Vision by what this TV does with each
     * profile: profile 7 is the HDR10 (or HDR10+) picture inside it when this Stick plays its base layer
     * ([TvPlan.dv7AsHdr10], on any TV: its decoders don't take profile 7); on a TV without Dolby Vision, profile 8, or a
     * file that also names HDR10 or HDR10+, is that picture (one naming HLG only, its HLG: 8.4); profile 5 and a Dolby
     * Vision file naming nothing else stay Dolby Vision (which such a TV can't show). On a Dolby Vision TV, Dolby Vision
     * as it is. On an HDR TV without HDR10+, HDR10+ is HDR10.
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
        val hlg = Hdr.HLG in f.hdr && Hdr.HDR10 !in f.hdr && Hdr.HDR10_PLUS !in f.hdr
        return when {
            hlg && f.dvProfile != 5 -> f.hdr - Hdr.DOLBY_VISION
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
     * Whether [s] is an IMAX copy of a [film] (never a show's episode): the viewer moved it to 4K IMAX, or its name says
     * IMAX or IMX and the viewer didn't move it to 4K ([PlaybackSource.imaxMove]). Never by measuring the picture.
     */
    fun isImaxCopy(s: PlaybackSource, film: Boolean): Boolean {
        if (!film) return false
        s.imaxMove?.let { return it }
        return s.offers.any { isImax(it.text) || isImax(it.filename) }
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

    /**
     * The height a copy counts as: its stated one, or, starved of data for its frame ([CopyRank.starved]: the picture
     * measured here, else the file's rate; codec from the name, HEVC for 4K), just under the class below (a starved 4K
     * after every well-fed 1080p). An unknown rate ([mbpsOf]) is never starved.
     */
    private fun shownHeight(f: SourceFacts, spec: MediaSpec?, input: RankInput): Int {
        val height = f.height ?: return 0
        val played = spec?.takeIf { it.videoHow == MediaSpec.How.MEASURED && it.videoBps > 0 }?.videoBps
        val bps = played ?: mbpsOf(f, spec, input)?.let { (it * 1_000_000).toLong() } ?: return height
        val codec = f.codec ?: if (height >= 2160) "HEVC" else null
        val frame = MediaSpec(videoCodec = codec, width = widthOf(height), height = height, bitDepth = f.bitDepth ?: 0, overallBps = bps)
        if (!CopyRank.starved(frame, bits = bps)) return height
        return when {
            height >= 1440 -> 1079
            height >= 1080 -> 719
            height >= 720 -> 479
            else -> height
        }
    }

    /**
     * The file's rate, Mb/s: measured here (its exact size over its length), else the add-ons' size over the runtime,
     * else over an assumed length ([FILM_MIN], [EPISODE_MIN]; ranking only). Null without a size.
     */
    private fun mbpsOf(f: SourceFacts, spec: MediaSpec?, input: RankInput): Double? =
        spec?.overallBps?.takeIf { it > 0 }?.let { it / 1_000_000.0 } ?: f.avgMbps
            ?: f.sizeBytes?.takeIf { it > 0 }?.let {
                it * 8.0 / ((input.runtimeMin ?: if (input.episode) EPISODE_MIN else FILM_MIN) * 60.0) / 1_000_000.0
            }

    /** The rate aimed at: the viewer's preferred bitrate, as set ([RankInput.preferredMbps]). */
    private fun target(input: RankInput): Double = input.preferredMbps

    /**
     * How near [mbps] is to the [target], higher first: bands of [NEAR_SHARE] of it (2 Mb/s at 60), a Mb/s above it
     * counting [HIGH_SIDE] of one below, so the nearest wins and only nearly equal distances tie. An unknown rate sits
     * after copies within three bands (about 54 to 67 at 60) and before every copy known to be further off. Equal bands
     * leave it to the steps after (size last: the higher rate).
     */
    private fun rateKey(mbps: Double?, input: RankInput): Long {
        val r = mbps ?: return -5L
        val t = target(input)
        if (t <= 0.0) return 0L
        val d = if (r >= t) (r - t) * HIGH_SIDE else t - r
        return -2L * floor(d / (t * NEAR_SHARE)).toLong()
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
     * Next source's order after [currentKey] (the copy playing, or the Best), by the Sources window's list: the rest of
     * its own group first ([listPlace]), down from it and then the ones above it in the group, so a group isn't left
     * while a copy of it is still there to try (the lowest of three 4K copies goes on to the other two 4K ones, not to
     * 1080p); then the groups under that one, top to bottom, each in [ranked]'s order; only then the groups above, top
     * to bottom. Only those that may be picked by themselves, never one that showed no picture here or wasn't the whole
     * film, and never the copy itself: neither its row nor any row holding the same file ([same], e.g. [FileSet.holds]:
     * another add-on's row of it, or its row under a new key once the list was ranked again). Its row not in the list by
     * [currentKey], the one holding its file stands in; none, [current]'s own group from the top; with neither,
     * [ranked]'s order.
     */
    fun nextOrder(
        ranked: List<PlaybackSource>,
        currentKey: String?,
        film: Boolean,
        current: PlaybackSource? = null,
        same: (PlaybackSource) -> Boolean = { false },
    ): List<PlaybackSource> {
        val at = ranked.indexOfFirst { it.key == currentKey }.takeIf { it >= 0 } ?: ranked.indexOfFirst(same)
        val own = when {
            at >= 0 -> listPlace(ranked[at], film)
            current != null -> listPlace(current, film)
            else -> return ranked.filter { pickable(it) && !same(it) }
        }
        val places = IntArray(ranked.size) { listPlace(ranked[it], film) }
        val out = ArrayList<PlaybackSource>(ranked.size)
        fun take(from: Int, until: Int, place: Int) {
            for (i in from until until) {
                val s = ranked[i]
                if (places[i] == place && i != at && s.key != currentKey && pickable(s) && !same(s)) out += s
            }
        }
        take(at + 1, ranked.size, own)
        if (at > 0) take(0, at, own)
        for (p in own + 1 until LIST_PLACES) take(0, ranked.size, p)
        for (p in 0 until own) take(0, ranked.size, p)
        return out
    }

    /**
     * Picked by itself: not a cinema recording or uncached ([PlaybackSource.automatic]), and not one that showed no
     * picture here or wasn't the whole film ([app.fireplayer.player.CopyHealth.setAside]).
     */
    private fun pickable(s: PlaybackSource): Boolean = s.automatic && s.measured?.health?.setAside != true

    /** How many groups the Sources window lists ([listPlace]). */
    const val LIST_PLACES = 9

    /**
     * Where [s]'s group is in the Sources window's list, from the top ([app.fireplayer.vod.play.SourcesModel.Section],
     * in its order): 4K IMAX (a [film]'s IMAX copies, [isImaxCopy]), 4K, 1080p, 720p, 3D, Resolution not stated, Cinema
     * recordings, Not cached yet, Lower quality (SD).
     */
    fun listPlace(s: PlaybackSource, film: Boolean): Int = when (s.group) {
        SourceGroup.UHD -> if ((s.facts.height ?: 0) >= 2160 && isImaxCopy(s, film)) 0 else 1
        SourceGroup.FHD -> 2
        SourceGroup.LOWER -> if (isSd(s)) 8 else 3
        SourceGroup.THREE_D -> 4
        SourceGroup.OTHER -> 5
        SourceGroup.CINEMA -> 6
        SourceGroup.NOT_ON_RD -> 7
    }

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
