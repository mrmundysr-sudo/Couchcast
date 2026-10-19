package com.mundymorning.couchcast

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mundymorning.couchcast.roku.Room
import com.mundymorning.couchcast.roku.RokuApp
import com.mundymorning.couchcast.roku.RokuClient
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = RoomStore(app)

    private val _rooms = MutableStateFlow(store.load())
    val rooms: StateFlow<List<Room>> = _rooms.asStateFlow()

    private val _currentIp = MutableStateFlow(store.lastIp ?: _rooms.value.firstOrNull()?.ip)
    val currentIp: StateFlow<String?> = _currentIp.asStateFlow()

    private val _apps = MutableStateFlow<List<RokuApp>>(emptyList())
    val apps: StateFlow<List<RokuApp>> = _apps.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        refreshRooms()
        loadApps()
    }

    fun current(): Room? = _rooms.value.firstOrNull { it.ip == _currentIp.value }

    fun dismissMessage() {
        _message.value = null
    }

    fun selectRoom(ip: String) {
        _currentIp.value = ip
        store.lastIp = ip
        _apps.value = emptyList()
        loadApps()
    }

    private fun persist() = store.save(_rooms.value)

    fun renameRoom(ip: String, name: String) {
        _rooms.update { l -> l.map { if (it.ip == ip) it.copy(name = name.ifBlank { it.name }) else it } }
        persist()
    }

    fun removeRoom(ip: String) {
        _rooms.update { l -> l.filterNot { it.ip == ip } }
        persist()
        if (_currentIp.value == ip) {
            val next = _rooms.value.firstOrNull()?.ip
            _currentIp.value = next
            store.lastIp = next
            _apps.value = emptyList()
            loadApps()
        }
    }

    private fun upsert(ip: String, name: String, model: String, isTv: Boolean) {
        _rooms.update { l ->
            if (l.any { it.ip == ip }) {
                l.map { if (it.ip == ip) it.copy(model = model, isTv = isTv, online = true) else it }
            } else {
                l + Room(ip, name, model, isTv, true)
            }
        }
        persist()
        if (_currentIp.value == null) selectRoom(ip)
    }

    fun scan() {
        if (_scanning.value) return
        viewModelScope.launch {
            _scanning.value = true
            try {
                val ips = RokuClient.discover(getApplication())
                if (ips.isEmpty()) {
                    _message.value = "No Roku found. Make sure the phone is on the same Wi-Fi, or add the Roku by IP address."
                }
                ips.map { ip -> async { ip to RokuClient.deviceInfo(ip) } }.awaitAll().forEach { (ip, info) ->
                    if (info != null) upsert(ip, info.name, info.model, info.isTv)
                }
            } finally {
                _scanning.value = false
            }
        }
    }

    fun addByIp(ip: String) {
        val clean = ip.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            try {
                val info = RokuClient.deviceInfo(clean)
                if (info == null) {
                    _message.value = "Couldn't reach a Roku at $clean. Check the address and that you're on the same Wi-Fi."
                } else {
                    upsert(clean, info.name, info.model, info.isTv)
                }
            } catch (e: IOException) {
                _message.value = networkHint()
            }
        }
    }

    fun refreshRooms() {
        viewModelScope.launch {
            _rooms.value.map { r ->
                async {
                    val info = try { RokuClient.deviceInfo(r.ip) } catch (_: IOException) { null }
                    _rooms.update { l ->
                        l.map {
                            if (it.ip == r.ip) {
                                if (info != null) it.copy(model = info.model, isTv = info.isTv, online = true)
                                else it.copy(online = false)
                            } else it
                        }
                    }
                }
            }.awaitAll()
            persist()
        }
    }

    fun loadApps() {
        val ip = _currentIp.value ?: return
        viewModelScope.launch {
            try {
                _apps.value = RokuClient.apps(ip).sortedBy { it.name.lowercase() }
            } catch (e: IOException) {
                _message.value = networkHint()
            }
        }
    }

    private fun networkHint() =
        "The Roku refused control. On the TV, open Settings > System > Advanced system settings > " +
            "Control by mobile apps and set Network access to Permissive or Default."

    fun key(key: String) {
        val ip = _currentIp.value ?: run {
            _message.value = "Add a Room first."
            return
        }
        viewModelScope.launch {
            try {
                if (!RokuClient.key(ip, key)) {
                    _rooms.update { l -> l.map { if (it.ip == ip) it.copy(online = false) else it } }
                }
            } catch (e: IOException) {
                _message.value = networkHint()
            }
        }
    }

    fun typeText(text: String) {
        val ip = _currentIp.value ?: return
        viewModelScope.launch {
            try { RokuClient.typeText(ip, text) } catch (e: IOException) { _message.value = networkHint() }
        }
    }

    fun launch(app: RokuApp) {
        val ip = _currentIp.value ?: return
        viewModelScope.launch {
            try { RokuClient.launch(ip, app.id) } catch (e: IOException) { _message.value = networkHint() }
        }
    }
}
