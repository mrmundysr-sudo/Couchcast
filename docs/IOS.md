# Couchcast for iPhone

SwiftUI app in `ios/`. Logic lives in the Swift package `ios/CouchcastCore` (Roku control, the local file server, photo prep) and is tested with `swift test` on macOS. The app target in `ios/Couchcast` is the screens. `ios/project.yml` is the XcodeGen spec; the `.xcodeproj` is generated, not committed.

## What it does
Same features as the Android app, except where iOS or Roku rules differ (below).
- **Rooms**: find Rokus on the Wi-Fi (scans the local /24; iOS can't use Roku's SSDP discovery without a special Apple entitlement) or add by address. **Install Couchcast Receiver** is here too.
- **Receiver installer** (`InstallModel`/`InstallView`, core in `CouchcastCore/Install.swift`): checks the Wi-Fi, the Roku and its developer installer, guides the on-TV Developer Mode steps (only the physical remote can do them), waits for the restart, uploads the bundled receiver with Digest auth, and only says "installed" when the Roku's own app list shows the right version. The password can be remembered in the iOS Keychain (this device only). The receiver ZIP is `ios/Couchcast/Resources/couchcast-receiver.zip`, built from `/receiver` by `python3 tools/package_receiver.py`; CI fails if it is stale.
- **Remote**, **Channels** (icons, launch).
- **Play**: direct links and web pages (a hidden WKWebView finds the video the page's player requests, plus a plain page fetch), played through the receiver and confirmed only when the Roku's player says "play". Streaming-service links (Netflix, YouTube, Hulu, Disney+ and others) offer the service's own Roku channel, found in the Roku's app list. When playback fails, a phone-side check explains why (needs browser headers, needs your session, DRM, expired, Roku can't decode) and offers only the fallbacks that fit: open the channel, **phone relay**, sign in, mirror.
- **Phone relay**: the iPhone fetches the stream (with the page's Referer/User-Agent, optionally your signed-in session) and serves it to the Roku. HLS playlists are rewritten so every segment and key goes through the relay. It serves only addresses it registered, only to that Roku, only under an unguessable token, never to private addresses, and never DRM. Keep Couchcast open on screen while it plays.
- **Sign in to a site**: an in-app browser (WKWebView) so a members-only video can be relayed. Couchcast never sees the password; only host names are stored; cookies stay in the app's own browser store and can be cleared per site or all at once.
- **Mirror**: a guide for AirPlay screen mirroring (iOS only lets Control Center start it). The screen reports connected only when iOS says the screen is being mirrored.
- **Cast a video or photo from this iPhone**: photos become an upright JPEG of at most 1080p. Videos that aren't already .mp4 (iPhone records .mov, often HEVC) are re-encoded to H.264 MP4. The phone serves the file with seek support.
- **Cast tab** (Chromecast, Google TV): see "Google Cast" below.

## Differences from Android
- iOS has no foreground service, so the relay and phone-video casting only last while Couchcast is on screen (the app asks iOS for a little background time for quick app switches).
- Roku discovery scans the /24 instead of SSDP.
- Mirroring is started from Control Center, not from the app.
- The page scan can't intercept network requests like Android's WebView; it reads the page's resource list and video tags instead.

## Google Cast
The Cast tab, its confirmation logic (`CouchcastCore/Cast.swift`, tested) and the Cast code (`CastView.swift`) are in place, but **the Google Cast SDK is not linked yet**, so the tab says it isn't included. The SDK is a binary from Google; adding it is a decision about how to fetch it (Google's CocoaPods pod `google-cast-sdk`, or a Swift package that mirrors the same binary). After adding it: set `NSBonjourServices` to `_googlecast._tcp` and `_CC1AD845._googlecast._tcp` in `project.yml`, and initialise `GCKCastContext` with the default media receiver at launch. The code under `#if canImport(GoogleCast)` is written against the SDK's API but has never been compiled or run.

## Build checks (CI)
`.github/workflows/ios.yml`: `swift test`, then an unsigned simulator build and an unsigned iPhone build.

## TestFlight (you do this part)
1. Apple Developer Program account. In App Store Connect, create an app with bundle ID `com.mundymorning.couchcast` (register the bundle ID first under Certificates, Identifiers & Profiles if it isn't listed).
2. App Store Connect > Users and Access > Integrations > App Store Connect API: create a key with the App Manager role. Download the `.p8` once.
3. In the GitHub repo, Settings > Secrets and variables > Actions, add:
   - `ASC_KEY_ID`: the key's ID
   - `ASC_ISSUER_ID`: the issuer ID shown on that page
   - `ASC_KEY_P8`: the full text of the `.p8` file
   - `APPLE_TEAM_ID`: your 10-character team ID (Membership details)
4. Actions > "iOS TestFlight" > Run workflow. It archives with Apple-managed signing and uploads. The build appears in TestFlight after Apple's processing (usually under 30 minutes). Add yourself as an internal tester and install with the TestFlight app.

The TestFlight workflow has not been run (it needs your secrets), so expect to fix small things the first time. Nothing in the repo contains signing material.

## Placeholders to replace
The app icon (`ios/Couchcast/Assets.xcassets`) is a generated placeholder.
