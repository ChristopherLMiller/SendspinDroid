package com.sendspindroid.sendspin.audio

import android.annotation.TargetApi
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import com.sendspindroid.logging.PlayerLog

/**
 * How the output plays a stream's PCM: at what bit depth, and whether it
 * goes to the device untouched. Untouched (bit-perfect), Android applies no
 * volume to it, so the player applies volume and mutes to the samples.
 */
data class OutputSetup(val bitDepth: Int, val bitPerfect: Boolean = false)

/**
 * Sets the platform's output up for a stream's PCM format, just before the
 * player for it is made. Main thread.
 */
fun interface OutputPreparer {
    fun prepare(sampleRate: Int, channels: Int, bitDepth: Int): OutputSetup

    companion object {
        /** Through the platform mixer, at the stream's own bit depth. */
        val MIXER = OutputPreparer { _, _, bitDepth -> OutputSetup(bitDepth) }
    }
}

/**
 * The stereo formats a device plays bit-perfect, as (sample rate, bit depth),
 * and which of them a stream's PCM goes out in.
 *
 * PCM narrower than the device takes goes out in a wider sample, its bits at
 * the top and zeros under them, as a DAC with 32-bit slots for 24-bit audio
 * expects: the device gets every bit of the stream. A stream is never
 * narrowed.
 */
data class BitPerfectFormats(val formats: Set<Pair<Int, Int>>) {

    fun isEmpty(): Boolean = formats.isEmpty()

    /**
     * The bit depth [bitDepth] PCM at [sampleRate] goes out at: its own,
     * else the narrowest wider one the device takes. Null when it cannot go
     * out bit-perfect.
     */
    fun outputDepth(sampleRate: Int, bitDepth: Int): Int? =
        formats.filter { (rate, depth) -> rate == sampleRate && depth >= bitDepth }
            .minOfOrNull { it.second }

    /** Whether [bitDepth] PCM at [sampleRate] can go out bit-perfect. */
    fun covers(sampleRate: Int, bitDepth: Int): Boolean = outputDepth(sampleRate, bitDepth) != null
}

/**
 * Bit-perfect playback on a USB DAC, Android 14+. Android then hands the
 * audio to the device as it is: "the audio stream must not have any volume
 * scaling, sample rate conversion, or audio processing effects", and "the
 * framework doesn't provide any software volume control".
 *
 * It is asked for one format at a time, the stream's, through the preferred
 * mixer attributes of media on the device, and the AudioTrack has to be in
 * the same AudioFormat. Only USB devices take them, only for USAGE_MEDIA,
 * and a device's preference ends when it is unplugged. Not every device or
 * DAC offers it: the HAL has to. Callers check for API 34.
 */
@TargetApi(34)
class UsbBitPerfect(private val audioManager: AudioManager) {

    // The attributes the player's AudioTrack plays with (SyncAudioPlayer):
    // "use the same attributes for playback"
    private val media = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    // The device a preference was set on, to clear it again. Main thread.
    private var preferredOn: AudioDeviceInfo? = null

    /** The USB device media plays on now, or null. */
    fun usbOutput(): AudioDeviceInfo? =
        audioManager.getAudioDevicesForAttributes(media).firstOrNull { it.type in USB_TYPES }

    /** What media's USB device plays bit-perfect; empty with no such device. */
    fun formats(): BitPerfectFormats {
        val device = usbOutput() ?: return BitPerfectFormats(emptySet())
        return BitPerfectFormats(bitPerfectAttributes(device).keys)
    }

    /**
     * Ask for [bitDepth] stereo PCM at [sampleRate] to go to the USB device
     * untouched, before the AudioTrack for it is made. Without a USB device
     * that takes it, any earlier preference is cleared, and the stream goes
     * through the mixer at its own depth.
     */
    fun prepare(sampleRate: Int, channels: Int, bitDepth: Int): OutputSetup {
        val device = usbOutput()
        val attributes = device?.let { bitPerfectAttributes(it) }.orEmpty()
        val depth = BitPerfectFormats(attributes.keys).outputDepth(sampleRate, bitDepth)
        val chosen = depth?.let { attributes[sampleRate to it] }
        if (device == null || channels != 2 || chosen == null) {
            clear()
            return OutputSetup(bitDepth)
        }
        val set = try {
            audioManager.setPreferredMixerAttributes(media, device, chosen)
        } catch (e: Exception) {
            PlayerLog.Audio.w("Bit-perfect output refused: ${e.message}")
            false
        }
        if (!set) {
            clear()
            return OutputSetup(bitDepth)
        }
        preferredOn = device
        PlayerLog.Audio.i("Bit-perfect output on ${device.productName}: ${sampleRate}Hz/${depth}bit " +
            "for a ${bitDepth}-bit stream")
        return OutputSetup(depth, bitPerfect = true)
    }

    /** Media goes through the mixer again. */
    fun clear() {
        val device = preferredOn ?: return
        preferredOn = null
        try {
            audioManager.clearPreferredMixerAttributes(media, device)
        } catch (e: Exception) {
            PlayerLog.Audio.w("Bit-perfect output not cleared: ${e.message}")
        }
    }

    /** [device]'s stereo bit-perfect mixer attributes, by (sample rate, bit depth). */
    private fun bitPerfectAttributes(device: AudioDeviceInfo): Map<Pair<Int, Int>, AudioMixerAttributes> =
        audioManager.getSupportedMixerAttributes(device)
            .filter {
                it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT &&
                    it.format.channelMask == AudioFormat.CHANNEL_OUT_STEREO
            }
            .mapNotNull { attributes ->
                val depth = when (attributes.format.encoding) {
                    AudioFormat.ENCODING_PCM_16BIT -> 16
                    AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
                    AudioFormat.ENCODING_PCM_32BIT -> 32
                    else -> return@mapNotNull null
                }
                (attributes.format.sampleRate to depth) to attributes
            }
            .toMap()

    private companion object {
        val USB_TYPES = setOf(AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET)
    }
}
