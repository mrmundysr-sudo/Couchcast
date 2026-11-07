# Couchcast for iPhone

SwiftUI app in `ios/`. Logic lives in the Swift package `ios/CouchcastCore` (Roku control, the local file server, photo prep) and is tested with `swift test` on macOS. The app target in `ios/Couchcast` is the screens. `ios/project.yml` is the XcodeGen spec; the `.xcodeproj` is generated, not committed.

## What it does (v1)
- Rooms: find Rokus on the Wi-Fi (scans the local /24; iOS can't use Roku's SSDP discovery without a special Apple entitlement) or add by address.
- Remote, Channels (icons, launch).
- Play: direct video links and web pages with an ordinary video; the same Couchcast Receiver as Android, confirmed only when the Roku's own player says "play".
- Cast from this iPhone: pick a video or photo. Photos become an upright JPEG of at most 1080p. Videos that aren't already .mp4 (iPhone records .mov, often HEVC) are re-encoded to H.264 MP4. The phone serves the file to the Roku with seek support, only that file, only to the Roku's address, under a random token. Keep the app open on screen while a video plays; photos are free once the Roku has downloaded them.

## Not in v1
Receiver installer (use the Android app, or install once from any Android phone), sign-in sites, Google Cast, mirroring guide, iPad layout, background playback. The Roku must have receiver 1.0.5 or newer (the app checks).

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
