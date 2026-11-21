package app.fireplayer.player

import android.app.ActivityManager
import android.content.Context

/**
 * How much memory the TV has, in the steps the film's buffer and the skip preview are sized by. It's read from the
 * TV's total memory and whether Fire OS calls it a low-memory device, not from the app's heap limit: Fire OS gives
 * FirePlayer the same 384 MB heap limit on a Fire TV Stick with 1.7 GB in all as on a TV with 3 GB, though the Stick
 * can't spare it (seen: the Stick's low-memory killer looping while a 4K film held 230 MB of buffer, and the film
 * losing frames).
 */
enum class RamTier(
    /** The most the film's buffer holds, ahead and behind together (MiB). */
    val budgetMiB: Int,
    /** How much of the film is kept behind where it plays (ms), so going back a little plays at once. */
    val backBufferMs: Int,
    /**
     * The most memory what's kept behind may take (MiB; 0: no limit but [backBufferMs]). In a 4K remux at 84 Mb/s, 6 s
     * behind are 63 MB of a 128 MiB buffer, which then holds only 6 s ahead (see [BackBuffer]).
     */
    val backCapMiB: Int,
) {
    /** Fire OS's low-memory devices, and anything under 1.4 GiB. */
    TINY(64, FilmMemory.LEAST_BACK_BUFFER_MS, 16),
    /** Under 1.8 GiB, as the Fire TV Stick 4K Max (1.7 GB). */
    SMALL(128, 6_000, 24),
    /** Under 2.6 GiB: TVs sold as "2 GB". */
    MEDIUM(176, 10_000, 48),
    /** 2.6 GiB and up. */
    LARGE(256, 15_000, 0),
    ;

    /**
     * Short of memory for a second 4K picture: the skip preview keeps to pictures up to 1080p (a 4K-only film gets
     * none) and lets its player go 3 s after skipping stops instead of 15 s.
     */
    val short: Boolean get() = this != LARGE

    /** How long after the last skip the preview's player is kept, in case skipping starts again. */
    val previewHoldMs: Long get() = if (short) 3_000L else 15_000L

    companion object {
        private const val GIB = 1L shl 30

        /** The tier for a TV with [totalMemBytes] of memory in all; [lowRam] is Fire OS's isLowRamDevice. */
        fun of(totalMemBytes: Long, lowRam: Boolean): RamTier = when {
            lowRam -> TINY
            // Not reported (never seen): the largest tier, still held to the heap limit (FilmMemory.plan).
            totalMemBytes <= 0L -> LARGE
            totalMemBytes < (1.4 * GIB).toLong() -> TINY
            totalMemBytes < (1.8 * GIB).toLong() -> SMALL
            totalMemBytes < (2.6 * GIB).toLong() -> MEDIUM
            else -> LARGE
        }
    }
}

/**
 * What the film's buffer gets on this TV (see [FilmMemory.plan]): [budgetBytes] in all, [backBufferMs] of it behind
 * where the film plays, held to [backCapBytes] (0: no limit). [byFreeMemory]: the budget is below the tier's because
 * little memory was free when the film started; [tierBytes] is what it would be with enough free (the most it can be
 * raised to once room has been made, [FilmMemory.afterRoom]).
 */
data class FilmBufferPlan(
    val tier: RamTier,
    val budgetBytes: Int,
    val backBufferMs: Int,
    val backCapBytes: Long = 0L,
    val byFreeMemory: Boolean = false,
    val tierBytes: Int = budgetBytes,
)

/**
 * The film's buffer and the TV's memory: how big the buffer is on this TV, how far it steps down while Fire OS is short
 * of memory, and when it steps back up.
 */
object FilmMemory {
    /** No film buffer is smaller than this, squeezed or not. */
    const val FLOOR_BYTES = 48 shl 20
    /** Nor bigger than this, whatever the TV. */
    const val CEILING_BYTES = 400 shl 20
    /** The most of the app's heap limit the film's buffer may take: the rest is the app's own. */
    const val HEAP_SHARE = 0.6
    /** The least any TV keeps behind where the film plays. */
    const val LEAST_BACK_BUFFER_MS = 4_000
    /**
     * A squeezed buffer never stops loading with less than this ahead: what's kept behind can't be let go while the film
     * plays, so a cap lowered under it would leave nothing ahead and stall the film.
     */
    const val MIN_AHEAD_US = 5_000_000L

    /**
     * What is left free for everything but the film's buffer when the buffer is sized by the memory free at its start:
     * the decoder's own frames (5 to 8 frames of 4K 10-bit, 121 to 198 MB measured on a Fire TV Stick 4K Max) and room
     * for the rest of the TV.
     */
    const val RESERVE_BYTES = 260L shl 20
    /** Sized by free memory, the buffer still gets this much (it steps down further only under pressure, [capFor]). */
    const val FREE_FLOOR_BYTES = 64 shl 20

    /** One segment of a film read with two requests ([TwoRanges]); two are held at most. */
    const val SEGMENT_BYTES = 8 shl 20
    /** The same where the TV has the memory for two of them. */
    const val BIG_SEGMENT_BYTES = 16 shl 20

    /**
     * How big a segment of a film read with two requests is on a TV of [tier] with [freeBytes] free as the film starts
     * (0 or less: not known). Asking for the next segment costs each request about 98 ms (measured on a Fire TV Stick
     * 4K Max on connections used again: the request's way there and back, not a new connection), 6% of the 1.5 s an
     * 8 MiB segment takes at 45 Mb/s and 3% of a 16 MiB one's 3 s. Two segments are held in memory beside the film's
     * buffer, so the bigger ones cost 16 MiB more: only where what's free leaves [RESERVE_BYTES], the least film
     * buffer ([FREE_FLOOR_BYTES]) and both segments, and never on the smallest TVs.
     */
    fun segmentBytes(tier: RamTier, freeBytes: Long): Int = when {
        tier == RamTier.TINY -> SEGMENT_BYTES
        freeBytes in 1 until RESERVE_BYTES + FREE_FLOOR_BYTES + 2L * BIG_SEGMENT_BYTES -> SEGMENT_BYTES
        else -> BIG_SEGMENT_BYTES
    }

    /**
     * The film's buffer on a TV of [tier] whose app heap limit is [maxHeapBytes] (Runtime.maxMemory): the tier's
     * budget, but never more than [HEAP_SHARE] of the heap limit or [CEILING_BYTES], nor less than [FLOOR_BYTES].
     * Held under the tier's budget by a small heap limit, it keeps behind what the tier that budget fits keeps, or
     * the room ahead would shrink to a few seconds in a 4K film.
     *
     * [freeBytes] is the memory the TV has free as the film starts (ActivityManager's availMem; 0 or less: not known).
     * The buffer never takes more of it than leaves [RESERVE_BYTES], down to [FREE_FLOOR_BYTES]: the tier says what the
     * TV can spare at best, the free memory what it can spare now (seen: a 260 MB buffer and up to 198 MB of decoder
     * frames on a 1.7 GB Stick, with 76 kB of swap left).
     *
     * [keepBytes] is the budget of the player this one takes the place of, in the same moment (the same film in a
     * rebuilt player, another source in a new one; 0: none). That player's buffer has just been let go of, and
     * Android hasn't taken it back yet, so the free memory still reads as if it were held: this player gets at least
     * the budget that one had (never past the tier's), which is that same memory again.
     */
    fun plan(tier: RamTier, maxHeapBytes: Long, freeBytes: Long = 0L, keepBytes: Int = 0): FilmBufferPlan {
        val byTier = minOf(tier.budgetMiB.toLong() shl 20, (maxHeapBytes * HEAP_SHARE).toLong(), CEILING_BYTES.toLong())
            .coerceAtLeast(FLOOR_BYTES.toLong())
        val byFree = if (freeBytes > 0L) (freeBytes - RESERVE_BYTES).coerceAtLeast(minOf(FREE_FLOOR_BYTES.toLong(), byTier)) else byTier
        val budget = maxOf(minOf(byTier, byFree), minOf(byTier, keepBytes.toLong())).toInt()
        val fits = RamTier.entries.lastOrNull { (it.budgetMiB.toLong() shl 20) <= budget } ?: RamTier.TINY
        return FilmBufferPlan(
            tier, budget, minOf(tier.backBufferMs, fits.backBufferMs),
            // What's kept behind is held to the smaller of the two tiers' sizes, as its length is.
            backCapBytes = listOf(tier.backCapMiB, fits.backCapMiB).filter { it > 0 }.minOrNull()?.let { it.toLong() shl 20 } ?: 0L,
            byFreeMemory = budget < byTier,
            tierBytes = byTier.toInt(),
        )
    }

    /**
     * The film's budget once room has been made for it: [budgetBytes] is what it got when its player was built, held
     * under [tierBytes] by the memory free at that moment. That moment comes before the room is made: the player is
     * built as soon as the link is checked (about half a second after OK), while the page it was started from lets go
     * of its pictures and caches only once the player's screen covers it, and the film or episode before (Next
     * episode, a rebuilt player) has only just let go of its own buffer, which Android hasn't taken back yet. So the
     * free memory is read again when that memory is back ([FilmRoom]), by the same rule as at the start ([plan]: all
     * but [RESERVE_BYTES]), in two ways, the better of which counts:
     * - [freeBytes] free now, plus [heldBytes] the film's buffer already holds itself. Right until the film's decoder
     *   has taken its frames (they come out of the same memory, and the reserve is there for them), too low after.
     * - [startFreeBytes] free when the player was built, plus [gainedBytes] seen to come back since ([roomGained]):
     *   what would have been free then, had the room been made first. The decoder taking its frames before or after
     *   the room is made doesn't change it.
     *
     * Only ever more, and never past [tierBytes]: a reading that comes out lower changes nothing, and memory running
     * short later is [MemoryPressure]'s to answer. Gives back the budget from now on ([budgetBytes] when nothing
     * changes, or nothing is known).
     */
    fun afterRoom(
        budgetBytes: Int,
        tierBytes: Int,
        freeBytes: Long,
        heldBytes: Long,
        startFreeBytes: Long = 0L,
        gainedBytes: Long = 0L,
    ): Int {
        if (budgetBytes >= tierBytes) return budgetBytes
        val now = if (freeBytes > 0L) freeBytes + heldBytes.coerceAtLeast(0L) else 0L
        val atStart = if (startFreeBytes > 0L && gainedBytes > 0L) startFreeBytes + gainedBytes else 0L
        val free = maxOf(now, atStart)
        if (free <= 0L) return budgetBytes
        return minOf(tierBytes.toLong(), free - RESERVE_BYTES).toInt().coerceAtLeast(budgetBytes)
    }

    /**
     * How much more is free for a film once memory let go of was collected: what's free for it after
     * ([afterBytes]: the TV's free memory and what its own buffer holds) less the same before ([beforeBytes]). 0 when
     * either isn't known, or when less is free (something else took memory meanwhile: the film's decoder, another
     * app): only what was seen to come back counts.
     */
    fun roomGained(beforeBytes: Long, afterBytes: Long): Long =
        if (beforeBytes <= 0L || afterBytes <= 0L) 0L else (afterBytes - beforeBytes).coerceAtLeast(0L)

    /**
     * What the buffer fills to at squeeze [step]: 0 is the whole [budgetBytes], 1 three quarters (Fire OS says memory
     * is running low), 2 half (critical, or still low half a minute on); never below [FLOOR_BYTES].
     */
    fun capFor(budgetBytes: Int, step: Int): Int {
        val cap = when {
            step <= 0 -> budgetBytes
            step == 1 -> (budgetBytes.toLong() * 3 / 4).toInt()
            else -> budgetBytes / 2
        }
        return cap.coerceAtLeast(FLOOR_BYTES).coerceAtMost(budgetBytes)
    }

    /**
     * Whether what a squeezed buffer has given back should be collected now: at squeeze [step] (above 0) it holds
     * [heldBytes], [COLLECT_STEP_BYTES] or more under what it held when memory was last collected for it, or when it
     * was squeezed ([heldAtCollectBytes]).
     *
     * A buffer coming down to a smaller size loads nothing new, so the memory it lets go of as the film plays on is
     * only dropped, and nothing has Android collect it (it collects when enough new things were made): the TV would
     * stay as short of memory as before the squeeze, and half a minute on the squeeze would go a step further for it
     * ([MemoryPressure]). So FirePlayer has it collected itself ([FilmRoom.made]), a good piece at a time rather than
     * at every check: collecting looks at everything the app holds, which is work a TV short of memory is short of
     * too. Once the buffer is down and loading again, what it loads anew makes Android collect by itself.
     */
    fun givenBack(step: Int, heldBytes: Long, heldAtCollectBytes: Long): Boolean =
        step > 0 && heldAtCollectBytes - heldBytes >= COLLECT_STEP_BYTES

    /** A squeezed buffer's memory is collected each time it holds this much less ([givenBack]). */
    const val COLLECT_STEP_BYTES = 16L shl 20

    /**
     * How full the buffer may get before loading stops, with [aheadUs] loaded ahead: [capBytes], or the whole
     * [budgetBytes] while less than [MIN_AHEAD_US] is ahead.
     */
    fun fillLimit(capBytes: Int, budgetBytes: Int, aheadUs: Long): Int =
        if (aheadUs >= MIN_AHEAD_US) capBytes else maxOf(capBytes, budgetBytes)

    /** Less film than this loaded says too little about how much of it fits. */
    const val ROOM_MIN_US = 1_000_000L
    const val ROOM_MIN_BYTES = 4L shl 20
    /** After a stall the film never plays on with less than this ahead, however little fits. */
    const val STALL_WAIT_LEAST_US = 2_000_000L
    /** A wait leaves this much of what fits still to fill, or half of it where that is less. */
    const val WAIT_HEADROOM_US = 15_000_000L

    /**
     * How much of this film fits ahead of the picture, in µs: as it stood the last time the buffer was full
     * ([fullUs]; 0: it hasn't been yet), else worked out from what's loaded now ([aheadUs] of film in
     * [allocatedBytes], of [limitBytes]). 0 while there's too little to tell. An 80 Mb/s film in 128 MiB: 10 to 12 s.
     */
    fun roomUs(fullUs: Long, aheadUs: Long, allocatedBytes: Long, limitBytes: Long): Long {
        if (fullUs > 0) return fullUs
        if (aheadUs < ROOM_MIN_US || allocatedBytes < ROOM_MIN_BYTES || limitBytes <= 0) return 0L
        return (aheadUs.toDouble() * limitBytes / allocatedBytes).toLong()
    }

    /**
     * How much film to wait for before playing (on), when the rules ask for [wantedUs]: never more than what fits
     * ([roomUs]; 0: not known) less [WAIT_HEADROOM_US], or half of what fits where that's more, nor less than
     * [leastUs]. A cushion sized for a minute's smooth playing can't be had where 10 s fill the memory: waiting for
     * all of it kept a high-quality film on the loading ring for as long as the whole buffer takes to arrive, at
     * every start, jump and stall. Half is there in half the time, and the rest fills as it plays. A film of which
     * a minute or more fits waits as it always did.
     */
    fun waitUs(wantedUs: Long, roomUs: Long, leastUs: Long): Long =
        if (roomUs <= 0) wantedUs else minOf(wantedUs, maxOf(leastUs, roomUs / 2, roomUs - WAIT_HEADROOM_US))

    @Volatile private var tier: RamTier? = null

    /** This TV's tier, read once (its total memory doesn't change). */
    fun tier(context: Context): RamTier = tier ?: run {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val found = if (am == null) RamTier.LARGE else {
            val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            RamTier.of(info.totalMem, am.isLowRamDevice)
        }
        tier = found
        found
    }

    /** The memory the TV has free right now, in bytes (ActivityManager.MemoryInfo.availMem); 0 when it can't be read. */
    fun freeNow(context: Context): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 0L
        return runCatching { ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.availMem }.getOrDefault(0L)
    }

    /** Whether Fire OS says memory is low right now (ActivityManager.MemoryInfo.lowMemory). */
    fun lowNow(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        return ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.lowMemory
    }
}

/**
 * How far the film's buffer is squeezed for the TV's memory ([step]: 0 not at all, 1 to three quarters, 2 to half; see
 * [FilmMemory.capFor]). Fire OS asking the app to trim (onTrimMemory) goes straight to the step it calls for; its
 * "memory is low", read every 10 s, means step 1, and step 2 when still low [escalateMs] later. After [recoverMs]
 * without either, it steps back up one step, and another after the same again. Times are elapsedRealtime.
 */
class MemoryPressure(
    private val escalateMs: Long = 30_000L,
    private val recoverMs: Long = 120_000L,
) {
    var step = 0
        private set
    // When the latest run of "memory is low" readings began, or -1 when the last reading wasn't low.
    private var lowSince = -1L
    // When memory was last short (a trim asked for, or a low reading), or when the last step back up was taken.
    private var lastPressureAt = 0L

    /** Fire OS asked the app to trim, calling for [wanted] (1 running low, 2 critical). True when the step changed. */
    fun trimAsked(wanted: Int, nowMs: Long): Boolean {
        lastPressureAt = nowMs
        return raise(wanted)
    }

    /** The 10 s check: [low] is whether Fire OS says memory is low now. True when the step changed. */
    fun checked(low: Boolean, nowMs: Long): Boolean {
        if (low) {
            if (lowSince < 0) lowSince = nowMs
            lastPressureAt = nowMs
            return raise(if (nowMs - lowSince >= escalateMs) 2 else 1)
        }
        lowSince = -1L
        if (step == 0 || nowMs - lastPressureAt < recoverMs) return false
        step--
        lastPressureAt = nowMs
        return true
    }

    private fun raise(wanted: Int): Boolean {
        val next = wanted.coerceIn(0, 2)
        if (next <= step) return false
        step = next
        return true
    }
}
