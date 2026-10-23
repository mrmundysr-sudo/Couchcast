package com.mundymorning.couchcast.install

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mundymorning.couchcast.RoomStore
import com.mundymorning.couchcast.roku.Room
import com.mundymorning.couchcast.roku.RokuApp
import com.mundymorning.couchcast.roku.RokuClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

enum class CheckStatus { PENDING, OK, FAILED }
data class CheckRow(val label: String, val status: CheckStatus, val note: String = "")

data class Problem(val title: String, val body: String)

data class InstallUi(
    val step: Step = Step.PICK,
    val ip: String? = null,
    val roomName: String = "",
    val checks: List<CheckRow> = emptyList(),
    val problem: Problem? = null,
    val receiver: ReceiverState = ReceiverState.UNKNOWN,
    val installedVersion: String? = null,
    val bundledVersion: String = "",
    val busy: String? = null,
    val error: String? = null,
    val confirmReplaceOf: String? = null,
    val hasSavedPassword: Boolean = false,
    val restartWaitSeconds: Int = 0,
    val verified: Boolean = false,
    val testNote: String? = null,
    val resumeAvailable: Boolean = false,
)

class InstallViewModel(app: Application) : AndroidViewModel(app) {
    private val store = InstallStore(app)
    private val pwStore = DevPasswordStore(app)
    private val roomStore = RoomStore(app)
    private val dev = RokuDevClient()
    private val pkg: ReceiverPackage = ReceiverPackage.load(app)

    private val _ui = MutableStateFlow(initial())
    val ui: StateFlow<InstallUi> = _ui.asStateFlow()

    private var job: Job? = null

    private fun initial(): InstallUi {
        val ip = store.ip
        val resumable = ip != null && store.step !in setOf(Step.PICK, Step.DONE)
        return InstallUi(
            ip = ip ?: roomStore.lastIp ?: roomStore.load().firstOrNull()?.ip,
            roomName = store.roomName,
            bundledVersion = pkg.versionName,
            resumeAvailable = resumable,
        )
    }

    fun rooms(): List<Room> = roomStore.load()

    // ---- selection -------------------------------------------------------------------------

    fun choose(ip: String, name: String) {
        _ui.update { it.copy(ip = ip, roomName = name, error = null) }
    }

    /** Manual IP: confirm it is really a Roku before accepting it. */
    fun chooseManual(rawIp: String, onVerified: (String) -> Unit) {
        val ip = rawIp.trim()
        if (ip.isEmpty()) return
        job?.cancel()
        job = viewModelScope.launch {
            _ui.update { it.copy(busy = "Looking for a Roku at $ip…", error = null) }
            val info = try { RokuClient.deviceInfo(ip) } catch (_: IOException) { null }
            if (info == null) {
                _ui.update { it.copy(busy = null, error = "Couldn't reach a Roku at $ip. Check the address and that your phone is on the same Wi-Fi.") }
            } else {
                onVerified(ip)
                _ui.update { it.copy(busy = null, ip = ip, roomName = info.name) }
            }
        }
    }

    // ---- navigation ------------------------------------------------------------------------

    fun start() {
        val ip = _ui.value.ip ?: return
        store.ip = ip
        store.roomName = _ui.value.roomName
        runCheck()
    }

    fun resume() {
        store.ip?.let { ip -> _ui.update { it.copy(ip = ip, roomName = store.roomName) } }
        start()
    }

    fun startOver() {
        job?.cancel()
        store.clear()
        _ui.update { InstallUi(ip = it.ip, roomName = it.roomName, bundledVersion = pkg.versionName) }
    }

    /** Leaves the wizard but keeps progress so it can be resumed. */
    fun cancel() {
        job?.cancel()
        _ui.update { it.copy(busy = null, resumeAvailable = store.ip != null && store.step !in setOf(Step.PICK, Step.DONE)) }
    }

    /** @return false if already at the first screen (caller should close the wizard). */
    fun back(): Boolean {
        job?.cancel()
        val next = when (_ui.value.step) {
            Step.PICK -> return false
            Step.DEV_MODE, Step.PROBLEM, Step.DONE, Step.CHECKING -> Step.PICK
            Step.RESTART, Step.CREDENTIALS -> Step.DEV_MODE
            Step.INSTALLING, Step.VERIFYING -> return true // uploading; do not interrupt
        }
        setStep(next, error = null, busy = null)
        return true
    }

    private fun setStep(step: Step, error: String? = _ui.value.error, busy: String? = null, problem: Problem? = null) {
        store.step = step
        _ui.update { it.copy(step = step, error = error, busy = busy, problem = problem, confirmReplaceOf = null) }
    }

    // ---- checks ----------------------------------------------------------------------------

    fun runCheck() {
        val ip = _ui.value.ip ?: return
        job?.cancel()
        job = viewModelScope.launch {
            setStep(Step.CHECKING, error = null)
            var rows = listOf(
                CheckRow("Phone is on Wi-Fi or Ethernet", CheckStatus.PENDING),
                CheckRow("Roku answers on your network", CheckStatus.PENDING),
                CheckRow("Roku developer installer is available", CheckStatus.PENDING),
            )
            fun show(i: Int, s: CheckStatus, note: String = "") {
                rows = rows.toMutableList().also { it[i] = it[i].copy(status = s, note = note) }
                _ui.update { it.copy(checks = rows) }
            }
            _ui.update { it.copy(checks = rows) }
            val pending = store.restartPendingSince > 0

            if (!networkUp()) {
                show(0, CheckStatus.FAILED, "not on Wi-Fi/Ethernet")
                finishCheck(Reachability.NETWORK_DOWN, ReceiverState.UNKNOWN, pending); return@launch
            }
            show(0, CheckStatus.OK)

            val name: String?
            try {
                val info = RokuClient.deviceInfo(ip)
                name = info?.name
            } catch (_: IOException) {
                show(1, CheckStatus.FAILED, "Network control is turned off")
                finishCheck(Reachability.ECP_BLOCKED, ReceiverState.UNKNOWN, pending); return@launch
            }
            if (name == null) {
                show(1, CheckStatus.FAILED, "no answer on port 8060")
                finishCheck(Reachability.ROKU_UNREACHABLE, ReceiverState.UNKNOWN, pending); return@launch
            }
            show(1, CheckStatus.OK)
            if (_ui.value.roomName.isBlank()) _ui.update { it.copy(roomName = name) }

            val (probe, why) = dev.probeInstallerDetail(ip)
            if (probe == InstallerProbe.CLOSED) {
                show(2, CheckStatus.FAILED, why)
                finishCheck(Reachability.DEV_MODE_OFF, ReceiverState.UNKNOWN, pending); return@launch
            }
            show(2, CheckStatus.OK)
            val apps: List<RokuApp>? = try { RokuClient.apps(ip) } catch (_: IOException) { null }
            val state = InstallPlanner.classify(apps, pkg)
            _ui.update { it.copy(installedVersion = apps?.firstOrNull { a -> a.id == InstallPlanner.DEV_APP_ID }?.version) }
            finishCheck(Reachability.INSTALLER_READY, state, pending)
        }
    }

    private fun finishCheck(reach: Reachability, receiver: ReceiverState, pending: Boolean) {
        val ip = _ui.value.ip.orEmpty()
        if (reach == Reachability.INSTALLER_READY) store.restartPendingSince = 0
        val step = InstallPlanner.decide(reach, pending, receiver)
        _ui.update { it.copy(receiver = receiver, hasSavedPassword = pwStore.has(ip)) }
        when (step) {
            Step.PROBLEM -> setStep(Step.PROBLEM, problem = problemFor(reach))
            Step.RESTART -> { setStep(Step.RESTART); waitForRestart() }
            Step.DONE -> {
                // Receiver already installed: this is the strongest check there is (Roku lists it itself).
                _ui.update { it.copy(verified = true) }
                setStep(Step.DONE)
            }
            else -> setStep(step)
        }
    }

    private fun problemFor(r: Reachability) = when (r) {
        Reachability.NETWORK_DOWN -> Problem(
            "Your phone isn't on a home network",
            "Connect your phone to the same Wi-Fi network as your Roku, then try again."
        )
        Reachability.ECP_BLOCKED -> Problem(
            "The Roku is refusing network control",
            "On the TV open Settings > System > Advanced system settings > Control by mobile apps, set Network access to Default or Permissive, then try again."
        )
        else -> Problem(
            "Can't find your Roku",
            "Make sure the TV is on, and that your phone is on the same Wi-Fi network. If the Roku's address changed, go back and add it by its current IP address (Settings > Network > About on the TV)."
        )
    }

    private fun networkUp(): Boolean {
        val cm = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    // ---- developer mode + restart ---------------------------------------------------------------

    /** The user says they finished the on-TV steps and the Roku is restarting. */
    fun passwordSet() {
        store.restartPendingSince = System.currentTimeMillis()
        setStep(Step.RESTART)
        waitForRestart()
    }

    private fun waitForRestart() {
        val ip = _ui.value.ip ?: return
        job?.cancel()
        job = viewModelScope.launch {
            val started = System.currentTimeMillis()
            while (System.currentTimeMillis() - started < RESTART_TIMEOUT_MS) {
                _ui.update { it.copy(restartWaitSeconds = ((System.currentTimeMillis() - started) / 1000).toInt()) }
                val ecp = try { RokuClient.deviceInfo(ip) != null } catch (_: IOException) { false }
                if (ecp && dev.probeInstaller(ip) == InstallerProbe.OPEN) {
                    // Roku is back. Stop "waiting" for good: the follow-up check must land on a
                    // stable screen that shows its results, never loop back here.
                    store.restartPendingSince = 0
                    runCheck(); return@launch
                }
                delay(4000)
            }
            _ui.update { it.copy(error = "Still waiting. Make sure the TV is on and finished restarting, then tap Check again.") }
        }
    }

    // ---- install ---------------------------------------------------------------------------

    fun install(password: String, remember: Boolean, replaceConfirmed: Boolean = false) {
        val ip = _ui.value.ip ?: return
        val state = _ui.value
        if (password.isEmpty()) {
            _ui.update { it.copy(error = "Enter the developer password you chose on the TV.") }; return
        }
        if (state.receiver == ReceiverState.OTHER_DEV_APP && !replaceConfirmed) {
            _ui.update { it.copy(confirmReplaceOf = "another sideloaded channel") }; return
        }
        job?.cancel()
        job = viewModelScope.launch {
            setStep(Step.INSTALLING, error = null, busy = "Uploading the receiver to your Roku…")
            when (val out = dev.install(ip, "rokudev", password, pkg.zip)) {
                is InstallOutcome.Accepted -> {
                    if (remember) pwStore.save(ip, password) else pwStore.clear(ip)
                    verifyInstalled()
                }
                InstallOutcome.BadPassword -> {
                    pwStore.clear(ip)
                    backToCredentials("The Roku didn't accept that password. It is the one you set in the Developer Settings screen on the TV (not your Roku account password). Passwords are case-sensitive.")
                }
                is InstallOutcome.CompileFailed -> backToCredentials("The Roku couldn't start the receiver package: ${out.detail}. This is a problem with the app's receiver, not your TV. Please report it.")
                is InstallOutcome.Rejected -> backToCredentials("The Roku refused the install: ${out.detail}")
                InstallOutcome.InstallerClosed -> {
                    store.restartPendingSince = 0
                    runCheck()
                }
                is InstallOutcome.Unreachable -> backToCredentials("Lost contact with the Roku: ${out.detail} Check that the TV is on and on the same Wi-Fi, then retry.")
                is InstallOutcome.Unknown -> backToCredentials("${out.detail} Nothing has been confirmed, so please retry.")
            }
        }
    }

    fun installWithSavedPassword() {
        val ip = _ui.value.ip ?: return
        val pw = pwStore.load(ip)
        if (pw == null) {
            _ui.update { it.copy(hasSavedPassword = false, error = "The saved password could not be read. Please enter it again.") }
        } else install(pw, remember = true)
    }

    fun forgetSavedPassword() {
        _ui.value.ip?.let { pwStore.clear(it) }
        _ui.update { it.copy(hasSavedPassword = false) }
    }

    private fun backToCredentials(msg: String) = setStep(Step.CREDENTIALS, error = msg)

    /** Success is only claimed when the Roku itself lists the receiver. */
    fun verifyInstalled() {
        val ip = _ui.value.ip ?: return
        job?.cancel()
        job = viewModelScope.launch {
            setStep(Step.VERIFYING, error = null, busy = "Checking that the Roku lists the receiver…")
            repeat(8) {
                val apps = try { RokuClient.apps(ip) } catch (_: IOException) { null }
                val state = InstallPlanner.classify(apps, pkg)
                if (state == ReceiverState.CURRENT) {
                    val v = apps?.firstOrNull { a -> a.id == InstallPlanner.DEV_APP_ID }?.version
                    store.lastVerifiedVersion = v
                    _ui.update { it.copy(receiver = state, installedVersion = v, verified = true, testNote = null) }
                    setStep(Step.DONE)
                    return@launch
                }
                delay(2500)
            }
            _ui.update { it.copy(verified = false) }
            setStep(
                Step.CREDENTIALS,
                error = "The Roku accepted the file, but it isn't listing the receiver yet, so we can't confirm the install. Tap Check again, or retry the install."
            )
        }
    }

    /** Reinstall or update even when the receiver is already current. */
    fun reinstall() {
        _ui.update { it.copy(verified = false) }
        setStep(Step.CREDENTIALS, error = null)
    }

    // ---- test ------------------------------------------------------------------------------

    fun launchReceiver() {
        val ip = _ui.value.ip ?: return
        viewModelScope.launch {
            _ui.update { it.copy(testNote = "Starting the receiver…") }
            val sent = dev.launchDevApp(ip)
            delay(2500)
            val active = dev.activeAppId(ip)
            _ui.update {
                it.copy(
                    testNote = when {
                        active == InstallPlanner.DEV_APP_ID -> "Confirmed: the Roku reports the Couchcast Receiver is running. Playing video through it still needs to be tried from the cast screen."
                        !sent -> "The Roku didn't accept the launch command."
                        else -> "Launch was sent, but the Roku isn't reporting the receiver as running. Look at the TV to check."
                    }
                )
            }
        }
    }

    override fun onCleared() {
        job?.cancel()
    }

    private companion object {
        const val RESTART_TIMEOUT_MS = 4 * 60 * 1000L
    }
}
