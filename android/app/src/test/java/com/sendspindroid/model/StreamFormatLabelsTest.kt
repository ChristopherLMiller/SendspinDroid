package com.sendspindroid.model

import com.sendspindroid.sendspin.protocol.StreamFormat
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class StreamFormatLabelsTest {

    @Test
    fun `a rate in kHz drops its trailing zeros`() {
        assertEquals("48", kiloHertz(48_000))
        assertEquals("44.1", kiloHertz(44_100))
        assertEquals("352.8", kiloHertz(352_800))
        assertEquals("768", kiloHertz(768_000))
        assertEquals("11.025", kiloHertz(11_025))
    }

    @Test
    fun `a rate in kHz is written the same in every locale`() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("44.1", kiloHertz(44_100))
        } finally {
            Locale.setDefault(before)
        }
    }

    @Test
    fun `codecs are written as their names are`() {
        assertEquals("Opus", codecName("opus"))
        assertEquals("FLAC", codecName("flac"))
        assertEquals("PCM", codecName("PCM"))
        assertEquals("AAC", codecName("aac"))
    }

    @Test
    fun `the technical label names codec, rate and depth`() {
        assertEquals("FLAC 48 kHz 24-bit", StreamFormat("flac", 48_000, 2, 24).technicalLabel())
        assertEquals("PCM 44.1 kHz 16-bit", StreamFormat("pcm", 44_100, 2, 16).technicalLabel())
    }
}
