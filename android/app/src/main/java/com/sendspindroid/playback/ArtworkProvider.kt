package com.sendspindroid.playback

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.FileNotFoundException
import java.io.FileOutputStream

/**
 * Serves the current track's artwork as a `content://` URI.
 *
 * Android Auto loads a session's artwork only from `content://` or
 * `android.resource://` URIs: given the server's `http(s)` artwork URL it
 * shows the app icon instead, in its media card and beside Maps. So the
 * session's artwork URI points here, at the JPEG the session already holds.
 *
 * Only the current image is kept, in memory. Each image gets its own path
 * (from a hash of its bytes), so a controller that cached one image loads
 * the next instead of reusing it. A path that is no longer current is not
 * found, rather than answered with the wrong track's art.
 *
 * Exported without a permission, as Android Auto runs in its own process.
 * It hands out nothing but the cover that is already on the lock screen.
 */
class ArtworkProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = MIME_TYPE

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Artwork is read-only")
        val image = current
        if (image == null || uri.lastPathSegment != image.name) {
            throw FileNotFoundException("No artwork at $uri")
        }
        return openPipeHelper(uri, MIME_TYPE, null, image.bytes) { output, _, _, _, bytes ->
            try {
                FileOutputStream(output.fileDescriptor).use { it.write(bytes) }
            } catch (_: java.io.IOException) {
                // The reader closed the pipe early.
            }
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    private class Image(val name: String, val bytes: ByteArray)

    companion object {
        private const val MIME_TYPE = "image/jpeg"

        @Volatile
        private var current: Image? = null

        /** Matches the authority in the manifest, which carries the build's application ID. */
        private fun authority(context: Context) = "${context.packageName}.artwork"

        /**
         * Makes [bytes] (a JPEG) the artwork this provider serves, and returns
         * its URI. The image before it is no longer served.
         */
        fun publish(context: Context, bytes: ByteArray): Uri {
            val name = "%08x.jpg".format(bytes.contentHashCode())
            current = Image(name, bytes)
            return Uri.Builder()
                .scheme("content")
                .authority(authority(context))
                .appendPath(name)
                .build()
        }

        /** Stops serving any artwork. */
        fun clear() {
            current = null
        }
    }
}
