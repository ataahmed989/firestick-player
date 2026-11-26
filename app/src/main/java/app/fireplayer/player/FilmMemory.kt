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
 * raised to once room has been made, [FilmMemory.afterRoom], or grown to once memory has been quiet,
 * [FilmMemory.grown]). [spanExtraBytes]: what the second request's long spans hold beyond its usual segments, given
 * up by the buffer ([FilmMemory.withSecondRequest]).
 *
 * [reserveBytes] is what the start left free of the TV's memory for everything but the buffer, and [lowMarkBytes]
 * the free memory under which Fire OS calls memory low (0: this plan doesn't go by it). [floorBytes]: a buffer held
 * down never starts with less ([FilmMemory.floorBytes]; 0: not held down), which is [aheadFloorUs] of this film
 * ahead when its rate is known. [startBy] says which of them set the size. [leanDownload]: so little was free that
 * the download keeps to short spans and narrow sockets ([FilmMemory.longSpansFit], [FilmSockets.lowMemory]).
 */
data class FilmBufferPlan(
    val tier: RamTier,
    val budgetBytes: Int,
    val backBufferMs: Int,
    val backCapBytes: Long = 0L,
    val byFreeMemory: Boolean = false,
    val tierBytes: Int = budgetBytes,
    val spanExtraBytes: Int = 0,
    val reserveBytes: Long = FilmMemory.RESERVE_BYTES,
    val lowMarkBytes: Long = 0L,
    val floorBytes: Int = 0,
    val aheadFloorUs: Long = 0L,
    val startBy: StartBy = StartBy.TIER,
    val leanDownload: Boolean = byFreeMemory,
) {
    /** What set the size a film's buffer starts with. */
    enum class StartBy {
        /** The TV's own size for it: enough memory was free. */
        TIER,
        /** The memory free at the start, less what's left for the decoder and Fire OS. */
        FREE,
        /** The least a buffer held down starts with ([FilmMemory.FREE_FLOOR_BYTES]). */
        FLOOR,
        /** Enough of this film ahead to ride out a dip in the connection ([FilmMemory.aheadFloorUs]). */
        AHEAD,
        /** The memory of the player it takes the place of. */
        KEPT,
    }
}

/** The TV's memory as Fire OS reports it right now ([FilmMemory.now]); 0 where it can't be read. */
class MemoryNow(
    /** Free, in bytes (ActivityManager.MemoryInfo.availMem). */
    val freeBytes: Long,
    /** Fire OS says memory is low (MemoryInfo.lowMemory). */
    val low: Boolean,
    /** The free memory under which it says so (MemoryInfo.threshold). */
    val lowMarkBytes: Long,
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
     * for the rest of the TV. The rule of TVs with 2.6 GiB and more; the smaller ones leave more ([startReserve]).
     */
    const val RESERVE_BYTES = 260L shl 20
    /** Sized by free memory, the buffer still gets this much (it steps down further only under pressure, [capFor]). */
    const val FREE_FLOOR_BYTES = 64 shl 20

    // ---- The start on a TV short of memory ([RamTier.short]): what a 4K film takes besides its buffer, and what
    // Fire OS needs left over. Measured on the Fire TV Sticks (1.7 GB), FirePlayer 1.0.366:
    // - 397 MiB free, a 112 MiB buffer and 16 MiB of long spans: Fire OS's low-memory killer 3.7 s after the start,
    //   about 15 processes killed in 30 s, swap at 0 kB and thrashing, 97 frames lost or shown late in 105 s.
    // - 540 MiB free, the same buffer: one background app killed, the buffer squeezed to 48 MiB, 4 frames dropped.
    // RESERVE_BYTES leaves 60 MiB over the decoder's 200: under the mark at which Fire OS calls memory low, so a
    // buffer it let through at full size (anything from 388 MiB free) put the TV there by itself.

    /** The decoder's frames for a 4K film: up to 198 MB measured, taken in the film's first seconds. */
    const val DECODER_BYTES = 200L shl 20
    /**
     * What the player takes besides its buffer as a film starts: the second request's two segments (16 MiB) and its
     * own (the app's memory peaked at 320 MB with a 112 MiB buffer and 32 MiB of spans, from 110 to 136 MB before).
     */
    const val OWN_BYTES = 64L shl 20
    /** Left over Fire OS's low mark after a start or a step up: two steps' worth, so neither lands on the mark. */
    const val ROOM_MARGIN_BYTES = 32L shl 20
    /**
     * The free memory under which Fire OS calls memory low, when it doesn't say (MemoryInfo.threshold): Android's own
     * figure for a 1080p screen (147,456 kB). What it says is held between [LOW_MARK_LEAST_BYTES] and
     * [LOW_MARK_MOST_BYTES], so an odd figure neither starves the buffer nor lets it at the TV's last memory.
     */
    const val LOW_MARK_BYTES = 144L shl 20
    const val LOW_MARK_LEAST_BYTES = 96L shl 20
    const val LOW_MARK_MOST_BYTES = 256L shl 20

    /**
     * A buffer below its TV's size grows by this much at a time ([grown]): an eighth of a Stick's 128 MiB, the size of
     * its squeeze's steps, and 2.3 s of a 58 Mb/s film.
     */
    const val GROW_STEP_BYTES = 16 shl 20

    /**
     * A buffer held down by free memory still holds this much of the film ahead: [ROOMY_AHEAD_US] on a line measured
     * to bring the film [ROOMY_LINE] times as fast as it plays or faster, [THIN_AHEAD_US] on a thinner one, or one not
     * measured. What was measured is the 5 s a squeezed buffer keeps ([MIN_AHEAD_US]): it held on both Sticks without a
     * stall (a 58 Mb/s film on 83 to 92 Mb/s, a 35 to 40 Mb/s one) and is the least seen to hold, while a 69 Mb/s film
     * on 65 to 82 Mb/s stalled every 6 to 16 s with it. A start isn't under pressure yet, so it keeps over that least:
     * a second where the line has room, three where it's thin. Three more seconds of the Stick's 58 Mb/s film make its
     * buffer 72 MiB, still under the 80 MiB the same Stick was back at four minutes later, with no trouble seen after.
     */
    const val ROOMY_AHEAD_US = 6_000_000L
    const val THIN_AHEAD_US = 8_000_000L
    /** The room on the line a remux's busy scenes need ([app.fireplayer.vod.sources.LineRate.REMUX_ROOM]). */
    const val ROOMY_LINE = 1.5f

    /** Fire OS's low mark as the sizes use it: [lowMarkBytes] as it says it (0 or less: not said), held to sane bounds. */
    fun lowMark(lowMarkBytes: Long): Long =
        if (lowMarkBytes <= 0L) LOW_MARK_BYTES else lowMarkBytes.coerceIn(LOW_MARK_LEAST_BYTES, LOW_MARK_MOST_BYTES)

    /**
     * What a film's start leaves free on a TV short of memory, for everything but its buffer: the decoder's frames
     * ([DECODER_BYTES]), the player's own ([OWN_BYTES]), Fire OS's low mark and [ROOM_MARGIN_BYTES] over it. 440 MiB
     * at the usual mark: 397 MiB free leaves the buffer nothing but its floor, 540 leaves it 100 MiB, and from 568 a
     * Stick's buffer is its whole 128 MiB, as before.
     */
    fun startReserve(lowMarkBytes: Long): Long = DECODER_BYTES + OWN_BYTES + lowMark(lowMarkBytes) + ROOM_MARGIN_BYTES

    /**
     * How much of the film a buffer held down keeps ahead at the least, in µs, on a line bringing it [fillRate] times
     * as fast as it plays (null: not measured). See [ROOMY_AHEAD_US].
     */
    fun aheadFloorUs(fillRate: Float?): Long =
        if (fillRate != null && fillRate >= ROOMY_LINE) ROOMY_AHEAD_US else THIN_AHEAD_US

    /**
     * The least a buffer held down by free memory starts with for a film of [rateMbps] (its size over its length; 0 or
     * less: not known) on a line measured to bring [lineMbps] (0 or less: not measured): [aheadFloorUs] of it ahead and
     * what's kept behind ([backBufferMs] of it, up to [backCapBytes]; 0: no limit), in whole MiB. 0 when the film's rate
     * isn't known: [FREE_FLOOR_BYTES] is the floor then, and the same length ahead is kept as the film loads
     * ([fillLimit]). A 58 Mb/s film on an 83 Mb/s line with 4 s behind up to 16 MiB: 8 s ahead, 72 MiB.
     */
    fun floorBytes(rateMbps: Double, lineMbps: Double, backBufferMs: Int, backCapBytes: Long): Int {
        if (rateMbps <= 0.0 || rateMbps.isNaN() || rateMbps.isInfinite()) return 0
        val perSecond = rateMbps * 1_000_000.0 / 8.0
        val behind = (perSecond * backBufferMs / 1000.0).let { if (backCapBytes > 0L) minOf(it, backCapBytes.toDouble()) else it }
        val aheadUs = aheadFloorUs(if (lineMbps > 0.0) (lineMbps / rateMbps).toFloat() else null)
        val bytes = perSecond * aheadUs / 1_000_000.0 + behind
        val mib = 1L shl 20
        return (((bytes.toLong() + mib - 1) / mib) * mib).coerceAtMost(CEILING_BYTES.toLong()).toInt()
    }

    /**
     * The film's buffer on a TV of [tier] whose app heap limit is [maxHeapBytes] (Runtime.maxMemory): the tier's
     * budget, but never more than [HEAP_SHARE] of the heap limit or [CEILING_BYTES], nor less than [FLOOR_BYTES].
     * Held under the tier's budget by a small heap limit, it keeps behind what the tier that budget fits keeps, or
     * the room ahead would shrink to a few seconds in a 4K film.
     *
     * [freeBytes] is the memory the TV has free as the film starts (ActivityManager's availMem; 0 or less: not known).
     * The tier says what the TV can spare at best, the free memory what it can spare now.
     * - A TV of 2.6 GiB and more: the buffer never takes more of it than leaves [RESERVE_BYTES], down to
     *   [FREE_FLOOR_BYTES] (seen: a 260 MB buffer and up to 198 MB of decoder frames on a 1.7 GB Stick, with 76 kB of
     *   swap left). As ever; nothing else here applies to it.
     * - A TV short of memory ([RamTier.short]): it never takes more than leaves [startReserve], which counts Fire OS's
     *   low mark too ([lowMarkBytes]: MemoryInfo.threshold; 0: not said). With that much free and more, the buffer is
     *   the tier's, as ever. With less it starts smaller and grows back with calm ([grown]): never under
     *   [FREE_FLOOR_BYTES], nor under what keeps enough of this film ahead ([floorBytes], from [rateMbps] and
     *   [lineMbps]; 0: not known).
     *
     * The download (long spans, the sockets' windows) goes by the first rule on every TV, as before
     * ([FilmBufferPlan.leanDownload]): what the connection is measured to bring doesn't move with the buffer's start.
     *
     * [keepBytes] is the budget of the player this one takes the place of, in the same moment (the same film in a
     * rebuilt player, another source in a new one; 0: none). That player's buffer has just been let go of, and
     * Android hasn't taken it back yet, so the free memory still reads as if it were held: this player gets at least
     * the budget that one had (never past the tier's), which is that same memory again.
     */
    fun plan(
        tier: RamTier,
        maxHeapBytes: Long,
        freeBytes: Long = 0L,
        keepBytes: Int = 0,
        lowMarkBytes: Long = 0L,
        rateMbps: Double = 0.0,
        lineMbps: Double = 0.0,
    ): FilmBufferPlan {
        val byTier = minOf(tier.budgetMiB.toLong() shl 20, (maxHeapBytes * HEAP_SHARE).toLong(), CEILING_BYTES.toLong())
            .coerceAtLeast(FLOOR_BYTES.toLong())
        val least = minOf(FREE_FLOOR_BYTES.toLong(), byTier)
        val kept = minOf(byTier, keepBytes.toLong())
        // As ever: all but the decoder's reserve.
        val everFree = if (freeBytes > 0L) minOf(byTier, (freeBytes - RESERVE_BYTES).coerceAtLeast(least)) else byTier
        val asEver = maxOf(everFree, kept)
        val lean = asEver < byTier
        if (!tier.short || freeBytes <= 0L) {
            val by = when {
                asEver >= byTier -> FilmBufferPlan.StartBy.TIER
                kept > everFree -> FilmBufferPlan.StartBy.KEPT
                everFree > least -> FilmBufferPlan.StartBy.FREE
                else -> FilmBufferPlan.StartBy.FLOOR
            }
            return planOf(tier, asEver, byTier, lean, by)
        }
        val mark = lowMark(lowMarkBytes)
        val reserve = startReserve(lowMarkBytes)
        val byFree = minOf(byTier, (freeBytes - reserve).coerceAtLeast(least))
        if (maxOf(byFree, kept) >= byTier) return planOf(tier, byTier, byTier, lean, FilmBufferPlan.StartBy.TIER, reserve, mark)
        // Held down: what's kept behind goes by the size it comes to, and the floor by what's kept behind (at most
        // one round for each tier).
        var budget = maxOf(byFree, kept)
        var by = when {
            kept > byFree -> FilmBufferPlan.StartBy.KEPT
            byFree > least -> FilmBufferPlan.StartBy.FREE
            else -> FilmBufferPlan.StartBy.FLOOR
        }
        var floor = least
        var aheadUs = 0L
        for (round in RamTier.entries) {
            val fits = fitting(budget)
            val forFilm = floorBytes(rateMbps, lineMbps, minOf(tier.backBufferMs, fits.backBufferMs), backCap(tier, fits)).toLong()
            if (forFilm <= 0L) break
            aheadUs = aheadFloorUs(if (lineMbps > 0.0) (lineMbps / rateMbps).toFloat() else null)
            val next = minOf(byTier, forFilm)
            floor = maxOf(least, next)
            if (next <= budget) break
            budget = next
            by = if (next >= byTier) FilmBufferPlan.StartBy.TIER else FilmBufferPlan.StartBy.AHEAD
        }
        return planOf(tier, budget, byTier, lean, by, reserve, mark, floor.toInt(), aheadUs)
    }

    /** The tier whose budget [budgetBytes] fits: what's kept behind goes by it. */
    private fun fitting(budgetBytes: Long): RamTier =
        RamTier.entries.lastOrNull { (it.budgetMiB.toLong() shl 20) <= budgetBytes } ?: RamTier.TINY

    /** What's kept behind is held to the smaller of the two tiers' sizes, as its length is; 0: no limit. */
    private fun backCap(tier: RamTier, fits: RamTier): Long =
        listOf(tier.backCapMiB, fits.backCapMiB).filter { it > 0 }.minOrNull()?.let { it.toLong() shl 20 } ?: 0L

    private fun planOf(
        tier: RamTier, budget: Long, byTier: Long, lean: Boolean, by: FilmBufferPlan.StartBy,
        reserve: Long = RESERVE_BYTES, mark: Long = 0L, floor: Int = 0, aheadUs: Long = 0L,
    ): FilmBufferPlan {
        val fits = fitting(budget)
        val held = budget < byTier
        return FilmBufferPlan(
            tier, budget.toInt(), minOf(tier.backBufferMs, fits.backBufferMs),
            backCapBytes = backCap(tier, fits),
            byFreeMemory = held,
            tierBytes = byTier.toInt(),
            reserveBytes = reserve,
            lowMarkBytes = mark,
            floorBytes = if (held) floor else 0,
            aheadFloorUs = if (held) aheadUs else 0L,
            startBy = by,
            leanDownload = lean,
        )
    }

    /**
     * The film's budget once room has been made for it: [budgetBytes] is what it got when its player was built, held
     * under [tierBytes] by the memory free at that moment. That moment comes before the room is made: the player is
     * built as soon as the link is checked (about half a second after OK), while the page it was started from lets go
     * of its pictures and caches only once the player's screen covers it, and the film or episode before (Next
     * episode, a rebuilt player) has only just let go of its own buffer, which Android hasn't taken back yet. So the
     * free memory is read again when that memory is back ([FilmRoom]), by the same rule as at the start ([plan]: all
     * but [reserveBytes], the plan's [FilmBufferPlan.reserveBytes]), in two ways, the better of which counts:
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
        reserveBytes: Long = RESERVE_BYTES,
    ): Int {
        if (budgetBytes >= tierBytes) return budgetBytes
        val now = if (freeBytes > 0L) freeBytes + heldBytes.coerceAtLeast(0L) else 0L
        val atStart = if (startFreeBytes > 0L && gainedBytes > 0L) startFreeBytes + gainedBytes else 0L
        val free = maxOf(now, atStart)
        if (free <= 0L) return budgetBytes
        return minOf(tierBytes.toLong(), free - reserveBytes).toInt().coerceAtLeast(budgetBytes)
    }

    /**
     * The film's budget a step on, once memory has been quiet ([MemoryPressure.mayGrow]): a buffer started below its
     * TV's size ([FilmBufferPlan.byFreeMemory]) gets [GROW_STEP_BYTES] more, up to [tierBytes], when the TV has the
     * room for it now ([growNeed]). [budgetBytes] when it has none, when nothing is known of its memory ([freeBytes] 0
     * or less), or when it's at its most. Steps, not the whole way: each one is taken only after the last has been
     * seen to do no harm (see [MemoryPressure]).
     */
    fun grown(budgetBytes: Int, tierBytes: Int, freeBytes: Long, heldBytes: Long, lowMarkBytes: Long): Int {
        if (budgetBytes >= tierBytes || freeBytes <= 0L) return budgetBytes
        if (freeBytes < growNeed(budgetBytes, tierBytes, heldBytes, lowMarkBytes)) return budgetBytes
        return minOf(tierBytes.toLong(), budgetBytes.toLong() + GROW_STEP_BYTES).toInt()
    }

    /**
     * The free memory a step up takes ([grown]), in bytes; 0 when the buffer is at its most. The step itself, what the
     * buffer has yet to fill of its present size ([budgetBytes] less the [heldBytes] it holds: that memory is still to
     * be taken), and Fire OS's low mark with [ROOM_MARGIN_BYTES] over it left free after. By now the decoder has its
     * frames, so they aren't counted again. A Stick at 80 MiB, full, with the usual mark: 192 MiB.
     */
    fun growNeed(budgetBytes: Int, tierBytes: Int, heldBytes: Long, lowMarkBytes: Long): Long {
        if (budgetBytes >= tierBytes) return 0L
        val step = minOf(tierBytes.toLong() - budgetBytes, GROW_STEP_BYTES.toLong())
        val unfilled = (budgetBytes - heldBytes.coerceAtLeast(0L)).coerceAtLeast(0L)
        return step + unfilled + lowMark(lowMarkBytes) + ROOM_MARGIN_BYTES
    }

    /**
     * How much more is free for a film once memory let go of was collected: what's free for it after
     * ([afterBytes]: the TV's free memory and what its own buffer holds) less the same before ([beforeBytes]). 0 when
     * either isn't known, or when less is free (something else took memory meanwhile: the film's decoder, another
     * app): only what was seen to come back counts.
     */
    fun roomGained(beforeBytes: Long, afterBytes: Long): Long =
        if (beforeBytes <= 0L || afterBytes <= 0L) 0L else (afterBytes - beforeBytes).coerceAtLeast(0L)

    /** The squeeze's last step: half given back ([capFor]). */
    const val STEPS = 4

    /**
     * What the buffer fills to at squeeze [step], which counts the eighths given back: 0 is the whole [budgetBytes],
     * 2 three quarters, [STEPS] (and anything past it) half; never below [FLOOR_BYTES]. [extraBytes] that the second
     * request's long spans hold ([FilmBufferPlan.spanExtraBytes]) count with the budget and come off what it fills to,
     * so the two together are squeezed as the buffer alone was.
     */
    fun capFor(budgetBytes: Int, step: Int, extraBytes: Int = 0): Int {
        val extra = extraBytes.coerceAtLeast(0)
        val whole = budgetBytes.toLong() + extra
        val cap = (whole * (8 - step.coerceIn(0, STEPS)) / 8 - extra).toInt()
        return cap.coerceAtLeast(FLOOR_BYTES).coerceAtMost(budgetBytes)
    }

    /**
     * Whether the second request may read long spans ([TwoRanges.longSpans]) beside a film buffer of [plan]: not on the
     * smallest TVs, nor when so little memory was free as the film started that every TV's rule held the buffer down
     * ([FilmBufferPlan.leanDownload]; spans stay short there).
     */
    fun longSpansFit(plan: FilmBufferPlan): Boolean = plan.tier != RamTier.TINY && !plan.leanDownload

    /**
     * [plan], with the second request's long spans holding [extraBytes] more than its usual segments: the film's buffer
     * gives that up (its budget, and the most it can be raised to), so the two hold no more memory together than
     * before. What's kept behind stays as planned. Never under [FLOOR_BYTES], and a buffer held down by free memory
     * never under its floor ([FilmBufferPlan.floorBytes]): the spans aren't counted in what's loaded ahead.
     */
    fun withSecondRequest(plan: FilmBufferPlan, extraBytes: Long): FilmBufferPlan {
        if (extraBytes <= 0L) return plan
        val extra = extraBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val tier = (plan.tierBytes - extra).coerceAtLeast(FLOOR_BYTES)
        val floor = minOf(plan.floorBytes, tier)
        val budget = (plan.budgetBytes - extra).coerceAtLeast(maxOf(FLOOR_BYTES, floor))
        return plan.copy(
            budgetBytes = budget,
            tierBytes = tier,
            spanExtraBytes = extra,
            byFreeMemory = plan.byFreeMemory && budget < tier,
            floorBytes = floor,
        )
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
     * How full the buffer may get before loading stops, with [aheadUs] loaded ahead: [capBytes], but
     * - with less than [MIN_AHEAD_US] ahead, all the TV spares at best ([tierBytes], and never less than
     *   [budgetBytes]), squeezed or not;
     * - not squeezed ([capBytes] is the budget), with less than [floorUs] ahead, the same: a buffer started small for
     *   want of free memory still holds that much of a heavy film ([aheadFloorUs]).
     * So a size under the tier's only limits what is held beyond those seconds. On a TV whose buffer is the tier's
     * ([tierBytes] is [budgetBytes], as it is by default), this is the budget while less than [MIN_AHEAD_US] is ahead
     * and [capBytes] otherwise, as ever.
     */
    fun fillLimit(capBytes: Int, budgetBytes: Int, aheadUs: Long, tierBytes: Int = budgetBytes, floorUs: Long = MIN_AHEAD_US): Int = when {
        aheadUs < MIN_AHEAD_US -> maxOf(capBytes, budgetBytes, tierBytes)
        capBytes >= budgetBytes && aheadUs < floorUs -> maxOf(capBytes, tierBytes)
        else -> capBytes
    }

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
     * How much of this film fits ahead in a stall, in µs, worked out from the bytes held now (every check, so a squeeze
     * that has lifted or a buffer that never filled counts as it is): [allocatedBytes] hold the [aheadUs] loaded ahead
     * and the [behindUs] kept behind, so here the film takes allocated / (ahead + behind) bytes a µs, and [fillBytes]
     * hold that much film less what stays behind. 0 while there's too little to tell. An 80 Mb/s film with 2 s kept
     * behind in 128 MiB: about 11 s.
     */
    fun roomInStallUs(aheadUs: Long, behindUs: Long, allocatedBytes: Long, fillBytes: Long): Long {
        val behind = behindUs.coerceAtLeast(0L)
        val span = aheadUs.coerceAtLeast(0L) + behind
        if (span < ROOM_MIN_US || allocatedBytes < ROOM_MIN_BYTES || fillBytes <= 0L) return 0L
        return ((span.toDouble() * fillBytes / allocatedBytes).toLong() - behind).coerceAtLeast(0L)
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

    /**
     * The TV's memory right now, in one question to Fire OS: what's free, whether it calls that low, and under how
     * much it does. All zero (and not low) when it can't be read.
     */
    fun now(context: Context): MemoryNow {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return MemoryNow(0L, false, 0L)
        return runCatching {
            val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            MemoryNow(info.availMem, info.lowMemory, info.threshold)
        }.getOrDefault(MemoryNow(0L, false, 0L))
    }

    /**
     * The start of a film's buffer, for the log (no links, no titles). Wherever enough memory was free, the line it
     * always was: "Film buffer: SMALL memory, 600 MiB free, 112 MiB, 6 s kept behind up to 24 MiB, 16 MiB given to the
     * second request's long spans". Held down on a TV short of memory, with why and what it grows to:
     * "Film buffer: SMALL memory, 397 MiB free (Fire OS calls it low under 144 MiB, swap 0 of 819 MiB free), film about
     * 58 Mb/s on a connection bringing 83: starts at 72 MiB, for 8 s of it ahead (free memory leaves no more: 440 MiB
     * are left for the decoder and Fire OS), up to 112 MiB once memory has been quiet, 4 s kept behind up to 16 MiB, ...".
     */
    fun startWords(plan: FilmBufferPlan, freeBytes: Long, rateMbps: Double, lineMbps: Double, swap: () -> String? = ::swapNow): String {
        val budget = plan.budgetBytes
        val line = StringBuilder(320).append("Film buffer: ").append(plan.tier).append(" memory, ").append(freeBytes shr 20).append(" MiB free")
        if (plan.byFreeMemory && plan.lowMarkBytes > 0L) {
            line.append(" (Fire OS calls it low under ").append(plan.lowMarkBytes shr 20).append(" MiB")
            swap()?.let { line.append(", swap ").append(it).append(" free") }
            line.append(')')
            if (rateMbps > 0.0) {
                line.append(", film about ").append(Math.round(rateMbps)).append(" Mb/s")
                if (lineMbps > 0.0) line.append(" on a connection bringing ").append(Math.round(lineMbps))
            }
            line.append(": starts at ").append(budget shr 20).append(" MiB")
            val left = (plan.reserveBytes shr 20).toString() + " MiB are left for the decoder and Fire OS"
            line.append(
                when (plan.startBy) {
                    FilmBufferPlan.StartBy.AHEAD -> ", for " + plan.aheadFloorUs / 1_000_000L + " s of it ahead (free memory leaves no more: $left)"
                    FilmBufferPlan.StartBy.FLOOR -> ", the least a film starts with (free memory leaves no more: $left)"
                    FilmBufferPlan.StartBy.KEPT -> ", the memory of the player before it"
                    else -> ", what's free once $left"
                },
            )
            line.append(", up to ").append(plan.tierBytes shr 20).append(" MiB once memory has been quiet")
        } else {
            line.append(", ").append(budget shr 20).append(" MiB")
            if (plan.byFreeMemory) line.append(" (held down by free memory, ").append(plan.tierBytes shr 20).append(" MiB with room)")
        }
        line.append(", ").append(plan.backBufferMs / 1000).append(" s kept behind")
        if (plan.backCapBytes > 0) line.append(" up to ").append(plan.backCapBytes shr 20).append(" MiB")
        if (plan.spanExtraBytes > 0) line.append(", ").append(plan.spanExtraBytes shr 20).append(" MiB given to the second request's long spans")
        return line.toString()
    }

    /**
     * The TV's swap as "12 of 819 MiB", for the log of a start held down by free memory ([swapWords]); null when it
     * has none or it can't be read. One small read of /proc/meminfo, only then: the sizes don't go by it (on the Stick
     * that ran out of memory at a film's start, swap was at 0 kB; what it is at a start that goes well isn't known yet).
     */
    fun swapNow(): String? = runCatching { swapWords(java.io.File("/proc/meminfo").readLines()) }.getOrNull()

    /** [swapNow] from /proc/meminfo's [lines] ("SwapTotal:  838860 kB", "SwapFree:  12288 kB"). */
    fun swapWords(lines: List<String>): String? {
        fun kb(name: String): Long? =
            lines.firstOrNull { it.startsWith(name) }?.substring(name.length)?.trim()?.substringBefore(' ')?.toLongOrNull()
        val total = kb("SwapTotal:") ?: return null
        val free = kb("SwapFree:") ?: return null
        if (total <= 0L) return null
        return "${free shr 10} of ${total shr 10} MiB"
    }
}

/**
 * How far the film's buffer is squeezed for the TV's memory, and when it may grow. [step] counts the eighths of the
 * buffer given back, 0 to [FilmMemory.STEPS] (half; see [FilmMemory.capFor]). Times are elapsedRealtime.
 *
 * As ever, on a TV with memory to spare (not [fine]): Fire OS asking the app to trim (onTrimMemory) goes straight to
 * what it calls for, a quarter back when memory runs low and half when it's critical; its "memory is low", read every
 * 10 s, means a quarter, and half when still low [escalateMs] later. After [recoverMs] without either, a quarter
 * comes back, and the other after the same again.
 *
 * On a TV short of memory ([fine]: [RamTier.short]) the same signals move it an eighth at a time:
 * - Down: an eighth for "running low" or a low reading, a quarter for "critical". Signals within [gapMs] of the first
 *   are one event (Fire OS sends several in the same second) and count as the strongest of them; one [gapMs] or more
 *   after it steps further. So a low reading that lasts reaches half at the same 30 s as before, by way of every
 *   eighth, and one "critical" takes a quarter, not half at once. Seen on both Sticks in the first seconds of a film:
 *   straight to half, 48 MiB. A buffer gives memory back only as fast as the film plays (7 MiB a second at 58 Mb/s),
 *   so half at once meant no loading at all for 9 s and then a film left with the 5 s it never goes under, for the
 *   minutes until the steps back; nothing in that says the giving back itself cost frames (it frees what the film
 *   has played past once a second, and has Android collect it at most once in 10 s, off the playing threads).
 * - Up: the first eighth after [recoverMs] without a signal (the worst seen on a Stick ran 105 s from a film's start;
 *   two minutes outlast it), each further one after [nextMs] more, any signal starting the two minutes again.
 * - A buffer started below its TV's size for want of free memory grows on by the same clock ([mayGrow], [grew]):
 *   [recoverMs] of quiet from the film's start ([filmStarted]) or the last signal, then a step every [nextMs],
 *   only ever while nothing is squeezed.
 */
class MemoryPressure(
    private val fine: Boolean = false,
    private val escalateMs: Long = 30_000L,
    private val recoverMs: Long = 120_000L,
    private val nextMs: Long = 30_000L,
    private val gapMs: Long = 8_000L,
) {
    var step = 0
        private set
    // When the latest run of "memory is low" readings began, or -1 when the last reading wasn't low.
    private var lowSince = -1L
    // When memory was last short (a trim asked for, or a low reading).
    private var lastPressureAt = 0L
    // When the buffer last stepped up or grew since then, or -1 when it hasn't.
    private var lastUpAt = -1L
    // When the film's player was built: a buffer started small grows only once it has been quiet since.
    private var startedAt = 0L
    // Fine steps: when the event the latest signals belong to began (-1: none yet), and the step before it.
    private var eventAt = -1L
    private var eventFrom = 0

    /** Fire OS asked the app to trim, calling for [wanted] (1 running low, 2 critical). True when the step changed. */
    fun trimAsked(wanted: Int, nowMs: Long): Boolean {
        pressed(nowMs)
        return if (fine) down(wanted.coerceIn(1, 2), nowMs) else raise(wanted * QUARTER)
    }

    /** The 10 s check: [low] is whether Fire OS says memory is low now. True when the step changed. */
    fun checked(low: Boolean, nowMs: Long): Boolean {
        if (low) {
            if (lowSince < 0) lowSince = nowMs
            pressed(nowMs)
            return if (fine) down(1, nowMs) else raise(if (nowMs - lowSince >= escalateMs) FilmMemory.STEPS else QUARTER)
        }
        lowSince = -1L
        if (step == 0) return false
        val up = lastUpAt
        if (if (fine && up >= 0L) nowMs - up < nextMs else nowMs - maxOf(lastPressureAt, up) < recoverMs) return false
        step = (step - if (fine) 1 else QUARTER).coerceAtLeast(0)
        lastUpAt = nowMs
        return true
    }

    /** A new player for the film was built at [nowMs]: a buffer started small waits out its start before it grows. */
    fun filmStarted(nowMs: Long) {
        startedAt = nowMs
    }

    /**
     * Whether a buffer started below its TV's size may take a step up at [nowMs] ([FilmMemory.grown]): only on a TV
     * short of memory, with nothing squeezed and no low reading, [recoverMs] after the film's start and the last
     * signal, or [nextMs] after the last step up when that came since both.
     */
    fun mayGrow(nowMs: Long): Boolean {
        if (!fine || step != 0 || lowSince >= 0L) return false
        val up = lastUpAt
        return if (up >= 0L && up >= startedAt) nowMs - up >= nextMs else nowMs - maxOf(lastPressureAt, startedAt) >= recoverMs
    }

    /** The buffer grew a step at [nowMs]. */
    fun grew(nowMs: Long) {
        lastUpAt = nowMs
    }

    private fun pressed(nowMs: Long) {
        lastPressureAt = nowMs
        lastUpAt = -1L
    }

    // A signal for [eighths] more: on top of the step its event began at, so the signals of one event add up to the
    // strongest of them, not to their sum.
    private fun down(eighths: Int, nowMs: Long): Boolean {
        if (eventAt < 0L || nowMs - eventAt >= gapMs) {
            eventAt = nowMs
            eventFrom = step
        }
        return raise(eventFrom + eighths)
    }

    private fun raise(wanted: Int): Boolean {
        val next = wanted.coerceIn(0, FilmMemory.STEPS)
        if (next <= step) return false
        step = next
        return true
    }

    private companion object {
        const val QUARTER = 2
    }
}
