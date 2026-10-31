package com.sendspindroid.playback

import android.content.Context
import android.graphics.Bitmap
import androidx.media3.common.Player
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.FileNotFoundException

/**
 * The artwork Android Auto loads: [ArtworkProvider] serves the session's JPEG
 * at a content:// URI, and [MetadataForwardingPlayer] puts that URI in the
 * metadata ahead of the server's http(s) URL, which Auto does not load.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ArtworkProviderTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        Robolectric.buildContentProvider(ArtworkProvider::class.java)
            .create("${context.packageName}.artwork")
    }

    @After
    fun tearDown() = ArtworkProvider.clear()

    private fun read(uri: android.net.Uri): ByteArray =
        context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }

    @Test
    fun `a published image is served at its content uri`() {
        val jpeg = byteArrayOf(1, 2, 3, 4)

        val uri = ArtworkProvider.publish(context, jpeg)

        assertEquals("content", uri.scheme)
        assertEquals("${context.packageName}.artwork", uri.authority)
        assertEquals("image/jpeg", context.contentResolver.getType(uri))
        assertArrayEquals(jpeg, read(uri))
    }

    @Test
    fun `each image has its own uri and an old one is no longer served`() {
        val first = ArtworkProvider.publish(context, byteArrayOf(1, 2, 3))
        val second = ArtworkProvider.publish(context, byteArrayOf(4, 5, 6))

        assertNotEquals(first, second)
        assertArrayEquals(byteArrayOf(4, 5, 6), read(second))
        assertThrows { read(first) }
    }

    @Test
    fun `nothing is served once cleared`() {
        val uri = ArtworkProvider.publish(context, byteArrayOf(1, 2, 3))

        ArtworkProvider.clear()

        assertThrows { read(uri) }
    }

    @Test
    fun `the metadata carries the served uri rather than the server url`() {
        val player = MetadataForwardingPlayer(mockk<Player>(relaxed = true)) {
            ArtworkProvider.publish(context, it)
        }
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)

        player.updateMetadata(
            title = "Song", artist = "Artist", album = "Album",
            artwork = bitmap,
            artworkUri = android.net.Uri.parse("https://server/art.jpg")
        )

        val metadata = player.mediaMetadata
        assertEquals("content", metadata.artworkUri?.scheme)
        assertArrayEquals(metadata.artworkData, read(metadata.artworkUri!!))
    }

    @Test
    fun `without an image the metadata keeps the server url`() {
        val player = MetadataForwardingPlayer(mockk<Player>(relaxed = true)) {
            ArtworkProvider.publish(context, it)
        }

        player.updateMetadata(
            title = "Song", artist = "Artist", album = "Album",
            artworkUri = android.net.Uri.parse("https://server/art.jpg")
        )

        assertEquals("https://server/art.jpg", player.mediaMetadata.artworkUri.toString())
        assertNull(player.mediaMetadata.artworkData)
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (_: FileNotFoundException) {
            return
        }
        throw AssertionError("Expected FileNotFoundException")
    }
}
