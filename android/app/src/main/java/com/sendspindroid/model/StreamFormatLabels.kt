package com.sendspindroid.model

import com.sendspindroid.sendspin.protocol.StreamFormat
import java.math.BigDecimal

/** A sample rate in kHz without trailing zeros, whatever the locale: "48", "44.1". */
fun kiloHertz(sampleRate: Int): String =
    BigDecimal(sampleRate).movePointLeft(3).stripTrailingZeros().toPlainString()

/** A codec as it is written: "Opus", "FLAC", "PCM". */
fun codecName(codec: String): String = when (codec.lowercase()) {
    "opus" -> "Opus"
    "flac" -> "FLAC"
    "pcm" -> "PCM"
    else -> codec.uppercase()
}

/** "FLAC 48 kHz 24-bit", the form of the stats sheet and the diagnostic report. */
fun StreamFormat.technicalLabel(): String =
    "${codec.uppercase()} ${kiloHertz(sampleRate)} kHz $bitDepth-bit"
