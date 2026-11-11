package app.fireplayer.player

import app.fireplayer.player.SkipRules.IntroDbSegment
import app.fireplayer.player.SkipRules.Part
import app.fireplayer.player.SkipRules.Pick
import app.fireplayer.player.SkipRules.SkipDbSegment
import app.fireplayer.subs.OffsetTracker.Segment
import app.fireplayer.subs.SyncMapping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.BitSet

/**
 * Database times taken on the downloaded subtitles' timeline, put on a file with a bumper or an ad through auto sync's
 * confirmed offsets ([SyncMapping], [SkipRules.mapped]).
 */
class SyncedSkipTest {
    // The version the subtitles and databases were timed on: 46 minutes.
    private val clean = 46 * 60_000L

    /** Talking matched every 20 s of the file from [fromS] to [toS] seconds, except [skip] (an ad has none). */
    private fun heard(fromS: Int, toS: Int, skip: IntRange = IntRange.EMPTY) = BitSet().apply {
        for (s in fromS until toS step 20) if (s !in skip) set(s)
    }

    private fun mapping(segments: List<Segment>, heard: BitSet) = SyncMapping(segments, heard, 1, "film")

    private fun exact(start: Long, end: Long) = SkipDbSegment(start, end, "exact", 0, 0.9)

    private fun found(p: Pick) = p as Pick.Found

    @Test
    fun anAdAfterTheIntroKeepsTheIntroAndMovesTheCredits() {
        // A 60 s ad at 4:00..5:00 of the file; auto sync found the subtitles 60 s later from 5:00 on.
        val file = clean + 60_000
        val sync = mapping(listOf(Segment(0, 0), Segment(300_000, 60_000)), heard(0, (file / 1000).toInt(), 240..299))
        assertEquals(clean, sync.cleanLength(file))
        // SkipDB, asked with the file's length, can't place it (11 s past its "shifted" range would be the same).
        assertTrue(SkipRules.skipDb(Part.OUTRO, SkipDbSegment(2_640_000, clean, "out-of-range", -60_000, 0.9), file) is Pick.Rejected)
        // Asked with the subtitles' length (adjust=none), it's exact: its times are on the subtitles' timeline.
        val intro = SkipRules.mapped(Part.INTRO, SkipRules.skipDbOnSubtitles(Part.INTRO, exact(60_000, 120_000), clean), sync, file)
        val outro = SkipRules.mapped(Part.OUTRO, SkipRules.skipDbOnSubtitles(Part.OUTRO, exact(2_640_000, clean), clean), sync, file)
        assertEquals(60_000L, found(intro).start)
        assertEquals(120_000L, found(intro).end)
        assertEquals(2_700_000L, found(outro).start)
        assertEquals(file, found(outro).end)
        // Buttons only: database times never start the automatic countdown.
        val segments = SkipRules.segments(mapOf(Part.INTRO to intro, Part.OUTRO to outro), file, "skipdb+sync")!!
        assertEquals(2_700_000L, segments.creditsAt)
        assertTrue(segments.creditsToEnd)
        assertNull(segments.autoAt)
    }

    @Test
    fun introDbCreditsAnchorOnTheSubtitlesLengthNotTheFiles() {
        val file = clean + 60_000
        val sync = mapping(listOf(Segment(0, 0), Segment(300_000, 60_000)), heard(0, (file / 1000).toInt(), 240..299))
        val outro = IntroDbSegment(2_640_000, clean, 0.9)
        // Weighed against the file, its credits don't end where the file does: rejected for length alone.
        val onFile = SkipRules.Evidence(false, SkipRules.outroAnchors(outro, file))
        assertTrue(SkipRules.introDb(Part.OUTRO, outro, false, onFile, file) is Pick.Rejected)
        // Weighed against the subtitles' timeline they do, and map 60 s later.
        val onSubs = SkipRules.Evidence(SkipRules.runtimeAgrees(46, clean), SkipRules.outroAnchors(outro, clean))
        val mapped = SkipRules.mapped(Part.OUTRO, SkipRules.introDb(Part.OUTRO, outro, false, onSubs, clean), sync, file)
        assertEquals(2_700_000L, found(mapped).start)
    }

    @Test
    fun aLeadingBumperMovesEverything() {
        // 12 s of bumper before the episode: one offset from the start.
        val file = clean + 12_000
        val sync = mapping(listOf(Segment(0, 12_000)), heard(0, (file / 1000).toInt()))
        assertEquals(clean, sync.cleanLength(file))
        val intro = SkipRules.mapped(Part.INTRO, SkipRules.skipDbOnSubtitles(Part.INTRO, exact(30_000, 90_000), clean), sync, file)
        assertEquals(42_000L, found(intro).start)
        assertEquals(102_000L, found(intro).end)
    }

    @Test
    fun onlyWhatWasHeardNearbyMaps() {
        // Watched from the start for 10 minutes, then a jump to the end and back: nothing heard near the credits yet.
        val file = clean + 60_000
        val segments = listOf(Segment(0, 0), Segment(300_000, 60_000))
        val early = mapping(segments, heard(0, 600, 240..299))
        assertEquals(100_000L, early.fileTimeOf(100_000))
        assertNull(early.fileTimeOf(2_640_000))
        val credits = SkipRules.skipDbOnSubtitles(Part.OUTRO, exact(2_640_000, clean), clean)
        assertTrue(SkipRules.mapped(Part.OUTRO, credits, early, file) is Pick.Rejected)
        // Talking just before the credits matched under the same offset: now they map, to the file's end (credits
        // have no talking; the lengths agreeing anchors their end).
        val later = mapping(segments, heard(0, 600, 240..299).apply { set(2_650) })
        assertEquals(2_700_000L, found(SkipRules.mapped(Part.OUTRO, credits, later, file)).start)
        assertEquals(file, found(SkipRules.mapped(Part.OUTRO, credits, later, file)).end)
        // Credits followed by a scene: their end, far from anything heard, isn't known.
        val notToEnd = SkipRules.skipDbOnSubtitles(Part.OUTRO, exact(2_640_000, 2_700_000), clean)
        assertTrue(SkipRules.mapped(Part.OUTRO, notToEnd, later, file) is Pick.Rejected)
        // Talking matched only on the other side of a break doesn't count for this side.
        val otherSide = mapping(segments, BitSet().apply { set(310) })
        assertNull(otherSide.fileTimeOf(200_000))
    }

    @Test
    fun nothingAcrossOrRightAtABreak() {
        val file = clean + 60_000
        val sync = mapping(listOf(Segment(0, 0), Segment(300_000, 60_000)), heard(0, (file / 1000).toInt(), 240..299))
        // The minute before the break on the subtitles' timeline could be either side of the ad: no answer.
        assertNull(sync.fileTimeOf(250_000))
        assertEquals(460_000L, sync.fileTimeOf(400_000))
        // A stretch that starts before the break and ends after it isn't one stretch in this file.
        assertNull(sync.fileRangeOf(100_000, 400_000))
        val across = SkipRules.skipDbOnSubtitles(Part.INTRO, exact(100_000, 400_000), clean)
        assertTrue(SkipRules.mapped(Part.INTRO, across, sync, file) is Pick.Rejected)
    }

    @Test
    fun nothingMapsInFilmJumpedOverBeforeTheChange() {
        // Heard to 4:05 (found right to 3:59), a jump to 7:00 past a 60 s ad at 4:00..5:00: the change was put at 7:00,
        // but it may be anywhere after 4:00. An intro at 4:10..5:00 on the subtitles' timeline is really at 5:10..6:00:
        // under the earlier offset it would land in the ad. Nothing maps there; before it, all is as it was.
        val file = clean + 60_000
        val segments = listOf(Segment(0, 0), Segment(420_000, 60_000))
        val heard = BitSet().apply { set(0, 240); set(420, 600) }
        val jumped = SyncMapping(segments, heard, 1, "film", listOf(0L, 240_000L))
        assertNull(jumped.fileRangeOf(250_000, 300_000))
        assertNull(jumped.fileTimeOf(250_000))
        assertEquals(200_000L, jumped.fileTimeOf(200_000))
        assertEquals(500_000L, jumped.fileTimeOf(440_000))
        val intro = SkipRules.skipDbOnSubtitles(Part.INTRO, exact(250_000, 300_000), clean)
        assertTrue(SkipRules.mapped(Part.INTRO, intro, jumped, file) is Pick.Rejected)
        // Without knowing where the change may begin, the same would have mapped into the ad.
        assertEquals(LongRange(250_000, 300_000), SyncMapping(segments, heard, 1, "film").fileRangeOf(250_000, 300_000))
    }

    @Test
    fun aSecondAcrossAChangeCountsForNeitherSide() {
        // The change at 5:00.5; second 300 was matched under the later offset only. Nothing before it was heard again.
        val sync = mapping(listOf(Segment(0, 0), Segment(300_500, 60_000)), BitSet().apply { set(300); set(360, 600) })
        assertNull(sync.fileTimeOf(250_000))
        assertEquals(460_000L, sync.fileTimeOf(400_000))
    }

    @Test
    fun cutFilmMapsToNothing() {
        // 30 s missing from this file at 10:00: those 30 s of the subtitles are nowhere in it.
        val sync = mapping(listOf(Segment(0, 0), Segment(600_000, -30_000)), heard(0, 2_700))
        assertNull(sync.fileTimeOf(615_000))
        assertEquals(620_000L, sync.fileTimeOf(650_000))
    }

    @Test
    fun anotherVersionOfTheEpisodeIsNotMapped() {
        val file = clean + 60_000
        val sync = mapping(listOf(Segment(0, 0), Segment(300_000, 60_000)), heard(0, (file / 1000).toInt(), 240..299))
        // SkipDB timed a different cut than the subtitles: anything but exact at the subtitles' length is left out.
        for (m in listOf("shifted", "out-of-range", "agnostic")) {
            val p = SkipRules.skipDbOnSubtitles(Part.OUTRO, SkipDbSegment(2_640_000, clean, m, 5_000, 0.95), clean)
            assertTrue(m, SkipRules.mapped(Part.OUTRO, p, sync, file) is Pick.Rejected)
        }
        assertTrue(SkipRules.skipDbOnSubtitles(Part.INTRO, SkipDbSegment(60_000, 120_000, "exact", 0, 0.3), clean) is Pick.Rejected)
        // IntroDB's credits ending 40 s off the subtitles' end: another version, rejected before any mapping.
        val outro = IntroDbSegment(2_600_000, clean - 40_000, 0.95)
        val evidence = SkipRules.Evidence(true, SkipRules.outroAnchors(outro, clean))
        assertTrue(SkipRules.mapped(Part.OUTRO, SkipRules.introDb(Part.OUTRO, outro, false, evidence, clean), sync, file) is Pick.Rejected)
        // "None" and unknown pass through as they are.
        assertTrue(SkipRules.skipDbOnSubtitles(Part.RECAP, SkipDbSegment(0, 0, "exact", 0, 0.9), clean) === Pick.None)
        assertTrue(SkipRules.mapped(Part.RECAP, Pick.Missing, sync, file) === Pick.Missing)
    }

    @Test
    fun alreadySyncedSubtitlesMapOntoThemselves() {
        val sync = mapping(listOf(Segment(0, 0)), heard(0, 2_760))
        assertEquals(clean, sync.cleanLength(clean))
        assertEquals(LongRange(60_000, 120_000), sync.fileRangeOf(60_000, 120_000))
        // Nothing confirmed (a delay by hand, or offsets restored but not heard again this time): nothing maps.
        assertNull(mapping(listOf(Segment(0, 0)), BitSet()).fileTimeOf(60_000))
    }
}
