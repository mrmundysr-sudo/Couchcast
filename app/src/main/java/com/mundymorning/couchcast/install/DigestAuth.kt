package com.mundymorning.couchcast.install

import java.security.MessageDigest
import java.security.SecureRandom

/** RFC 2617 HTTP Digest, which is what the Roku development installer uses. */
object DigestAuth {
    data class Challenge(
        val realm: String,
        val nonce: String,
        val qop: String?,
        val opaque: String?,
    )

    fun parseChallenge(header: String?): Challenge? {
        if (header == null || !header.trimStart().startsWith("Digest", ignoreCase = true)) return null
        val params = Regex("(\\w+)=(?:\"([^\"]*)\"|([^,\\s]+))").findAll(header).associate {
            it.groupValues[1].lowercase() to it.groupValues[2].ifEmpty { it.groupValues[3] }
        }
        val realm = params["realm"] ?: return null
        val nonce = params["nonce"] ?: return null
        val qop = params["qop"]?.split(',')?.map { it.trim() }?.firstOrNull { it == "auth" }
        return Challenge(realm, nonce, qop, params["opaque"])
    }

    fun md5Hex(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun response(
        user: String, password: String, method: String, uri: String,
        ch: Challenge, nc: String, cnonce: String,
    ): String {
        val ha1 = md5Hex("$user:${ch.realm}:$password")
        val ha2 = md5Hex("$method:$uri")
        return if (ch.qop != null) md5Hex("$ha1:${ch.nonce}:$nc:$cnonce:${ch.qop}:$ha2")
        else md5Hex("$ha1:${ch.nonce}:$ha2")
    }

    fun authorization(
        user: String, password: String, method: String, uri: String, ch: Challenge,
        nc: String = "00000001", cnonce: String = randomCnonce(),
    ): String {
        val resp = response(user, password, method, uri, ch, nc, cnonce)
        val sb = StringBuilder("Digest username=\"$user\", realm=\"${ch.realm}\", nonce=\"${ch.nonce}\", uri=\"$uri\"")
        if (ch.qop != null) sb.append(", qop=${ch.qop}, nc=$nc, cnonce=\"$cnonce\"")
        sb.append(", response=\"$resp\"")
        ch.opaque?.let { sb.append(", opaque=\"$it\"") }
        return sb.toString()
    }

    private fun randomCnonce(): String {
        val b = ByteArray(8).also { SecureRandom().nextBytes(it) }
        return b.joinToString("") { "%02x".format(it) }
    }
}
