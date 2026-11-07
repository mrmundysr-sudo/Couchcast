package com.mundymorning.couchcast.relay

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

/** A file that lives on the phone (a video or a prepared photo) and can be served to the Roku by the relay. */
interface LocalSource {
    val name: String
    val mime: String
    /** Size in bytes, or -1 when the provider can't say (then ranges are not offered). */
    val length: Long
    /** A fresh stream starting at [offset]. The caller closes it. */
    fun open(offset: Long): InputStream
}

/** Skips exactly [n] bytes or throws; InputStream.skip may legitimately skip less. */
internal fun skipFully(s: InputStream, n: Long) {
    var left = n
    while (left > 0) {
        val skipped = s.skip(left)
        if (skipped > 0) { left -= skipped; continue }
        if (s.read() < 0) throw IOException("Unexpected end of file")
        left--
    }
}

class FileLocalSource(private val file: File, override val mime: String, override val name: String = file.name) : LocalSource {
    override val length: Long get() = file.length()
    override fun open(offset: Long): InputStream {
        val s = FileInputStream(file)
        try { skipFully(s, offset) } catch (e: IOException) { s.close(); throw e }
        return s
    }
}
