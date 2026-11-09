package com.mundymorning.couchcast.carreceiver

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArraySet

sealed class PairResult {
    data class Ok(val token: String) : PairResult()
    object Wrong : PairResult()
    data class Locked(val seconds: Long) : PairResult()
}

/**
 * A 6-digit code shown on the receiver. Entering it on a controller (phone web page) earns a token. After 5 wrong tries the
 * receiver locks pairing for 30 seconds and shows a NEW code, so guessing is hopeless. Tokens last until the receiver restarts.
 */
class Pairing(private val now: () -> Long = System::currentTimeMillis) {
    private val rnd = SecureRandom()
    private val tokens = CopyOnWriteArraySet<String>()
    private var failures = 0
    private var lockedUntil = 0L

    @Volatile var pin: String = newPin(); private set

    private fun newPin() = "%06d".format(rnd.nextInt(1_000_000))
    private fun newToken() = ByteArray(16).also { rnd.nextBytes(it) }.joinToString("") { "%02x".format(it) }

    @Synchronized
    fun pair(attempt: String): PairResult {
        val t = now()
        if (t < lockedUntil) return PairResult.Locked((lockedUntil - t + 999) / 1000)
        if (MessageDigest.isEqual(attempt.trim().toByteArray(), pin.toByteArray())) {
            failures = 0
            return PairResult.Ok(newToken().also { tokens += it })
        }
        failures++
        if (failures >= MAX_FAILURES) {
            failures = 0
            lockedUntil = t + LOCK_MS
            pin = newPin()
        }
        return PairResult.Wrong
    }

    fun isValid(token: String?): Boolean =
        token != null && tokens.any { MessageDigest.isEqual(it.toByteArray(), token.toByteArray()) }

    /** Forget every paired controller and show a new code. */
    @Synchronized
    fun reset() { tokens.clear(); pin = newPin(); failures = 0; lockedUntil = 0 }

    companion object {
        const val MAX_FAILURES = 5
        const val LOCK_MS = 30_000L
    }
}
