package app.fireplayer.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs

/**
 * When words that change crossfade, and how long each half takes. Pure, for tests.
 *
 * A change fades the old words out quickly, swaps them unseen and fades the new ones in, landing softly: about a fifth
 * of a second in all. Changes that come faster than that (a held key's repeats) swap at once at whatever strength
 * the words have, so a long list scrolled with the key held never dims or lags behind; one that comes while the old
 * words are still fading out only replaces what will be swapped in.
 */
object SwapFadeRules {
    /** The old words going. */
    const val OUT_MS = 70L
    /** The new words coming. */
    const val IN_MS = 150L
    /** Changes closer together than this are a held key's: swapped at once. */
    const val RAPID_MS = 120L

    enum class Step {
        /** Swap now, at the strength the words have (a fade in under way carries on). */
        NOW,
        /** Fade the old words out, from the strength they have; they're swapped when gone. */
        OUT,
        /** The old words are fading out already: the newest change is what gets swapped in. */
        WAIT,
    }

    /**
     * What a change does: [fadingOut] while the old words are going, [sinceLastMs] since the change before it,
     * [shown] false when nobody can see it (the page is away): then it's just put in place.
     */
    fun step(fadingOut: Boolean, sinceLastMs: Long, shown: Boolean): Step = when {
        !shown -> Step.NOW
        fadingOut -> Step.WAIT
        sinceLastMs in 0 until RAPID_MS -> Step.NOW
        else -> Step.OUT
    }

    /** A fade of [fullMs] from nothing to full takes this long between [from] and [to] (it carries on from where it is). */
    fun span(fullMs: Long, from: Float, to: Float): Long = (fullMs * abs(to - from).coerceIn(0f, 1f)).toLong()
}

/**
 * A few views whose words change together ([views], each at its own resting strength [base]): [change] crossfades
 * them as [SwapFadeRules] says, calling [swap] to put the new words in while they can't be seen. Only their alpha
 * moves, on one animator made once: nothing is laid out, captured or allocated while it runs. A change mid-fade carries
 * on from the strength they have, and they always end at exactly their resting strength.
 */
class SwapFade(private val views: Array<View>, private val base: FloatArray, private val swap: () -> Unit) {
    private var level = 1f
    private var from = 1f
    private var to = 1f
    private var lastAt = Long.MIN_VALUE / 2
    private var cancelled = false

    /** The old words are still fading out: what shows isn't what was last asked for. */
    var fadingOut = false
        private set

    private val motion: ValueAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        addUpdateListener { a -> setLevel(from + (to - from) * a.animatedFraction) }
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationStart(animation: Animator) { cancelled = false }
            override fun onAnimationCancel(animation: Animator) { cancelled = true }
            override fun onAnimationEnd(animation: Animator) { if (!cancelled) arrived() }
        })
    }

    /** The words changed, at [now] (uptime); [shown] false puts them in place at once. */
    fun change(now: Long, shown: Boolean) {
        val step = SwapFadeRules.step(fadingOut, now - lastAt, shown)
        lastAt = now
        when (step) {
            SwapFadeRules.Step.WAIT -> Unit
            SwapFadeRules.Step.OUT -> { fadingOut = true; run(0f) }
            SwapFadeRules.Step.NOW -> if (shown) swap() else settle()
        }
    }

    /** Something small moved on in the same words (minutes left, a progress line): no fade, unless one will swap it in anyway. */
    fun refresh() {
        if (!fadingOut) swap()
    }

    /** The views were just laid out differently (their width changed): they come back in softly from nothing. */
    fun dip(shown: Boolean) {
        if (!shown) return
        if (fadingOut) return
        motion.cancel()
        setLevel(0f)
        run(1f)
    }

    /** One view's resting strength changed (a line hidden by its alpha). */
    fun setBase(index: Int, alpha: Float) {
        if (base[index] == alpha) return
        base[index] = alpha
        views[index].alpha = alpha * level
    }

    /** Everything in place at once, the newest words showing at full strength. */
    fun settle() {
        motion.cancel()
        fadingOut = false
        swap()
        setLevel(1f)
    }

    private fun run(target: Float) {
        motion.cancel()
        from = level
        to = target
        val ms = SwapFadeRules.span(if (target == 0f) SwapFadeRules.OUT_MS else SwapFadeRules.IN_MS, level, target)
        if (ms <= 0L) {
            setLevel(target)
            arrived()
            return
        }
        motion.duration = ms
        motion.interpolator = if (target == 0f) OUT else IN
        motion.start()
    }

    private fun arrived() {
        if (!fadingOut) return
        fadingOut = false
        swap()
        run(1f)
    }

    private fun setLevel(v: Float) {
        level = v
        for (i in views.indices) views[i].alpha = base[i] * v
    }

    private companion object {
        val OUT = AccelerateInterpolator(1.2f)
        val IN = DecelerateInterpolator(1.6f)
    }
}
