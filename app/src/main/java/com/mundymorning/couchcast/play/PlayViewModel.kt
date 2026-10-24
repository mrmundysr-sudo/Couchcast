package com.mundymorning.couchcast.play

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mundymorning.couchcast.install.InstallPlanner
import com.mundymorning.couchcast.install.ReceiverPackage
import com.mundymorning.couchcast.install.ReceiverState
import com.mundymorning.couchcast.playback.*
import com.mundymorning.couchcast.relay.Relay
import com.mundymorning.couchcast.relay.RelayOpen
import com.mundymorning.couchcast.relay.RelaySpec
import com.mundymorning.couchcast.relay.RelayService
import com.mundymorning.couchcast.relay.RequestContext
import com.mundymorning.couchcast.roku.RokuApp
import com.mundymorning.couchcast.roku.RokuClient
import com.mundymorning.couchcast.signin.AndroidCookieSource
import com.mundymorning.couchcast.signin.SignInStore
import com.mundymorning.couchcast.signin.SiteSessions
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

enum class PlayPhase { IDLE, SCANNING, SENDING, PLAYING, NOT_CONFIRMED, FAILED, NEEDS_RECEIVER, NO_VIDEO, CHOOSE }

enum class ChannelState { UNKNOWN, INSTALLED, MISSING }
enum class RelayState { OFF, STARTING, ACTIVE }

/** What the entered link looks like (a recognised streaming service?) and whether that service's channel is on the Roku. */
data class LinkInfo(val service: ServiceMatch? = null, val channel: ChannelState = ChannelState.UNKNOWN)

data class PlayUi(
    val phase: PlayPhase = PlayPhase.IDLE,
    val message: String? = null,
    val candidates: List<String> = emptyList(),
    val chosen: String? = null,
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    val diagnosing: Boolean = false,
    val diagnosis: Diagnosis? = null,
    val offers: List<Offer> = emptyList(),
    val relay: RelayState = RelayState.OFF,
    /** Redacted, secret-free lines for troubleshooting (direct vs relay). */
    val details: List<String> = emptyList(),
)

class PlayViewModel(app: Application) : AndroidViewModel(app) {
    private val player = ReceiverPlayer()
    private val pkg = ReceiverPackage.load(app)
    private val scanner = PageScanner(app)
    private var job: kotlinx.coroutines.Job? = null
    private var lastScan: ScanResult? = null
    private val launcher = RokuChannelLauncher()
    private val probe = StreamProbe()
    private val signInStore = SignInStore(app)
    private var diagJob: kotlinx.coroutines.Job? = null
    private var relayKey: Int? = null
    private var lastEntered: String? = null
    private var lastRokuDrm: String? = null
    private var receiverInstalled: Boolean? = null

    private val _ui = MutableStateFlow(PlayUi())
    val ui: StateFlow<PlayUi> = _ui.asStateFlow()

    private val _link = MutableStateFlow(LinkInfo())
    val link: StateFlow<LinkInfo> = _link.asStateFlow()

    private val _channels = MutableStateFlow<List<Pair<StreamingService, RokuApp>>?>(null)
    /** Installed channels of known services on this Roku; null until loaded. */
    val channels: StateFlow<List<Pair<StreamingService, RokuApp>>?> = _channels.asStateFlow()

    private val _sites = MutableStateFlow(signInStore.hosts())
    val sites: StateFlow<List<String>> = _sites.asStateFlow()

    val capabilities: ReceiverCapabilities get() = CapabilityDetector.roku(receiverInstalled)

    /** A direct video link plays straight away; a web page is scanned for video first. */
    fun play(ip: String?, rawUrl: String, forceMp4: Boolean = false) {
        if (ip == null) { fail("Add a Roku in the Rooms tab first."); return }
        val url = normalize(rawUrl) ?: run { fail("Enter a link that starts with http:// or https://."); return }
        lastEntered = url
        diagJob?.cancel()
        val format = ReceiverPlayer.formatFor(url) ?: if (forceMp4) "mp4" else null
        if (format != null) { lastScan = null; send(ip, url, format); return }
        job?.cancel()
        job = viewModelScope.launch {
            _ui.value = PlayUi(PlayPhase.SCANNING, "Looking for video on that page… this can take up to 15 seconds.")
            val scan = try { scanner.scan(url) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { ScanResult(emptyList(), url, "") }
            lastScan = scan
            val found = scan.urls
            when (found.size) {
                0 -> _ui.value = PlayUi(PlayPhase.NO_VIDEO,
                    "No playable video found on that page. Sites like YouTube, Netflix, Hulu and Disney+ protect their video or hide it inside their own player, so it can't be sent to a Roku this way. Try a page with an ordinary video, or a direct .mp4 / .m3u8 link.")
                1 -> send(ip, found[0], ReceiverPlayer.formatFor(found[0]) ?: "mp4", scan.pageUrl, scan.userAgent)
                else -> _ui.value = PlayUi(PlayPhase.CHOOSE, "Found ${found.size} videos on that page. Pick one:", found)
            }
        }
    }

    fun playCandidate(ip: String?, url: String) {
        if (ip == null) return
        send(ip, url, ReceiverPlayer.formatFor(url) ?: "mp4", lastScan?.pageUrl, lastScan?.userAgent)
    }

    fun cancel() {
        job?.cancel()
        _ui.value = PlayUi()
    }

    private fun send(ip: String, url: String, format: String, referer: String? = null, userAgent: String? = null) {
        job?.cancel()
        job = viewModelScope.launch {
            _ui.value = PlayUi(PlayPhase.SENDING, "Checking the receiver on your Roku…", chosen = url, status = PlaybackStatus.CHECKING)
            val apps = try { RokuClient.apps(ip) } catch (_: IOException) { null }
            receiverInstalled = if (apps == null) null else InstallPlanner.classify(apps, pkg) == ReceiverState.CURRENT
            when (InstallPlanner.classify(apps, pkg)) {
                ReceiverState.CURRENT -> {}
                ReceiverState.UNKNOWN -> { fail("Couldn't reach the Roku. Check that the TV is on and on the same Wi-Fi."); return@launch }
                ReceiverState.NONE -> { needsReceiver("The Couchcast Receiver isn't installed on this Roku yet."); return@launch }
                ReceiverState.OUTDATED -> { needsReceiver("The receiver on this Roku is out of date. Update it first."); return@launch }
                ReceiverState.OTHER_DEV_APP -> { needsReceiver("A different sideloaded channel is on this Roku. Install the receiver first."); return@launch }
            }
            _ui.update { it.copy(message = "Sending the video to your Roku…") }
            val out = player.play(ip, url, format, referer = referer, userAgent = userAgent)
            val chosenUrl = url
            lastRokuDrm = out.last?.drm
            _ui.value = when (out.result) {
                PlayResult.PLAYING -> PlayUi(PlayPhase.PLAYING, "Confirmed: the Roku reports it is playing." + formatNote(out.last), status = PlaybackStatus.PLAYING_DIRECT)
                PlayResult.STOPPED_EARLY -> PlayUi(PlayPhase.FAILED, stoppedMessage(out))
                PlayResult.RECEIVER_OPEN_NOT_CONFIRMED -> PlayUi(PlayPhase.NOT_CONFIRMED,
                    "The receiver opened, but the Roku hasn't reported playing. Look at the TV: it shows the reason if the video failed. The link may need sign-in, or be a format the Roku can't play.")
                PlayResult.NOT_STARTED -> PlayUi(PlayPhase.FAILED, "The Roku didn't start the receiver. Make sure the TV is on and try again.")
                PlayResult.SEND_FAILED -> PlayUi(PlayPhase.FAILED, "The Roku didn't accept the command. Check Wi-Fi, and that network control is allowed on the TV.")
            }.copy(chosen = chosenUrl)
            _ui.update { it.copy(details = it.details + "Direct playback: ${out.result} (${Redactor.url(chosenUrl)})") }
            if (out.result == PlayResult.STOPPED_EARLY || out.result == PlayResult.RECEIVER_OPEN_NOT_CONFIRMED) diagnose(ip, chosenUrl, referer, userAgent)
        }
    }

    private fun formatNote(i: PlayerInfo?): String {
        i ?: return ""
        val bits = listOfNotNull(i.video?.takeIf { it.isNotBlank() }?.let { "video $it" }, i.resolution?.takeIf { it.isNotBlank() },
            i.audio?.takeIf { it.isNotBlank() }?.let { "audio $it" })
        return if (bits.isEmpty()) "" else " (${bits.joinToString(", ")})"
    }

    /** Plain-English reason from what the Roku itself reported. */
    private fun stoppedMessage(out: PlayOutcome): String {
        val i = out.last
        val noVideo = i != null && i.video.isNullOrBlank() && !i.audio.isNullOrBlank()
        val drm = i?.drm?.takeIf { it.isNotBlank() && !it.equals("none", true) }
        val why = when {
            drm != null -> "The Roku says this video is copy-protected ($drm), which the receiver can't play."
            noVideo -> "The Roku played the sound but could not decode the picture, which usually means the video uses a format this Roku doesn't support."
            else -> "The link may have expired, need a sign-in, or use a format this Roku can't play."
        }
        val seen = formatNote(i).trim().removePrefix("(").removeSuffix(")")
        return "The video started, then the Roku stopped it after about ${out.playedSeconds} seconds. $why" +
            (if (seen.isNotEmpty()) " The Roku reported: $seen." else "") + " The TV screen shows more detail."
    }

    /** Back stops playback in the receiver. */
    fun stop(ip: String?) {
        ip ?: return
        viewModelScope.launch {
            try { RokuClient.key(ip, "Back") } catch (_: IOException) {}
            endRelay()
            _ui.value = PlayUi()
        }
    }

    // ---- 1. Roku channel links ------------------------------------------------------------------

    /** Called as the user types/pastes: recognises streaming-service links and checks the channel is installed. */
    fun onLinkChanged(raw: String, ip: String?) {
        val match = StreamingServices.recognize(raw)
        if (match?.service?.id == _link.value.service?.service?.id && match?.contentId == _link.value.service?.contentId) return
        _link.value = LinkInfo(match, ChannelState.UNKNOWN)
        if (match == null || ip == null) return
        viewModelScope.launch {
            val apps = try { RokuClient.apps(ip) } catch (_: IOException) { null } ?: return@launch
            if (_link.value.service?.service?.id == match.service.id)
                _link.value = _link.value.copy(channel = if (ChannelMatcher.find(match.service, apps) != null) ChannelState.INSTALLED else ChannelState.MISSING)
        }
    }

    fun loadChannels(ip: String?) {
        ip ?: return
        viewModelScope.launch {
            val apps = try { RokuClient.apps(ip) } catch (_: IOException) { null }
            _channels.value = apps?.let { ChannelMatcher.installedServices(it) }
        }
    }

    fun openChannel(ip: String?, match: ServiceMatch? = _link.value.service) {
        if (ip == null || match == null) return
        job?.cancel()
        job = viewModelScope.launch {
            _ui.value = PlayUi(PlayPhase.SENDING, "Opening ${match.service.displayName} on your Roku…", status = PlaybackStatus.OPENING_CHANNEL)
            val name = match.service.displayName
            _ui.value = when (val r = launcher.open(ip, match)) {
                is ChannelLaunch.Opened -> PlayUi(PlayPhase.PLAYING, "$name is open on your TV." +
                    (if (r.titleLinkSent) " A link to the title was sent, but only the channel can open it. If you see its home screen instead, choose the title with your remote." else " Choose what to watch with your remote or the Remote tab."),
                    status = PlaybackStatus.CHANNEL_OPEN)
                is ChannelLaunch.NotConfirmed -> PlayUi(PlayPhase.NOT_CONFIRMED,
                    "The command to open $name was accepted, but the Roku hasn't reported it opening. Look at the TV.", status = PlaybackStatus.CHANNEL_NOT_CONFIRMED)
                is ChannelLaunch.NotInstalled -> PlayUi(PlayPhase.FAILED,
                    "$name isn't installed on this Roku. On the Roku, open Streaming Channels from the Home screen, search for $name, and add it. Then try again.", status = PlaybackStatus.FAILED)
                is ChannelLaunch.Failed -> PlayUi(PlayPhase.FAILED, r.reason, status = PlaybackStatus.FAILED)
            }
        }
    }

    // ---- 3. Diagnostics ------------------------------------------------------------------------

    private fun diagnose(ip: String, url: String, referer: String?, ua: String?) {
        diagJob?.cancel()
        _ui.update { it.copy(diagnosing = true) }
        diagJob = viewModelScope.launch {
            val userHost = lastEntered?.let { SiteSession.hostOf(it) }
            val privateOk = if (userHost != null && UpstreamPolicy.checkUrl("http://$userHost/", emptySet()) != null) setOf(userHost) else emptySet()
            val p = try { probe.probe(url, referer ?: lastScan?.pageUrl, ua ?: lastScan?.userAgent, AndroidCookieSource, privateOk) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
            val d = FailureClassifier.diagnose(DiagnoseInput(p, lastRokuDrm, _link.value.service ?: StreamingServices.recognize(lastEntered.orEmpty()),
                hasSiteSession = SiteSessions.hasSession(url)))
            val offers = PlaybackPlanner.usable(d.offers, capabilities)
            _ui.update {
                it.copy(diagnosing = false, diagnosis = d, offers = offers, status = PlaybackPlanner.statusFor(d),
                    details = it.details + "Phone check: ${d.kind}" + (p?.let { r -> " (no headers: ${r.manifest.bare.status}, browser-like: ${r.manifest.context.status}" +
                        (r.manifest.session?.let { s -> ", with your session: ${s.status}" } ?: "") + ")" }.orEmpty()))
            }
        }
    }

    // ---- 3. Phone relay ------------------------------------------------------------------------

    private fun lanIp(): String? {
        val cm = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(net) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return null
        return NetworkAddress.pickLanIpv4(cm.getLinkProperties(net)?.linkAddresses.orEmpty().map { it.address })
    }

    fun tryRelay(ip: String?) {
        val url = _ui.value.chosen
        if (ip == null || url == null) { fail("Pick a video first, then try the phone relay."); return }
        val format = ReceiverPlayer.formatFor(url) ?: "mp4"
        val phoneIp = lanIp() ?: run { fail("Connect your phone to the same Wi-Fi as the Roku to use the phone relay."); return }
        job?.cancel()
        val details = _ui.value.details
        job = viewModelScope.launch {
            _ui.value = PlayUi(PlayPhase.SENDING, "Starting the phone relay…", chosen = url, status = PlaybackStatus.STARTING_RELAY, relay = RelayState.STARTING, details = details)
            val scan = lastScan
            val hosts = (listOfNotNull(scan?.pageUrl, url) + scan?.urls.orEmpty()).mapNotNull { SiteSession.hostOf(it) }.toSet()
            // The user's session is used only when the check said the site needs it, and only for this playback.
            val useSession = _ui.value.diagnosis?.kind == FailureKind.NEEDS_SESSION || _useSession
            val ctx = RequestContext(scan?.pageUrl, scan?.userAgent, AndroidCookieSource, useSession && SiteSessions.hasSession(url), hosts)
            when (val o = Relay.manager.open(RelaySpec(phoneIp, ip, url, lastEntered ?: url, ctx, format))) {
                is RelayOpen.Refused -> _ui.value = PlayUi(PlayPhase.FAILED, o.reason, chosen = url, status = PlaybackStatus.FAILED, details = details)
                is RelayOpen.Ready -> {
                    relayKey = o.key
                    RelayService.start(getApplication(), ip)
                    _ui.update { it.copy(message = "Sending the relay link to your Roku…") }
                    val out = player.play(ip, o.playUrl, o.format)
                    val stats = Relay.manager.stats(o.key)
                    val line = "Phone relay: ${out.result} (${stats?.summary().orEmpty()})"
                    if (out.result == PlayResult.PLAYING) {
                        _ui.value = PlayUi(PlayPhase.PLAYING,
                            "Playing through your phone." + formatNote(out.last) + " Keep the phone on the same Wi-Fi, powered on, until the video ends. Remote pause and seek work as usual.",
                            chosen = url, status = PlaybackStatus.PLAYING_RELAY, relay = RelayState.ACTIVE, details = details + line)
                    } else {
                        val why = RelayFailure.explain(stats?.requests?.get() ?: 0, stats?.upstreamStatus.orEmpty(), stats?.drmDetected, out.result, stats?.lastError)
                        endRelay()
                        _ui.value = PlayUi(PlayPhase.FAILED, "Phone relay didn't work. $why", chosen = url,
                            status = if (stats?.drmDetected != null) PlaybackStatus.UNSUPPORTED_PROTECTED else PlaybackStatus.FAILED,
                            offers = listOf(Offer.MIRROR), details = details + line)
                    }
                }
            }
        }
    }

    private var _useSession = false
    /** Lets the user choose to include their signed-in session in a relay even when the check didn't require it. */
    fun setUseSession(use: Boolean) { _useSession = use }

    private fun endRelay() {
        relayKey?.let { Relay.manager.close(it) }
        relayKey = null
        RelayService.stop(getApplication())
        _ui.update { it.copy(relay = RelayState.OFF) }
    }

    // ---- 4. Sites and sign-in ------------------------------------------------------------------

    /** Where sign-in should start: the page the video came from, else what the user pasted. */
    fun signInStartUrl(): String? = lastScan?.pageUrl ?: lastEntered

    fun signedIn(host: String) { signInStore.add(host); _sites.value = signInStore.hosts() }

    fun logOut(host: String) { SiteSessions.clearSite(host); signInStore.remove(host); _sites.value = signInStore.hosts() }

    fun clearAllSites() { SiteSessions.clearAll(); signInStore.clear(); _sites.value = emptyList() }

    /** After signing in, look again with the new session. */
    fun rescan(ip: String?, signedInHost: String?) {
        val entered = lastEntered ?: return
        val sameSite = signedInHost != null && listOfNotNull(SiteSession.hostOf(entered), lastScan?.pageUrl?.let { SiteSession.hostOf(it) })
            .any { it == signedInHost || it.endsWith(".$signedInHost") || signedInHost.endsWith(".$it") }
        if (sameSite) play(ip, entered)   // never replay an unrelated link just because the user signed in somewhere
    }

    /** True when the in-app browser already holds a session for the chosen video's site. */
    fun hasSessionForChosen(): Boolean = _ui.value.chosen?.let { SiteSessions.hasSession(it) } == true || lastScan?.pageUrl?.let { SiteSessions.hasSession(it) } == true

    private fun needsReceiver(msg: String) { _ui.value = PlayUi(PlayPhase.NEEDS_RECEIVER, msg) }
    private fun fail(msg: String) { _ui.value = PlayUi(PlayPhase.FAILED, msg) }

    private fun normalize(raw: String): String? = LinkInput.normalize(raw)
}
