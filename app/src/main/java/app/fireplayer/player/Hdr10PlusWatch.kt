package app.fireplayer.player

import java.nio.ByteBuffer

/**
 * What one film's HDR10+ is done with, sample by sample, for [PacedVideoRenderer]: so the TV is kept in one HDR mode
 * from the film's first frame to its last.
 *
 * A Samsung TV on a Fire TV Stick 4K Max went black for a moment, again and again, the sound playing on, through a
 * Dolby Vision profile 8 copy with HDR10+ added to it, played by the HEVC decoder (the TV has no Dolby Vision): every
 * frame reached the screen on time and nothing changed on HDMI, while copies of the same film without HDR10+ played
 * steadily. So the HDR10+ itself is what the TV answered. It is hidden from the decoder ([Hdr10PlusMask]) for a whole
 * film, decided before its first frame is decoded, when:
 *  - the TV doesn't report HDR10+, or Settings › This TV › HDR10+ reaches this TV is off ([Hdr10PlusGate]);
 *  - the film is a Dolby Vision track Media3 gives the HEVC decoder as it is ([HYBRID]: profile 8 on a TV without
 *    Dolby Vision): its HDR10+ was added to a Dolby Vision copy by someone other than the studio, often from another
 *    release, and is the kind that misbehaves. A profile 7 remux played as its base layer ([DvBaseLayer]) isn't one:
 *    that picture is the disc's own HDR10, and HDR10+ in it the disc's too.
 * And from the frame it shows, for the rest of the film, never going back, when:
 *  - a message breaks HDR10+'s own rules ([MALFORMED], [Hdr10PlusValues.bad]);
 *  - a frame comes without HDR10+ after frames with it, or the film's first HDR10+ comes after frames without any
 *    ([GAPS]; x265's dhdr10-opt writes it only where it changes): the TV would be switched between HDR10+ and HDR10
 *    again and again. The first way round, the frames before went to the decoder with it, so the TV may switch that
 *    once; the second, none ever does.
 * A film's own, well-formed HDR10+ on every frame goes to the decoder as it is.
 *
 * Each sample's few bytes before its picture data are read, as they always were to mask them (a film without HDR10+
 * has a dozen or so there), and a message's numbers with them; nothing is made per frame. Playback thread only.
 */
class Hdr10PlusWatch {

    /** This film has HDR10+ ([PlayFacts.hdr10PlusSeen]). */
    var found = false
        private set
    /** Why this film's HDR10+ is hidden whatever the TV takes ([HYBRID], [MALFORMED], [GAPS]); 0 when it isn't. */
    var kept = 0
        private set
    /** HDR10+ is hidden from the decoder now, the film having it ([PlayFacts.hdr10PlusKept]). */
    var masking = false
        private set

    private val values = Hdr10PlusValues()
    /** A frame of this film came without HDR10+ before any came with it. */
    private var bare = false
    private var told = -1

    // Since the last summary.
    private var sinceMs = 0L
    private var with = 0
    private var without = 0
    private var malformed = 0
    private var odd = 0
    private var changes = 0
    private var curves = 0
    private var lastSum = 0
    private var minTarget = 0
    private var maxTarget = 0
    private var minPeak = 0
    private var maxPeak = 0
    private var minAverage = 0
    private var maxAverage = 0
    private var minAnchors = 0
    private var maxAnchors = 0
    private var ranged = false

    /** The next film or episode: looked at afresh. */
    fun newStream() {
        found = false
        kept = 0
        masking = false
        bare = false
        told = -1
        sinceMs = 0L
        resetCounts()
    }

    /**
     * One sample ([buffer], an HEVC access unit, from its position to its limit) on its way to the decoder. [gate]:
     * [PASS], or why HDR10+ is hidden for this TV ([TV], [SETTING]); [hybrid]: the film is a Dolby Vision track the
     * HEVC decoder is given as it is. Masks the sample's HDR10+ in place when it's hidden. Returns a line for the log
     * when what is done with the film's HDR10+ is first known or changes, else null.
     */
    fun sample(buffer: ByteBuffer, gate: Int, hybrid: Boolean): String? {
        if (hybrid && kept == 0) kept = HYBRID
        var why = if (gate != PASS) gate else kept
        // The numbers are read too, for the log, unless the TV has no HDR10+ at all: there, as ever, only masked.
        val read = if (gate == TV) null else values
        read?.clear()
        val n = Hdr10PlusMask.frame(buffer, write = why != PASS, read)
        // Not a frame as it's read here: it says nothing of the film's HDR10+.
        if (n == Hdr10PlusMask.NO_PICTURE) return null
        if (n == 0) {
            if (!found) {
                bare = true
                return null
            }
            without++
            if (why == PASS) {
                kept = GAPS
                why = GAPS
            }
        } else {
            // The film's first HDR10+ after frames without any: given to the decoder, it would switch the TV from
            // HDR10 to HDR10+ in the middle of the film, and back at the next frame without. Never given.
            if (!found && bare && why == PASS) {
                kept = GAPS
                why = GAPS
                Hdr10PlusMask.mask(buffer)
            }
            found = true
            with++
            if (read != null && read.read) {
                note(read)
                if (read.bad != 0) {
                    malformed++
                    if (why == PASS) {
                        kept = MALFORMED
                        why = MALFORMED
                        Hdr10PlusMask.mask(buffer)
                    }
                }
            }
        }
        masking = why != PASS
        if (why == told) return null
        told = why
        return when (why) {
            PASS -> "HDR10+ metadata in this video goes to the decoder as it is (the TV reports HDR10+)"
            TV -> "HDR10+ metadata in this video is masked before the decoder (the TV doesn't report HDR10+)"
            SETTING -> "HDR10+ metadata in this video is masked before the decoder (Settings > This TV: HDR10+ reaches " +
                "this TV is off): the TV gets steady HDR10"
            HYBRID -> "HDR10+ metadata in this video is masked before the decoder for the whole film (HDR10+ added to a " +
                "Dolby Vision file, on the HEVC decoder): the TV gets steady HDR10"
            MALFORMED -> "HDR10+ metadata in this video is malformed (${Hdr10PlusValues.words(values.bad)}): masked " +
                "from here to the film's end, the TV gets steady HDR10"
            else -> "HDR10+ metadata in this video isn't on every frame: masked from here to the film's end, the TV " +
                "gets steady HDR10"
        }
    }

    private fun note(v: Hdr10PlusValues) {
        if (v.bad and (Hdr10PlusValues.WINDOWS or Hdr10PlusValues.PEAK_TABLE) != 0) return
        if (v.odd != 0) odd++
        if (v.sum != lastSum) {
            lastSum = v.sum
            changes++
        }
        if (v.anchors >= 0) curves++
        val a = if (v.anchors < 0) 0 else v.anchors
        if (!ranged) {
            ranged = true
            minTarget = v.target; maxTarget = v.target
            minPeak = v.peak; maxPeak = v.peak
            minAverage = v.average; maxAverage = v.average
            minAnchors = a; maxAnchors = a
            return
        }
        if (v.target < minTarget) minTarget = v.target else if (v.target > maxTarget) maxTarget = v.target
        if (v.peak < minPeak) minPeak = v.peak else if (v.peak > maxPeak) maxPeak = v.peak
        if (v.average < minAverage) minAverage = v.average else if (v.average > maxAverage) maxAverage = v.average
        if (a < minAnchors) minAnchors = a else if (a > maxAnchors) maxAnchors = a
    }

    /**
     * The log's line on the last minute of a film with HDR10+, at [nowMs] (a clock that only goes forward): frames with
     * and without it, what was done, and its numbers' ranges. Null until a minute of it has gone by, and for a film
     * without HDR10+. Asked every few seconds of film, never per frame.
     */
    fun summary(nowMs: Long): String? {
        if (!found) return null
        if (sinceMs == 0L) {
            sinceMs = nowMs
            return null
        }
        if (nowMs - sinceMs < MINUTE_MS || with + without == 0) return null
        sinceMs = nowMs
        val did = when {
            !masking -> "sent to the decoder"
            told == TV -> "masked (the TV doesn't report HDR10+)"
            told == SETTING -> "masked (Settings > This TV)"
            told == HYBRID -> "masked (added to a Dolby Vision file)"
            told == MALFORMED -> "masked (malformed)"
            else -> "masked (not on every frame)"
        }
        val line = StringBuilder("HDR10+ this minute: ").append(with).append(" frames with it, ").append(without)
            .append(" without; ").append(did)
        if (ranged) {
            line.append("; target ").append(minTarget).append('-').append(maxTarget).append(" nits, peak ")
                .append(minPeak / 10).append('-').append(maxPeak / 10).append(" nits, average ")
                .append(minAverage / 10).append('-').append(maxAverage / 10).append(" nits, curve on ").append(curves)
                .append(" frames (").append(minAnchors).append('-').append(maxAnchors).append(" anchors), values changed ")
                .append(changes).append(" times, malformed ").append(malformed).append(", unusual ").append(odd)
        }
        resetCounts()
        return line.toString()
    }

    private fun resetCounts() {
        with = 0
        without = 0
        malformed = 0
        odd = 0
        changes = 0
        curves = 0
        ranged = false
    }

    companion object {
        /** HDR10+ goes to the decoder. */
        const val PASS = 0
        /** Hidden: the TV doesn't report HDR10+. */
        const val TV = 1
        /** Hidden: Settings › This TV › HDR10+ reaches this TV is off. */
        const val SETTING = 2
        // The three below are kept with a copy's measured facts ([MediaSpec.hdr10PlusHeld]): their numbers never
        // change.
        /** Hidden for the film: HDR10+ in a Dolby Vision track the HEVC decoder is given as it is. */
        const val HYBRID = 3
        /** Hidden for the rest of the film: a message broke HDR10+'s rules. */
        const val MALFORMED = 4
        /** Hidden for the rest of the film: its HDR10+ isn't on every frame. */
        const val GAPS = 5

        private const val MINUTE_MS = 60_000L
    }
}
