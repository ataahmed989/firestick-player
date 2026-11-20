package app.fireplayer.playback

/**
 * Multiview's connections: one lease per picture from the app's [ConnectionBudget], each under its own holder name, so
 * the pictures together never open more streams than each provider account allows. Pure (no Android): every call is
 * made on the main thread, as the budget's other users make theirs.
 */
class MultiviewLeases(private val budget: ConnectionBudget) {
    /** The account each picture's lease is held on, by picture id. */
    private val held = HashMap<Int, String>()

    /** Picture [id] asks for a stream on [account] (allowance [limit]). False: there's no room, nothing is held. */
    fun take(id: Int, account: String, limit: Int): Boolean {
        if (held[id] == account) return true
        drop(id)
        if (!budget.acquire(account, limit, holder(id))) return false
        held[id] = account
        return true
    }

    /**
     * Picture [id] moves to a channel on [account]: its own lease is given back first (it counts once), then asked for
     * on the new account. Refused, the old lease is taken back, so the picture keeps playing what it played.
     */
    fun swap(id: Int, account: String, limit: Int, oldLimit: Int): Boolean {
        val old = held[id]
        if (old == account) return true
        drop(id)
        if (budget.acquire(account, limit, holder(id))) {
            held[id] = account
            return true
        }
        if (old != null && budget.acquire(old, oldLimit, holder(id))) held[id] = old
        return false
    }

    fun holds(id: Int): Boolean = id in held

    /** Picture [id]'s lease goes back (it was removed, or its stream closed). */
    fun drop(id: Int) {
        val account = held.remove(id) ?: return
        budget.release(account, holder(id))
    }

    /** Every picture's lease goes back (Multiview closed, or the screen stopped). */
    fun dropAll() {
        for ((id, account) in held) budget.release(account, holder(id))
        held.clear()
    }

    companion object {
        fun holder(id: Int): String = "multiview-$id"
    }
}
