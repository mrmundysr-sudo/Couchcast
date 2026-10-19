# Couchcast

Android remote for Roku TVs and Rooms, from Mundy Morning Studios LLC. Not affiliated with Roku.

Native Kotlin + Jetpack Compose. Roku control uses ECP (HTTP, port 8060) with SSDP discovery.
See the build spec for the full roadmap.

## Status
- Milestone 1: SSDP discovery, add by IP, ECP keys, remote screen, named Rooms
- Milestone 2: multiple Rooms, last-used memory, channel grid with icons, on-screen keyboard, TV-only controls

Not yet built: lock-screen/tile/widget, casting, ad clock and billing, receiver channel, website.

## Build
CI (`.github/workflows/android.yml`) runs unit tests and publishes a debug APK as a pre-release `apk-<run>`.
Locally: `gradle assembleDebug` (Gradle 8.9, JDK 17, Android SDK 34).
