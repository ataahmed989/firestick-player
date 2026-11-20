package app.fireplayer.vod.play

/**
 * What the player does when a film from the add-ons stops, past a stall's first aid ([StallAid]) and beside the failure
 * table's steps (design-brief.md §6.5): the two cases where asking the same link again and again is the wrong thing.
 *
 * - **A refused link** (401, 403: expired, or made for another address; 404, 410: gone). The same link gives the same
 *   answer, so it isn't asked again every second for a minute: the error goes straight to the failure table, which
 *   asks the add-on for a fresh link to the same file once, then tries the next source ([linkRefused]).
 * - **No network** (the Wi-Fi dropped). That's nothing against the source: no failure is counted, the picture stays
 *   with the loading ring and a line saying why, and the same link is opened again where the film stood as soon as
 *   the network is back ([netStep]); only after [NET_WAIT_MS] without it does the play stop with the usual words.
 *
 * Pure: no Android, no links.
 */
object Recovery {
    /** How often the network is looked at while it's waited for, ms. */
    const val NET_POLL_MS = 2_000L
    /** Back: the film is opened again this long after, once the Wi-Fi has its address. */
    const val NET_SETTLE_MS = 1_000L
    /** Without a network this long, the play stops with "No internet connection". */
    const val NET_WAIT_MS = 10 * 60_000L

    /** What the ring says while the network is waited for. */
    const val NET_WAITING = "No internet connection · playing again when it's back"

    /** Whether [httpStatus] on an add-on's link says the link itself is refused or gone: a fresh one is asked for. */
    fun linkRefused(httpStatus: Int?): Boolean = app.fireplayer.player.FreshLinks.refused(httpStatus)

    enum class NetStep { WAIT, PLAY, GIVE_UP }

    /** What to do [waitedMs] into a wait for the network, with the network there now ([online]) or not. */
    fun netStep(online: Boolean, waitedMs: Long): NetStep = when {
        online -> NetStep.PLAY
        waitedMs >= NET_WAIT_MS -> NetStep.GIVE_UP
        else -> NetStep.WAIT
    }
}
