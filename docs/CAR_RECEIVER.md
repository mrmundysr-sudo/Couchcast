# Couchcast Car Receiver (personal test app)

An Android app that runs on the phone connected to your car through Android Auto. Your own phone (iPhone or Android) sends it a video, a song, a picture or a game, and acts as the controller. APK: `car-receiver.apk` in each CI release.

**Parked use only on the car screen.** Android Auto closes parked apps when the car moves. Video on the car screen is not a supported Android Auto feature yet (video is "coming in beta"); here it only works because the app is declared as a game app, and it contains real games. For personal testing only. Not for a public listing.

## What it does
- **Games:** runs web games packaged inside the APK (7 Come 11 today). Drop another folder with an `index.html` into `carreceiver/src/main/assets/games/` to add one. Play on the car touchscreen, or with the phone as a touchpad.
- **Video / audio / pictures:** plays a link, or a file sent from your phone, full screen. Audio and a "now playing" card also appear in Android Auto's media area (standard media session), so the title, play/pause and steering-wheel buttons work. Pictures show full screen.
- **Phone controller:** a web page the receiver serves. Open it in Safari or Chrome on any phone on the same network. No app needed on the controller phone.

## Set-up (Android test phone, Android 15 or newer for the car-screen games)
1. Install `car-receiver.apk`. Open it once. Allow notifications.
2. Android Auto developer mode: Settings > Apps > Android Auto > Additional settings in the app > tap "Version" 10 times > OK. Then the three-dot menu > Developer settings > turn on **Unknown sources**. (Google's testing page says this setting covers media and parked apps.)
3. Connect the phone to the Camry with a **USB cable** (wired keeps the phone's Wi-Fi free).
4. Give the phones a shared network: turn on the Android phone's **hotspot** and join it with your iPhone (or the reverse).
5. On the car screen, open the launcher. Look for "Couchcast Car Receiver" with the games, and in the media apps.
6. The receiver shows an address (like `http://192.168.43.1:8765`) and a 6-digit code. On your iPhone open that address in Safari, enter the code, and use the three tabs: **Cast** (link, file, transport), **Games**, **Controller** (touchpad, click, drag, d-pad, exit).

Without the car, everything works on the phone itself (and in Google's Desktop Head Unit). The receiver screen is the same.

## Security
Only devices on your local network are served. The controller page is public; every action needs the pairing token. The code is 6 digits; five wrong tries lock pairing for 30 seconds and show a new code. Tokens last until the receiver restarts or you tap "Forget paired phones". Sent files are stored in the app's cache, replaced by the next file, and deleted when you exit.

## Not tested yet (no car or Android Auto run so far)
Android Auto listing and launching, video on the car display, the games on a landscape car screen (shown in a phone-shaped frame), steering-wheel controls, the touchpad on a real game, and large uploads. CI only builds the app and runs the unit tests (pairing, the control server against a real HTTP client, pointer logic).

## Limits
- Android 15+ for apps on the car display; Android Auto may change behaviour at any time.
- iPhone and CarPlay can't show any of this on the car screen. The iPhone is the controller; the Android phone is the receiver.
- Streaming a game from another phone (instead of running it inside the receiver) is not built.
- Find-A-Word has no code in its repo and Schatzi is a native Android project, so only 7 Come 11 is packaged.
