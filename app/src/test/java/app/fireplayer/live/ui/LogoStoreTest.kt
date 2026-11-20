package app.fireplayer.live.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A channel logo as kept on disk: when it was fetched, then the picture; shown from there while it's fresh. */
class LogoStoreTest {

    private val day = 24L * 60 * 60 * 1000
    private val picture = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3, -1, 0, 127, -128)

    @Test fun thePictureComesBackAsItWentIn() {
        val fetched = 1_795_000_000_123L
        val kept = LogoStore.pack(fetched, picture)
        assertEquals(fetched, LogoStore.fetchedAt(kept))
        val at = LogoStore.pictureAt(kept)!!
        assertArrayEquals(picture, kept.copyOfRange(at, kept.size))
    }

    @Test fun everyTimeIsKeptExactly() {
        for (t in longArrayOf(0L, 1L, 255L, 256L, 1_700_000_000_000L, Long.MAX_VALUE, 0x0102030405060708L)) {
            assertEquals(t, LogoStore.fetchedAt(LogoStore.pack(t, picture)))
        }
    }

    @Test fun aLogoIsShownFromDiskForTwoWeeksThenFetchedAgain() {
        val fetched = 1_795_000_000_000L
        val kept = LogoStore.pack(fetched, picture)
        assertTrue(LogoStore.isFresh(kept, fetched))
        assertTrue(LogoStore.isFresh(kept, fetched + 13 * day))
        assertTrue(LogoStore.isFresh(kept, fetched + LogoStore.MAX_AGE_MS - 1))
        // Two weeks on, its server is asked again (a provider may have put a new logo at the same address).
        assertFalse(LogoStore.isFresh(kept, fetched + LogoStore.MAX_AGE_MS))
        assertFalse(LogoStore.isFresh(kept, fetched + 30 * day))
    }

    @Test fun aLogoFetchedInTheFutureIsNotTrusted() {
        // The TV's clock was wrong when it was kept (or is wrong now): asked for again rather than kept for years.
        val kept = LogoStore.pack(2_000_000_000_000L, picture)
        assertFalse(LogoStore.isFresh(kept, 1_795_000_000_000L))
    }

    @Test fun whatIsNotAKeptLogoIsNeverShown() {
        // Cut short (the app stopped while writing), or empty: no picture, never fresh.
        for (n in 0..8) {
            val short = ByteArray(n)
            assertNull(LogoStore.pictureAt(short))
            assertNull(LogoStore.fetchedAt(short))
            assertFalse(LogoStore.isFresh(short, 1_795_000_000_000L))
        }
    }

    @Test fun theSameLogoAtAnotherSizeIsKeptApart() {
        val url = "http://logos.example/one.png"
        // The guide's rows and a search row decode it for different sizes: one mustn't be shown for the other.
        assertNotEquals(LogoStore.key(url, 152), LogoStore.key(url, 96))
        assertEquals(LogoStore.key(url, 152), LogoStore.key(url, 152))
        assertNotEquals(LogoStore.key(url, 152), LogoStore.key("http://logos.example/two.png", 152))
    }
}
