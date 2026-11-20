package app.fireplayer.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveRoomTest {

    @Test fun fullScreenSoonTheTabAfterAWhile() {
        assertEquals(LiveRoom.FULL_MS, LiveRoom.dueMs(full = true))
        assertEquals(LiveRoom.TAB_MS, LiveRoom.dueMs(full = false))
        assertTrue(LiveRoom.FULL_MS < LiveRoom.TAB_MS)
    }

    @Test fun iconsStayPagesPicturesGo() {
        assertFalse(LiveRoom.isLarge(128 * 128 * 2)) // an icon, RGB_565
        assertFalse(LiveRoom.isLarge(96 * 96 * 4))
        assertTrue(LiveRoom.isLarge(200 * 300 * 2)) // a small poster
        assertTrue(LiveRoom.isLarge(280 * 420 * 2)) // a poster
        assertTrue(LiveRoom.isLarge(1920 * 1080 * 2)) // a banner
    }

    @Test fun theLineSaysWhatWentAndNothingElse() {
        assertEquals(
            "Live memory (full screen): 3 pictures out of sight (4096 KB), page glass copies, unused graphics trimmed",
            LiveRoom.line(full = true, pictures = 3, pictureBytes = 4L shl 20, glass = true, graphics = true),
        )
        assertEquals(
            "Live memory (Live TV a while): 0 pictures out of sight (0 KB), graphics trim not available",
            LiveRoom.line(full = false, pictures = 0, pictureBytes = 0, glass = false, graphics = false),
        )
    }
}
