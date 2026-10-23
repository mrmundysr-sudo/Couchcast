# Universal playback expansion

## Preserved working baseline
The last revision verified working on a real Roku TV (install wizard, page scan, multi-stream
selection, independent Roku playback, remote pause/resume/seek) is
`a00c55c00b70ac15274b1d13529c21acff06a168` (APK `apk-11`, branch `claude/roku-receiver-installer`).
No tag was created or moved. This work lives on `claude/universal-playback`, branched from that commit.

## Principle: progressive enhancement
Direct Roku playback stays the default and is untouched. Other methods are only *offered* after a
failure, a recognised streaming-service link, or when the user opens "More ways to play".

## What was added
| Feature | Where the user finds it | Code |
|---|---|---|
| Roku channel links | Play tab: a card appears when the link is a recognised service; also "More ways to play > Open a streaming channel on Roku" | `playback/StreamingServices`, `ChannelMatcher`, `RokuChannelLauncher` |
| Screen mirroring guide | "Mirror phone screen" (after a failure offer, and under "More ways to play") | `playback/MirroringGuide`, `ReceiverCapabilities`, `ui/MirrorScreen` |
| Phone relay | "Try phone relay" (offered when a check says headers/cookies are needed) and "Play the chosen video through the phone" | `relay/RelayServer`, `RelayManager`, `RelaySession`, `RelayService`, `playback/UpstreamPolicy`, `HlsPlaylist` |
| In-app sign-in | "Sign in to a site / manage sign-ins" and offered when a check says sign-in is needed | `ui/SitesScreen` (+ `SignInScreen`), `signin/SiteSessions`, `playback/SiteSession` |
| "Why won't it play?" | Automatic after a direct attempt fails or never confirms | `playback/StreamProbe`, `FailureClassifier`, `PlaybackPlanner`, `PlaybackStatus` |

The Roku receiver channel is **unchanged** (still 1.0.4). Relay URLs are plain `http://phone:port/...` links the existing receiver already plays.

## Playback selection (progressive enhancement)
1. Direct playback (unchanged): paste URL -> find streams -> select -> Roku plays it by itself.
2. If the link is a supported streaming service: offer its official Roku channel.
3. If direct playback fails or never confirms, the phone runs a bare / browser-like / signed-in ladder against the stream and its first segment and offers only what fits:
   protected -> channel or mirroring; needs headers -> relay; needs session -> sign-in then relay; sign-in required -> sign-in; format problem -> relay or mirroring.
4. Every option is also reachable by hand under "More ways to play".

## Security model: relay
- Binds only to the phone's Wi-Fi/Ethernet address; one ephemeral port; stops when the last session ends.
- Each session has a 128-bit random token in the URL path, only valid for that session, and only served to the **Roku's IP**. Wrong token, unknown id and wrong client all look the same (404/403).
- Not an open proxy: the Roku can only fetch ids the relay itself registered by rewriting a playlist or starting the session. Upstream URLs are never put in relay URLs (so signed tokens in them don't appear on the LAN).
- Upstream policy: http/https only; loopback, RFC1918, link-local, CGNAT and unique-local addresses are refused for the stream and for every redirect and rewritten playlist URI (IP literals checked up front, hostnames checked at DNS time). A private source is allowed only when the user themself pasted that private host.
- Referer/Origin/User-Agent are sent to hosts the relay reaches; **cookies are opt-in**, attached only to the source's own hosts, scoped by the cookie store itself, fetched on demand and never stored, logged or put in URLs. `RelaySession.toString()` is redacted; diagnostics contain counters and redacted hosts only.
- DRM is detected (HLS `SAMPLE-AES*` or a non-`identity` `KEYFORMAT`) and refused; DASH is not relayed.
- Lifecycle: ends on Stop, when the Roku reports playback over (3 misses), after 10 minutes idle, after 12 hours, on Wi-Fi loss or IP change, or if the Roku never starts. Runs as a foreground service with a visible notification, wake lock and Wi-Fi lock.

## Security model: sign-in
- The site's own page in a restricted WebView: https only, public hosts only, web pages only (no `file:`, `intent:`, `javascript:`, `content:`, `data:`), no HTTP-auth handling, no credential capture, no spoofed browser identity, third-party cookies off. Sites that refuse embedded sign-in (e.g. Google accounts) are not worked around.
- Couch Cast stores only the *hostnames* of signed-in sites. Cookies live in the app's private WebView store, excluded from backup and device transfer (`allowBackup=false` + data-extraction rules).
- Per-site "Log out" and "Clear all sign-in data". Cookies are never sent to the Roku by the direct path.

## Limitations (by design or by platform)
- DRM / subscription-protected video (Netflix, Disney+, Hulu, Prime Video, Max...) cannot be played or relayed; only the official channel (or mirroring, which protected apps usually blank) can show it.
- Roku title deep links are best effort: only YouTube (video id) and Netflix (title id) are attempted, and the channel decides whether to honour them; the app never claims the title opened, only that the channel did.
- Android has no public API to start Miracast silently. The app opens the system Cast screen and reports "connected" only when Android lists an external display; some Cast sessions are invisible to apps, so "not confirmed" is shown instead of guessing. Roku does not report mirroring support over ECP, so it is shown as "likely".
- Relay: MP4 and HLS only; needs the phone on, awake enough to keep the foreground service, and on the same Wi-Fi; guest/isolated networks block it; aggressive battery savers on some phones can still stop it. Streams requiring TLS client fingerprints, bot checks, short-lived single-use URLs or `blob:`/MSE-only delivery are not solved.
- Google Cast / Android TV / Fire TV: mirroring guidance and capability model exist; discovering and controlling those devices is not implemented.

## Verification status
See the PR description for the current table of automated tests versus checks that need a physical phone and Roku.
