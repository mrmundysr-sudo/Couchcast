package com.mundymorning.couchcast.relay

import android.content.Context
import android.net.Uri
import java.io.IOException
import java.io.InputStream

/** A picked file (content:// address from the system picker). Opened fresh for every request; nothing is copied. */
class UriLocalSource(
    private val context: Context,
    private val uri: Uri,
    override val name: String,
    override val mime: String,
    /** Size from the picker's own metadata, used when the file descriptor doesn't report one. */
    private val reportedSize: Long,
) : LocalSource {
    override val length: Long = run {
        val fromDescriptor = try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        } catch (_: Exception) { -1L }
        if (fromDescriptor >= 0) fromDescriptor else reportedSize
    }

    override fun open(offset: Long): InputStream {
        val afd = context.contentResolver.openAssetFileDescriptor(uri, "r") ?: throw IOException("Couldn't open the file")
        val s = afd.createInputStream()
        try { skipFully(s, offset) } catch (e: IOException) { s.close(); throw e }
        return s
    }
}
