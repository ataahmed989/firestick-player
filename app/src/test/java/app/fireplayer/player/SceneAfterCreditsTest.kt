package app.fireplayer.player

import app.fireplayer.player.MkvChapters.Chapter
import app.fireplayer.player.SkipRules.IntroDbSegment
import app.fireplayer.player.SkipRules.Part
import app.fireplayer.player.SkipRules.Pick
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A real scene after the credits ([Segments.sceneAt]), which the watched rule waits for, told apart from a preview of
 * the next episode, from the answers SkipDB and IntroDB really give (fetched Nov 2026) and from a file's chapters. The
 * buttons and end card ([Segments.afterCredits]) are as before.
 */
class SceneAfterCreditsTest {
    private val s = 1_000L

    /** IntroDB's answer for The Avengers (tt0848228): a scene (8107 s) before the scroll it times as credits. */
    private val avengers = """{"imdb_id":"tt0848228","media_type":"movie","is_movie":true,"season":0,"episode":0,"intro":null,
        "recap":null,"outro":{"start_sec":8155,"end_sec":8574,"start_ms":8155000,"end_ms":8574000,"confidence":1,"submission_count":1},
        "post_credits":{"start_sec":8107,"end_sec":8155,"start_ms":8107000,"end_ms":8155000,"confidence":1,"submission_count":1},
        "no_intro":false,"no_recap":false,"no_outro":false}"""

    /** IntroDB's answer for Guardians of the Galaxy (tt2015381): the scene runs from the credits' end to the film's. */
    private val guardians = """{"imdb_id":"tt2015381","media_type":"movie","is_movie":true,"intro":null,"recap":null,
        "outro":{"start_ms":6852000,"end_ms":7210000,"confidence":1},"post_credits":{"start_ms":7210000,"end_ms":7239000,"confidence":1},
        "no_intro":false,"no_recap":false,"no_outro":false}"""

    /** SkipDB's answer for Death Note S1 E1 (tt0877057), asked with 1380 s: a "Next time on…" preview, no scene. */
    private val deathNoteSkipDb = """{"imdb_id":"tt0877057","season":1,"episode":1,"segments":{
        "intro":{"start_ms":0,"end_ms":86000,"adjusted":false,"offset_ms":0,"match":"agnostic","confidence":0.75},"recap":null,
        "outro":{"start_ms":1286100,"end_ms":1356100,"adjusted":false,"offset_ms":0,"match":"agnostic","confidence":0.75},
        "preview":{"start_ms":1356100,"end_ms":1377400,"adjusted":false,"offset_ms":2600,"match":"shifted","confidence":0.82}},
        "intro_length_estimate_ms":85900}"""

    /** IntroDB's answer for the same episode: credits, and no post-credits scene. */
    private val deathNoteIntroDb = """{"imdb_id":"tt0877057","media_type":"tv","is_movie":false,"season":1,"episode":1,
        "intro":{"start_ms":1039,"end_ms":91039,"confidence":1},"recap":null,"outro":{"start_ms":1286120,"end_ms":1356110,"confidence":1},
        "post_credits":null,"no_intro":false,"no_recap":false,"no_outro":false}"""

    @Test
    fun introDbsPostCreditsSceneBeforeTheScrollIsAScene() {
        val file = 8_577 * s
        val picks = SkipTimes.introDbPicks(JSONObject(avengers), file, tmdbAgrees = true)
        val seg = SkipRules.segments(picks, file, "introdb")!!
        assertEquals(8_107 * s, seg.sceneAt)
        // The buttons as before: credits at 8155 s run to the end, so the end card comes there.
        assertEquals(8_155 * s, seg.creditsAt)
        assertNull(seg.afterCredits)
        assertEquals(8_155 * s, seg.endCardAt)
    }

    @Test
    fun aSceneRunningToTheFilmsEndIsEvidenceForItself() {
        val file = 7_241 * s
        val picks = SkipTimes.introDbPicks(JSONObject(guardians), file, tmdbAgrees = true)
        // IntroDB's credits end 31 s before this file does: not trusted for the buttons, as before.
        assertTrue(picks[Part.OUTRO] is Pick.Rejected)
        assertTrue(picks[Part.PREVIEW] is Pick.Rejected)
        // The scene ends where the file does: trusted for the watched rule.
        assertEquals(7_210 * s, (picks[Part.SCENE] as Pick.Found).start)
        val seg = SkipRules.segments(picks, file, "introdb")!!
        assertEquals(7_210 * s, seg.sceneAt)
        // Nothing else known: no button, no end card.
        assertNull(seg.creditsAt)
        assertNull(seg.afterCredits)
        assertNull(seg.skip(7_215 * s))
        assertNull(seg.endCardAt)
        // A file that ends elsewhere (another cut): no scene.
        val other = SkipTimes.introDbPicks(JSONObject(guardians), 7_300 * s, tmdbAgrees = true)
        assertTrue(other[Part.SCENE] is Pick.Rejected)
        assertNull(SkipRules.segments(other, 7_300 * s, "introdb"))
    }

    @Test
    fun skipDbsPreviewIsNeverAScene() {
        val file = 1_380 * s
        val skipDb = SkipTimes.skipDbPicks(JSONObject(deathNoteSkipDb), file)
        assertTrue(skipDb[Part.PREVIEW] is Pick.Found)
        assertTrue(skipDb[Part.SCENE] is Pick.Missing)
        val intro = SkipTimes.introDbPicks(JSONObject(deathNoteIntroDb), file, tmdbAgrees = true)
        assertTrue(intro[Part.SCENE] is Pick.Missing)
        val seg = SkipRules.segments(SkipRules.combine(skipDb, intro), file, "skipdb+introdb")!!
        assertNull(seg.sceneAt)
        // IntroDB's credits end where the preview starts, 24 s before this file does: not trusted, as before.
        assertNull(seg.creditsAt)
        assertEquals(1_039L..91_039L, seg.intro)
        // With credits trusted (SkipDB's own, say) the preview is what follows them: Skip credits, no end card, and
        // still no scene, so the watched rule waits for the credits alone.
        val trusted = SkipRules.segments(skipDb + (Part.OUTRO to Pick.Found(1_286_100, 1_356_100)), file, "skipdb")!!
        assertNull(trusted.sceneAt)
        assertEquals(1_286_100L, trusted.creditsAt)
        assertEquals(1_356_100L, trusted.afterCredits)
        assertNull(trusted.endCardAt)
        assertEquals("Skip credits", trusted.skip(1_300 * s)?.label)
    }

    @Test
    fun theButtonsGoAsBeforeWithASceneKnown() {
        val file = 7_240 * s
        val outro = Pick.Found(6_852 * s, 7_210 * s)
        val scene = Pick.Found(7_215 * s, 7_239 * s)
        val withScene = mapOf(Part.OUTRO to outro, Part.PREVIEW to scene, Part.SCENE to scene)
        val without = mapOf(Part.OUTRO to outro, Part.PREVIEW to scene)
        val a = SkipRules.segments(withScene, file, "introdb")!!
        val b = SkipRules.segments(without, file, "introdb")!!
        assertEquals(7_215 * s, a.sceneAt)
        assertNull(b.sceneAt)
        assertEquals(b.creditsAt, a.creditsAt)
        assertEquals(b.afterCredits, a.afterCredits)
        assertEquals(b.endCardAt, a.endCardAt)
        assertEquals(b.exitAt, a.exitAt)
        assertEquals(b.creditsToEnd, a.creditsToEnd)
        assertEquals(b.skip(7_000 * s)?.target, a.skip(7_000 * s)?.target)
    }

    @Test
    fun theSceneNeedsIntroDbsEvidence() {
        val file = 7_240 * s
        val seg = IntroDbSegment(7_210 * s, 7_239 * s, 1.0)
        val none = SkipRules.Evidence(tmdbAgrees = true, creditsEndHere = false)
        assertTrue(SkipRules.introDb(Part.SCENE, seg, false, none, file) is Pick.Rejected)
        assertTrue(SkipRules.introDb(Part.SCENE, seg, false, SkipRules.sceneEvidence(none, seg, file), file) is Pick.Found)
        // Unsure: never.
        val unsure = IntroDbSegment(7_210 * s, 7_239 * s, 0.3)
        assertTrue(SkipRules.introDb(Part.SCENE, unsure, false, SkipRules.sceneEvidence(none, unsure, file), file) is Pick.Rejected)
        // In the first half: not a scene after the credits.
        assertTrue(SkipRules.fits(Part.SCENE, 1_000 * s, 1_100 * s, file) is Pick.Rejected)
    }

    @Test
    fun aChapterAfterTheCreditsIsASceneUnlessItsAPreview() {
        val film = 7_200 * s
        // Credits, then a scene: the scene.
        val scene = MkvChapters.classify(listOf(
            Chapter(0, null, "Opening"), Chapter(600 * s, null, "Chapter 2"),
            Chapter(6_800 * s, null, "End Credits"), Chapter(7_100 * s, null, "Post-Credits Scene"),
        ), film)!!
        assertEquals(6_800 * s, scene.creditsAt)
        assertEquals(7_100 * s, scene.afterCredits)
        assertEquals(7_100 * s, scene.sceneAt)
        // An anime episode: ED, then the next episode's preview: no scene.
        val ep = 1_420 * s
        val preview = MkvChapters.classify(listOf(
            Chapter(0, null, "Prologue"), Chapter(90 * s, null, "OP"), Chapter(180 * s, null, "Part A"),
            Chapter(1_300 * s, null, "ED"), Chapter(1_390 * s, null, "Preview"),
        ), ep)!!
        assertEquals(1_300 * s, preview.creditsAt)
        assertEquals(1_390 * s, preview.afterCredits)
        assertNull(preview.sceneAt)
        assertNull(MkvChapters.classify(listOf(Chapter(1_300 * s, null, "ED"), Chapter(1_390 * s, null, "Next Episode")), ep)!!.sceneAt)
        assertNull(MkvChapters.classify(listOf(Chapter(1_300 * s, null, "ED"), Chapter(1_390 * s, null, "次回予告")), ep)!!.sceneAt)
        // ED, an epilogue, then the preview: the epilogue.
        val epilogue = MkvChapters.classify(listOf(
            Chapter(1_250 * s, null, "ED"), Chapter(1_340 * s, null, "Epilogue"), Chapter(1_390 * s, null, "Preview"),
        ), ep)!!
        assertEquals(1_340 * s, epilogue.sceneAt)
    }

    @Test
    fun noSceneWhereNothingLongEnoughFollowsTheCredits() {
        val film = 7_200 * s
        // The credits run to the end.
        assertNull(MkvChapters.classify(listOf(Chapter(6_800 * s, null, "Credits")), film)!!.sceneAt)
        // Something after them, but no chapter of its own: of a kind not known.
        val unnamed = MkvChapters.classify(listOf(Chapter(6_800 * s, 7_000 * s, "Credits")), film)!!
        assertEquals(7_000 * s, unnamed.afterCredits)
        assertNull(unnamed.sceneAt)
        // A 10 s logo, then the end: nothing after the credits (as before), no scene.
        val logo = MkvChapters.classify(listOf(Chapter(6_800 * s, null, "Credits"), Chapter(7_190 * s, null, "Logo")), film)!!
        assertNull(logo.afterCredits)
        assertNull(logo.sceneAt)
        // A 6 s logo, then a 60 s scene: the scene.
        val both = MkvChapters.classify(listOf(
            Chapter(6_800 * s, null, "Credits"), Chapter(7_074 * s, null, "Logo"), Chapter(7_080 * s, null, "Stinger"),
        ), film)!!
        assertEquals(7_080 * s, both.sceneAt)
    }

    @Test
    fun theSceneComesWithTheCreditsTheyreTakenWith() {
        val file = 7_240 * s
        val chapters = Segments(null, 60 * s..90 * s, 6_800 * s, 7_100 * s, "chapters", creditsFromFile = true, sceneAt = 7_100 * s)
        val db = Segments(10 * s..40 * s, null, 6_850 * s, null, "introdb", sceneAt = 7_210 * s)
        val both = chapters.or(db)
        assertEquals(7_100 * s, both.sceneAt)
        assertEquals(10 * s..40 * s, both.recap)
        // Chapters without credits: the database's credits and its scene.
        val noCredits = Segments(null, 60 * s..90 * s, null, null, "chapters").or(db)
        assertEquals(6_850 * s, noCredits.creditsAt)
        assertEquals(7_210 * s, noCredits.sceneAt)
        // Chapters' credits with no scene after them: none from the database either (the credits' own source says).
        val plain = Segments(null, null, 6_800 * s, null, "chapters", creditsToEnd = true, creditsFromFile = true).or(db)
        assertNull(plain.sceneAt)
        assertNotNull(plain.exitAt)
    }
}
