package app.fireplayer.player

import app.fireplayer.ui.QrCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QrCodeTest {
    @Test
    fun theAddressFitsASmallSquareCodeWithAQuietZone() {
        val m = QrCode.matrix("http://192.168.1.23:8765")
        assertEquals(m.width, m.height)
        // Version 2 (25 modules) with 3 modules of margin on each side.
        assertEquals(31, m.width)
        for (i in 0 until m.width) {
            assertTrue(!m[i, 0] && !m[0, i] && !m[i, m.height - 1] && !m[m.width - 1, i])
        }
        // A finder pattern's dark corner sits just inside the quiet zone.
        assertTrue(m[3, 3])
    }
}
