package com.sendspindroid.sendspin.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which format a stream's PCM goes out in on a device that plays some bit-perfect. */
class BitPerfectFormatsTest {

    // A DAC with 32-bit slots everywhere, and 16-bit at the CD rate
    private val dac = BitPerfectFormats(
        setOf(44_100 to 16, 44_100 to 32, 48_000 to 32, 96_000 to 32, 192_000 to 32)
    )

    @Test
    fun `a depth the device takes goes out as it is`() {
        assertEquals(16, dac.outputDepth(44_100, 16))
        assertEquals(32, dac.outputDepth(96_000, 32))
    }

    @Test
    fun `a narrower depth goes out in the narrowest wider sample`() {
        assertEquals(32, dac.outputDepth(96_000, 24))
        assertEquals(32, dac.outputDepth(48_000, 16))
        // 16-bit has a format of its own at 44.1 kHz, so it is not widened there
        assertEquals(16, dac.outputDepth(44_100, 16))
        assertEquals(32, dac.outputDepth(44_100, 24))
    }

    @Test
    fun `a stream is never narrowed, and a rate the device lacks is not covered`() {
        val sixteenBit = BitPerfectFormats(setOf(48_000 to 16))
        assertNull(sixteenBit.outputDepth(48_000, 24))
        assertFalse(sixteenBit.covers(48_000, 24))
        assertNull(dac.outputDepth(88_200, 16))
        assertTrue(dac.covers(192_000, 24))
    }

    @Test
    fun `no formats covers nothing`() {
        val none = BitPerfectFormats(emptySet())
        assertTrue(none.isEmpty())
        assertFalse(none.covers(48_000, 16))
    }
}
