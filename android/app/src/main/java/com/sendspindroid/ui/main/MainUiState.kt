package com.sendspindroid.ui.main

import android.graphics.Bitmap

/**
 * Track metadata for display in player UI.
 *
 * [albumArtist] and [year] are null when the track has none. Both are
 * optional in roles/metadata/v1.md (Server -> Client: server/state metadata
 * object), "`album_artist?`: string - album artist(s)" and "`year?`: integer -
 * release year in YYYY format", and no "" or 0 stands in for an absent one.
 * The exception is the album artist from the Media3 feed, which is the track
 * artist when the server sends none, so the two feeds can disagree on it.
 * Now Playing does not show an album artist equal to the artist.
 */
data class TrackMetadata(
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String? = null,
    val year: Int? = null
) {
    companion object {
        val EMPTY = TrackMetadata("", "", "")
    }

    val isEmpty: Boolean get() = title.isBlank() && artist.isBlank() && album.isBlank()
}

/**
 * Source for album artwork - can be binary data, URI, or URL.
 */
sealed class ArtworkSource {
    data class ByteArray(val data: kotlin.ByteArray) : ArtworkSource() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ByteArray) return false
            return data.contentEquals(other.data)
        }

        override fun hashCode(): Int = data.contentHashCode()
    }

    data class Uri(val uri: android.net.Uri) : ArtworkSource()
    data class Url(val url: String) : ArtworkSource()
}

/**
 * State for reconnection indicator.
 */
data class ReconnectingState(
    val serverName: String,
    val attempt: Int
)

/**
 * Player colors extracted from album artwork.
 */
data class PlayerColors(
    val backgroundColor: Int,
    val accentColor: Int,
    val textColor: Int
)
