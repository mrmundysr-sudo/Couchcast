package com.mundymorning.couchcast

import android.content.Context
import com.mundymorning.couchcast.roku.Room
import org.json.JSONArray
import org.json.JSONObject

class RoomStore(context: Context) {
    private val prefs = context.getSharedPreferences("couchcast", Context.MODE_PRIVATE)

    fun load(): List<Room> {
        val raw = prefs.getString("rooms", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Room(o.getString("ip"), o.getString("name"), o.optString("model"), o.optBoolean("isTv"))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(rooms: List<Room>) {
        val arr = JSONArray()
        rooms.forEach {
            arr.put(JSONObject().put("ip", it.ip).put("name", it.name).put("model", it.model).put("isTv", it.isTv))
        }
        prefs.edit().putString("rooms", arr.toString()).apply()
    }

    var lastIp: String?
        get() = prefs.getString("lastIp", null)
        set(v) = prefs.edit().putString("lastIp", v).apply()
}
