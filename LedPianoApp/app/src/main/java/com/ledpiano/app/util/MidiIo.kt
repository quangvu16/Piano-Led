package com.ledpiano.app.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.IOException
import java.io.InputStream

/** Helpers for opening MIDI files from content URIs or bundled assets. */
object MidiIo {

    /** URIs of the form `asset://samples/foo.mid` point into the APK assets. */
    const val ASSET_SCHEME = "asset://"

    fun openStream(context: Context, uriString: String): InputStream =
        if (uriString.startsWith(ASSET_SCHEME)) {
            context.assets.open(uriString.removePrefix(ASSET_SCHEME))
        } else {
            context.contentResolver.openInputStream(Uri.parse(uriString))
                ?: throw IOException("Không thể đọc tệp: $uriString")
        }

    fun queryDisplayName(context: Context, uri: Uri): String {
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) {
                        val name = cursor.getString(idx)
                        if (!name.isNullOrBlank()) return name
                    }
                }
            }
        } catch (_: Exception) {
        }
        return uri.lastPathSegment ?: "song.mid"
    }

    /** Turns a file name into a readable title for the exercises list. */
    fun cleanSongName(fileName: String): String =
        fileName.removeSuffix(".mid").removeSuffix(".MID")
            .replace('_', ' ').replace('-', ' ')
            .replace(Regex("\\s+"), " ").trim()
}
