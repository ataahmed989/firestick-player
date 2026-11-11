package app.fireplayer.subs

import kotlin.math.log10

/**
 * Tells talking from quiet in the film's sound, one mark per 10 ms, for [SyncMatcher].
 *
 * It's fed a mono copy at 8,000 samples a second ([RATE]) and looks only at the voice band (about 300 to 3,400 Hz),
 * comparing each 10 ms against a running estimate of the background level: a frame clearly louder than what's around it
 * in the voice band counts as speech. Music and effects get through now and then, but they rarely line up with
 * subtitle lines over minutes of film, so the match still finds the right place. No model, no allocation per frame:
 * a few multiplications per sample, so a film's worth costs a fraction of a percent of one core.
 */
class SpeechDetector {

    // One-pole high-pass at about 300 Hz and low-pass at about 3,400 Hz (at 8 kHz).
    private var hpIn = 0f
    private var hpOut = 0f
    private var lpOut = 0f

    private var energy = 0.0
    private var count = 0

    // The background level in the voice band (dB): drops at once to a quieter frame, climbs slowly through loud ones.
    private var floorDb = Double.NaN
    // How loud the speech-like frames have been lately (dB), so a quiet scene isn't held to a loud scene's bar.
    private var peakDb = Double.NaN

    /** Starts over (after a jump in the film), keeping what it learned about how loud things are. */
    fun reset() {
        hpIn = 0f; hpOut = 0f; lpOut = 0f
        energy = 0.0; count = 0
    }

    /**
     * Takes [n] samples (-1..1) from [samples] and calls [frame] once per finished 10 ms with whether it sounded
     * like talking.
     */
    inline fun push(samples: FloatArray, n: Int, frame: (Boolean) -> Unit) {
        for (i in 0 until n) {
            val done = add(samples[i])
            if (done != NONE) frame(done == SPEECH)
        }
    }

    /** Adds one sample; [NONE] until a 10 ms frame is complete, then [SPEECH] or [QUIET]. */
    fun add(sample: Float): Int {
        val hp = HP * (hpOut + sample - hpIn)
        hpIn = sample
        hpOut = hp
        lpOut += LP * (hp - lpOut)
        energy += (lpOut * lpOut).toDouble()
        if (++count < FRAME) return NONE
        val db = 10 * log10(energy / FRAME + 1e-12)
        energy = 0.0
        count = 0
        return if (judge(db)) SPEECH else QUIET
    }

    private fun judge(db: Double): Boolean {
        if (floorDb.isNaN()) {
            floorDb = db
            peakDb = db
            return false
        }
        floorDb = if (db < floorDb) floorDb + (db - floorDb) * 0.3 else floorDb + FLOOR_RISE_DB
        peakDb = if (db > peakDb) db else peakDb - PEAK_FALL_DB
        // Halfway between the background and the recent loud parts, and at least 9 dB over the background.
        val bar = maxOf(floorDb + 9.0, (floorDb + peakDb) / 2)
        return db > bar && db > SILENCE_DB
    }

    companion object {
        const val RATE = 8_000
        const val FRAME = 80 // 10 ms
        const val NONE = 0
        const val QUIET = 1
        const val SPEECH = 2
        private const val HP = 0.81f // ~300 Hz
        private const val LP = 0.73f // ~3,400 Hz
        private const val FLOOR_RISE_DB = 0.02 // per 10 ms: 2 dB a second
        private const val PEAK_FALL_DB = 0.01 // per 10 ms: 1 dB a second
        private const val SILENCE_DB = -62.0
    }
}

/**
 * Turns the film's sound (any rate, mono to 7.1, as 16-bit or float samples) into the 8 kHz mono [SpeechDetector]
 * listens to. With a centre channel (5.1, 7.1) it's mostly the centre, where films put the dialogue, so music and
 * effects in the other speakers get in the way less. Averages each stretch of samples into one, which is enough
 * filtering for telling talk from quiet.
 */
class MonoDownsampler(val inRate: Int, val channels: Int) {
    private var sum = 0f
    private var count = 0
    private var pos = 0
    private val out = FloatArray(1024)
    private var n = 0

    /** Adds [frames] sample frames read by [sample] (channel, frame → -1..1); full chunks go to [emit]. */
    inline fun push(frames: Int, sample: (frame: Int, channel: Int) -> Float, emit: (FloatArray, Int) -> Unit) {
        for (f in 0 until frames) {
            val v = when {
                channels >= 3 -> 0.7f * sample(f, 2) + 0.15f * (sample(f, 0) + sample(f, 1))
                channels == 2 -> 0.5f * (sample(f, 0) + sample(f, 1))
                else -> sample(f, 0)
            }
            if (add(v)) emit(buffer(), takeCount())
        }
    }

    /** Adds one mono sample; true when [buffer] is full and should be taken. */
    fun add(v: Float): Boolean {
        sum += v
        count++
        pos += SpeechDetector.RATE
        if (pos < inRate) return false
        pos -= inRate
        out[n++] = sum / count
        sum = 0f
        count = 0
        return n == out.size
    }

    fun buffer(): FloatArray = out

    /** How many samples are waiting in [buffer], and empties it. */
    fun takeCount(): Int = n.also { n = 0 }
}
