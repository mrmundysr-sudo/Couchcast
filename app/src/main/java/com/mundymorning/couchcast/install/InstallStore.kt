package com.mundymorning.couchcast.install

import android.content.Context

/** Wizard progress that survives leaving the screen and process death. Never holds a password. */
class InstallStore(context: Context) {
    private val prefs = context.getSharedPreferences("couchcast_install", Context.MODE_PRIVATE)

    var ip: String?
        get() = prefs.getString("ip", null)
        set(v) = prefs.edit().putString("ip", v).apply()

    var roomName: String
        get() = prefs.getString("roomName", "").orEmpty()
        set(v) = prefs.edit().putString("roomName", v).apply()

    var step: Step
        get() = runCatching { Step.valueOf(prefs.getString("step", null) ?: "PICK") }.getOrDefault(Step.PICK)
        set(v) = prefs.edit().putString("step", v.name).apply()

    /** Set when the user says the TV is restarting after the password was set. */
    var restartPendingSince: Long
        get() = prefs.getLong("restartSince", 0L)
        set(v) = prefs.edit().putLong("restartSince", v).apply()

    var lastVerifiedVersion: String?
        get() = prefs.getString("verifiedVersion", null)
        set(v) = prefs.edit().putString("verifiedVersion", v).apply()

    fun clear() = prefs.edit().clear().apply()
}
