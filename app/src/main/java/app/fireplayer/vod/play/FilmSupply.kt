package app.fireplayer.vod.play

import app.fireplayer.vod.PlaybackSource
import app.fireplayer.vod.sources.LineRate

/** Settings › Playback › When a film keeps buffering. */
enum class WhenBuffering(val label: String) {
    /** Asked once: switch to a lighter source, or keep this one. The answer is remembered for the film. */
    ASK("Ask"),
    /** Switched without asking, to the best source this connection keeps up with (or the next one), with a note. */
    LIGHTER("Switch to a lighter source"),
    /** Nothing is switched and nothing asked: the source chosen plays on, waits and all. */
    KEEP("Keep trying"),
    ;

    companion object {
        fun named(name: String?): WhenBuffering = entries.firstOrNull { it.name == name } ?: ASK
    }
}

/**
 * What the viewer answered when asked about a film that kept buffering, remembered for that film: [LIGHTER] for the
 * film whatever plays (a later source too heavy for the line is left without asking again), [KEEP] for the source it
 * was said of only (another source of the same film is judged afresh).
 */
enum class StallChoice {
    LIGHTER, KEEP;

    companion object {
        /** The answer as it's kept: "LIGHTER", or "KEEP|" and the key of the source kept. No links, no titles. */
        fun encode(choice: StallChoice, sourceKey: String): String = if (choice == KEEP) "KEEP|$sourceKey" else LIGHTER.name

        /** The answer kept as [text] that counts while [sourceKey] plays; null for none. */
        fun decode(text: String?, sourceKey: String): StallChoice? = when {
            text == null -> null
            text == LIGHTER.name -> LIGHTER
            text == "KEEP|$sourceKey" -> KEEP
            else -> null
        }
    }
}

/**
 * Whether a film keeps stopping to load because the connection brings it no faster than it plays, and what to say
 * about it.
 *
 * Measured on a Fire TV Stick 4K Max: a 4K remux of 89 GB over 2 h 53 min (69 Mb/s on average, more in busy scenes)
 * downloading at 65 to 82 Mb/s stalled every 6 to 16 s. The download being "faster than the film" by a few percent on
 * average isn't enough: the film's rate swings well above its average for minutes at a time, and what this TV's
 * memory holds ahead (5 s of such a film) can't carry it over them. No buffer rule cures that; a file the line keeps
 * up with does. So when the stall rules call for a switch ([app.fireplayer.vod.StallWatch.shouldSwitch]) and the
 * film's own rate is over [LineRate.SHORT_SHARE] of what the download sustains, the next source of the same size
 * isn't tried (it would stall the same): the best source needing at most [LineRate.SUSTAIN_SHARE] of the line is
 * offered instead, once, in plain words.
 *
 * Pure: no Android, no links.
 */
object FilmSupply {
    /** The download's sustained speed only counts once it's been measured over this many seconds of free loading. */
    const val MIN_SECONDS = 15

    /**
     * What the player measured: [lineMbps] the download sustains while the buffer has room (null: too little measured),
     * [fillRate] seconds of film arriving per second of loading lately (null: not measured).
     */
    data class Reading(val lineMbps: Double?, val fillRate: Float?)

    /**
     * Whether the connection is short for a source of [sourceMbps] (its size over its length; null when not known):
     * the source needs more than [LineRate.SHORT_SHARE] of [Reading.lineMbps], and the film isn't seen to arrive
     * [PLENTY] times as fast as it plays or faster (the two are measured apart, and both must say it: a film arriving
     * with plenty to spare stalled for another reason). With the source's rate or the line's unknown, the film
     * arriving slower than 1 / [LineRate.SHORT_SHARE] times as fast as it plays says the same.
     */
    fun short(sourceMbps: Double?, r: Reading?): Boolean {
        if (r == null) return false
        val rate = r.fillRate
        if (sourceMbps != null && r.lineMbps != null) return LineRate.tooFast(sourceMbps, r.lineMbps) && (rate == null || rate < PLENTY)
        return rate != null && rate < 1f / LineRate.SHORT_SHARE.toFloat()
    }

    /** A film arriving this many times as fast as it plays has plenty: its stalls aren't for want of download speed. */
    const val PLENTY = 1.3f

    /** The most a source may need to be one this line keeps up with, in Mb/s; null when the line isn't measured. */
    fun ceilingMbps(r: Reading?): Double? = r?.lineMbps?.takeIf { it > 0.0 }?.let { it * LineRate.SUSTAIN_SHARE }

    /** "This source needs about 69 Mb/s and this connection is bringing about 72, so it keeps pausing." */
    fun why(sourceMbps: Double?, r: Reading?): String {
        val line = r?.lineMbps
        return when {
            sourceMbps != null && line != null ->
                "This source needs about ${round(sourceMbps)} Mb/s and this connection is bringing about ${round(line)}, so it keeps pausing."
            line != null -> "This source arrives only about as fast as it plays (about ${round(line)} Mb/s), so it keeps pausing."
            else -> "This source arrives only about as fast as it plays, so it keeps pausing."
        }
    }

    /** The question asked once: [why], then the lighter source by its badges ("4K · HDR · BluRay · ≈26 Mb/s"). */
    fun question(sourceMbps: Double?, r: Reading?, lighter: PlaybackSource): String =
        why(sourceMbps, r) + " Switch to " + PlayRules.badges(lighter).ifEmpty { "a lighter source" } + "? It carries on from here."

    const val SWITCH = "Switch to it"
    const val KEEP = "Keep this source"

    /** Asked when a source the viewer picked keeps pausing though the connection is fast enough for it. */
    const val PLAIN_QUESTION = "This source keeps pausing. Try the next source? It carries on from here."
    const val TRY_NEXT = "Try next source"

    /** The note when a lighter source is switched to without asking (the setting, or the film's remembered answer). */
    fun switchedNote(r: Reading?): String =
        r?.lineMbps?.let { "Kept pausing at ${round(it)} Mb/s · switching to a lighter source" } ?: "Kept pausing · switching to a lighter source"

    /**
     * The note when this source is kept though the connection is short for it (Keep trying, or "Keep this source"
     * answered): "This copy needs about 78 Mb/s and about 47 is arriving, so it will keep pausing · 4K · HDR · ≈26 Mb/s
     * fits, in Sources". Said once.
     */
    fun keptNote(sourceMbps: Double?, r: Reading?, fits: PlaybackSource?): String {
        val line = r?.lineMbps
        val why = if (sourceMbps != null && line != null) {
            "This copy needs about ${round(sourceMbps)} Mb/s and about ${round(line)} is arriving, so it will keep pausing"
        } else {
            "This copy arrives slower than it plays, so it will keep pausing"
        }
        return why + " · " + (fits?.let { (PlayRules.badges(it).ifEmpty { "A lighter copy" }) + " fits, in Sources" } ?: "Sources lists every file")
    }

    /** The note when nothing lighter is ready. */
    fun noneNote(r: Reading?): String =
        (r?.lineMbps?.let { "This connection brings about ${round(it)} Mb/s" } ?: "This source arrives only as fast as it plays") +
            " · no lighter source is ready · Sources lists every file"

    private fun round(v: Double): Long = Math.round(v)
}
