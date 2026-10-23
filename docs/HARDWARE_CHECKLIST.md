# Hardware test checklist (Roku + phone)

Run on a real Roku and Android phone, same Wi-Fi. Install the APK from the CI pre-release for the branch under test.
Record pass/fail and the build number shown in the app. Anything that fails blocks merging.

## A. Baseline (must match the verified `a00c55c` behaviour)
1. Rooms: discovery finds the Roku; add-by-IP works; Room persists after restart.
2. Install wizard: Developer Mode steps, restart wait, password, upload; success shown only after the Roku lists the Couchcast Receiver.
3. Play tab, direct `.mp4` link: Roku plays; app says "Confirmed".
4. Play tab, direct `.m3u8` link: same.
5. Play tab, web page with several videos: choose list appears; chosen video plays.
6. Remote tab: pause, resume, seek while playing.
7. Bad link / expired link: app reports a reason, never "playing".
8. Back on the Roku stops the receiver; app Stop does the same.

## B. Universal playback (PR #2 only)
9. Recognised service link (e.g. YouTube): offers its Roku channel; channel opens; app does not claim the title opened.
10. Failing link needing headers: "Try phone relay" offered; relay plays; notification visible; relay stops on Stop, on playback end, and when Wi-Fi is switched off.
11. Relay from a second phone/laptop on the LAN cannot fetch the relay URL (expect 403/404).
12. Members-only site: in-app sign-in works; "Log out" and "Clear all" remove access.
13. Mirroring guide: opens the Android Cast screen; "connected" only appears when the TV is actually mirroring.
14. Battery: note % lost over 30 min of relay playback.

## C. Receiver interface branch (`claude/receiver-interface`)
15. Repeat A1–A8 unchanged. No visible difference is expected.
