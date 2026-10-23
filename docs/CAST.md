# Google Cast sender (Chromecast, Google TV, Android TV, TVs with Cast built in)

The **Cast** tab sends a video to a Cast device chosen with the Cast icon. Nothing is installed on the TV (it uses Google's
Default Media Receiver). The Roku tabs and the Roku receiver are untouched.

## Design
- `receiver/Receiver` is the shared interface; `cast/GoogleCastReceiver` is the Cast adapter, next to `RokuReceiver`.
- `cast/CastPlayback` holds the confirmation logic (no Play Services, unit-tested with a scripted fake TV).
  Same promise as Roku: **PLAYING only when the TV reports playing and keeps reporting it for 6 s**; a stream that
  starts then dies is `STOPPED_EARLY`; no TV reply is `NOT_STARTED`.
- `cast/GoogleCastTransport` is the only class that touches Cast SDK calls (main thread).
- Device discovery and the picker dialog are the standard Cast framework (Google Play services). Discovery uses
  local-network mDNS; no accounts, ads or analytics are added.
- Page scanning reuses `PageScanner`/`MediaLinks`.

## Limits (stated in the app too)
- Needs Google Play services; the tab explains when it is missing.
- The Cast media receiver cannot send Referer/User-Agent/cookies, so links that need them fail. Not yet solved.
- HLS/DASH streams must allow cross-origin reads (CORS) or the TV refuses them; progressive MP4 is most reliable.
- DRM services are not supported. No subtitles, queue or seek controls yet.
- Phone relay is **not** wired to Cast: the relay is locked to the Roku's IP by design, and Cast needs CORS headers.
  Doing this means a deliberate relay change and review (next step).

## Not verified on hardware
Unit tests and CI only. Check on a real Chromecast / Google TV: picker opens and the icon is visible on the dark theme,
a direct MP4 plays and is confirmed, an HLS link, a failing link reports a reason, Stop works, Roku tabs still behave
as in `docs/HARDWARE_CHECKLIST.md` section A.
