package com.mundymorning.couchcast.install

import android.content.Context
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * The Roku receiver channel (a ZIP sideload package built from /receiver).
 * This is NOT the Android APK; it is what gets uploaded to the Roku.
 */
class ReceiverPackage(val zip: ByteArray) {
    val title: String
    val version: List<Int>

    init {
        val manifest = manifestText(zip) ?: throw IllegalArgumentException("Receiver package has no manifest")
        val kv = parseManifest(manifest)
        title = kv["title"].orEmpty()
        version = listOf("major_version", "minor_version", "build_version").map { kv[it]?.toIntOrNull() ?: 0 }
    }

    val versionName: String get() = version.joinToString(".")

    companion object {
        const val ASSET = "couchcast-receiver.zip"

        fun load(context: Context) = ReceiverPackage(context.assets.open(ASSET).use { it.readBytes() })

        fun manifestText(zip: ByteArray): String? {
            ZipInputStream(ByteArrayInputStream(zip)).use { z ->
                while (true) {
                    val e = z.nextEntry ?: return null
                    if (e.name == "manifest") return z.readBytes().toString(Charsets.UTF_8)
                }
            }
        }

        fun parseManifest(text: String): Map<String, String> =
            text.lineSequence().map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") && '=' in it }
                .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }

        /** "1.0.00001" or "1.0.1" -> [1,0,1]. Null when it is not a dotted number. */
        fun parseVersion(v: String): List<Int>? {
            val parts = v.trim().split('.')
            if (parts.isEmpty() || parts.any { it.toIntOrNull() == null }) return null
            return parts.map { it.toInt() }
        }

        fun compare(a: List<Int>, b: List<Int>): Int {
            for (i in 0 until maxOf(a.size, b.size)) {
                val c = (a.getOrElse(i) { 0 }).compareTo(b.getOrElse(i) { 0 })
                if (c != 0) return c
            }
            return 0
        }
    }
}
