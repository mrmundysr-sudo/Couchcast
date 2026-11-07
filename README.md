# Couchcast

Android remote for Roku TVs and Rooms, from Mundy Morning Studios LLC. Not affiliated with Roku.

Native Kotlin + Jetpack Compose. Roku control uses ECP (HTTP, port 8060) with SSDP discovery.
See the build spec for the full roadmap.

## Status
- Milestone 1: SSDP discovery, add by IP, ECP keys, remote screen, named Rooms
- Milestone 2: multiple Rooms, last-used memory, channel grid with icons, on-screen keyboard, TV-only controls

- Receiver installer: guided "Install receiver on Roku" wizard (Rooms tab). Uploads the bundled Roku channel (`/receiver`) through Roku's Development Application Installer.

Not yet built: lock-screen/tile/widget, casting, ad clock and billing, website.

## Play tab
Paste a direct video link (`.mp4`, `.m3u8`, `.mpd`) or a web page and tap **Play on Roku**. For a web page the app loads it in a hidden WebView and collects the video requests the page's player makes plus its video tags (no DRM bypass; protected sites such as Netflix/Hulu/Disney+/YouTube are not supported); it plays one found video or lets you choose. The app checks the receiver is installed and current, sends the link with an ECP deep link (`/launch/dev?contentId=…` or `/input?…` if already running), then asks the Roku's `/query/media-player` and only says "playing" when the Roku does.

## Cast from this phone
The Play tab also has **Cast a video or photo from this phone**, next to where you paste a link. It opens Android's photo picker (no storage permission needed; the app only gets the one file you tap). A **video** is served to the Roku straight from the phone by the local relay, seekable, nothing copied, and keeps playing while the notification shows (phone on, same Wi-Fi). A **photo** is first re-encoded as an upright JPEG of at most 1080p (Rokus can't decode HEIC and choke on huge images), sent to the receiver, and the app reports success only after the Roku has downloaded the whole file; after that the phone is free. Back on the Roku, or Stop in the app, clears it. The receiver channel is now **1.0.00005** (adds the photo screen), so the app will ask you to update it once. One item at a time; no slideshow yet.

## More ways to play (universal playback)
Direct playback stays the default. When it can't work, Couch Cast offers: the official **Roku channel** for recognised streaming-service links, **screen mirroring** (guided, confirmed only when Android reports it), an **authenticated local phone relay** for ordinary MP4/HLS that needs request headers or a session, and **in-app sign-in** for members-only sites. See `docs/UNIVERSAL_PLAYBACK.md` for the design, security model and limits.

## Roku receiver installer
Two different things: the **Android APK** (this app) and the **Roku receiver package** (`/receiver`, zipped into the APK as `assets/couchcast-receiver.zip` and uploaded to the TV).

What the app does: checks Wi-Fi, reaches the Roku (ECP, port 8060), probes the developer installer (port 80), walks the user through Developer Mode, waits for the restart, asks for the developer password (optional encrypted "remember", Android Keystore AES-GCM), uploads the package with HTTP Digest auth (`rokudev`), and only reports success once the Roku's own `/query/apps` lists the `dev` channel with the right title and version.

What it cannot do (Roku restriction): turn on Developer Mode or set the developer password. Those require the physical remote key sequence on the TV, so the app guides the user instead of sending the sequence itself.

Tested in CI/unit tests: Digest auth (RFC 2617 vector), installer reply parsing, planning logic, upload against a fake installer that enforces Digest. **Not yet tested on a real Roku:** the on-TV Developer Mode steps, a real upload, the receiver channel's BrightScript, playback through the receiver.

## Build
CI (`.github/workflows/android.yml`) runs unit tests and publishes a debug APK as a pre-release `apk-<run>`.
Locally: `gradle assembleDebug` (Gradle 8.9, JDK 17, Android SDK 34).
