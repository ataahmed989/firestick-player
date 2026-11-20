package app.fireplayer.player

/**
 * A film from the add-ons plays from a link its add-on can give again, afresh, at any time (Films & Shows; an IPTV
 * provider's link is the only one there is). Two things follow for the player:
 *
 * - A link that is [refused] isn't asked again: the answer would be the same, and every second of asking is a second
 *   on the loading ring before the fresh link is asked for.
 * - The film's connection gives up on a download gone quiet after [READ_TIMEOUT_MS], not 12 s: on a TV whose memory
 *   holds 10 to 12 s of a high-quality film, the new connection is then made while there is still film to play,
 *   where the longer wait always ended in a stall. Nothing loaded is lost by it.
 *
 * Pure.
 */
object FreshLinks {
    /** 401, 403: expired, or made for another address. 404, 410: gone. */
    val REFUSED = setOf(401, 403, 404, 410)

    const val READ_TIMEOUT_MS = 8_000

    /** Whether [httpStatus] says the link itself is refused or gone. */
    fun refused(httpStatus: Int?): Boolean = httpStatus != null && httpStatus in REFUSED
}
