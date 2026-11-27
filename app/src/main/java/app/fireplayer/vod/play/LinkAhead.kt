package app.fireplayer.vod.play

import app.fireplayer.vod.Cancel
import app.fireplayer.vod.PlayRequest

/**
 * Resting on what OK would play, a Continue card or a title page's Play: after [REST_MS] on it, its play's link is checked
 * ahead ([app.fireplayer.vod.SourcesRepository.prepare]: the one copy OK would play, when it's cached), so OK starts
 * without waiting for the debrid service. Focus moving on, or the page going, drops it ([leave]). While focus moves
 * only a timer is set: the play isn't worked out, nor anything asked, until the rest is over. Main thread.
 */
class LinkAhead(private val ticker: Ticker, private val prepare: (PlayRequest) -> Cancel?) {
    private var key: String? = null
    private var make: (() -> PlayRequest?)? = null
    private var timer: Cancel? = null
    private var rested = false
    private var prep: Cancel? = null

    /**
     * Focus came to what OK would play, [key] (the card's or the Play target's): [make] is asked for its play once the
     * rest is over (null: nothing to prepare yet; [ready] asks again). The same [key] again changes nothing.
     */
    fun rest(key: String, make: () -> PlayRequest?) {
        if (key == this.key) return
        leave()
        this.key = key
        this.make = make
        timer = ticker.after(REST_MS) {
            timer = null
            rested = true
            ask()
        }
    }

    /** What OK would play may be known now (its add-ons answered): asked for at once when the rest is over. */
    fun ready() {
        if (rested && prep == null) ask()
    }

    /** Focus moved on, or the page went: the rest ends, and what was checked ahead is dropped (unless a play took it). */
    fun leave() {
        timer?.cancel()
        timer = null
        prep?.cancel()
        prep = null
        key = null
        make = null
        rested = false
    }

    private fun ask() {
        val request = make?.invoke() ?: return
        prep = prepare(request) ?: Cancel {}
    }

    companion object {
        /** How long focus rests on it first, ms: about a second, as agreed. */
        const val REST_MS = 1_000L
    }
}
