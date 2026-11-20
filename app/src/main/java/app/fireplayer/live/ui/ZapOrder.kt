package app.fireplayer.live.ui

import app.fireplayer.live.ChannelKey
import app.fireplayer.live.GuideScope

/**
 * Which channel the remote's Channel ▲ / ▼ (and, with "D-pad + channel buttons", Up / Down in full screen) goes to:
 * the playing channel's group only (TUNING-1.md section 4), in the order the guide shows that group.
 */
object ZapOrder {

    /**
     * The index [delta] places on from [from] in a list of [size] whose [from] sits at [cur] (-1 when it isn't there:
     * counted from the first, as before), round at the ends. -1 for an empty list.
     */
    fun nextIndex(size: Int, cur: Int, delta: Int): Int {
        if (size <= 0) return -1
        return Math.floorMod((if (cur < 0) 0 else cur) + delta, size)
    }

    /** The item [delta] on from the one keyed [from] in [list] (see [nextIndex]); null for an empty list. */
    fun <T> next(list: List<T>, from: ChannelKey?, delta: Int, keyOf: (T) -> ChannelKey): T? {
        val cur = if (from == null) -1 else list.indexOfFirst { keyOf(it) == from }
        return list.getOrNull(nextIndex(list.size, cur, delta))
    }

    /**
     * The lists to look for a channel's group in, first first: the one it was started from ([startedFrom]: a guide
     * list), else the one it was last played from ([lastFrom]), then its own group in its playlist, then its
     * playlist's All channels (its group may be hidden). The first that still holds the channel is its group.
     */
    fun candidates(startedFrom: GuideScope?, lastFrom: GuideScope?, playlistId: String, groupId: String?): List<GuideScope> =
        listOfNotNull(
            startedFrom ?: lastFrom,
            groupId?.let { GuideScope.Group(playlistId, it) },
            GuideScope.AllChannels(playlistId),
        ).distinct()
}
