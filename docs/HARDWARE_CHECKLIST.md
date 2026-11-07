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

## D. Cast tab (`claude/cast-sender`)
16. Cast icon visible on the dark background; tapping opens the device chooser; it lists the Chromecast / Google TV.
17. Connect: the tab shows "Connected to <name>".
18. Direct `.mp4` link plays on the TV; app says "Confirmed".
19. `.m3u8` link: plays, or fails with a clear reason (CORS/sign-in), never "playing" if it did not.
20. Web page with a video: found and sent; multiple videos -> choose list.
21. Stop ends playback; disconnecting from the Cast dialog ends the session.
22. Phone without Google Play services (if available): tab explains Cast is unavailable and the app does not crash.
23. Repeat A1–A8 (Roku) - unchanged.

Build 15 note: tapping the Cast icon crashed (activity theme was not AppCompat). Fixed in the next build; re-check item 16
(icon visible on the dark theme, chooser opens) and that Roku screens look the same as before (A1-A8).

## E. Cast from phone (`claude/affectionate-curie-usoym1`)
24. Play tab: the app asks you to update the receiver (now 1.0.00005); the installer wizard completes.
25. "Cast a video or photo from this phone" opens the picker. Pick a short phone-recorded video: Roku plays, app says "Confirmed"; notification visible.
26. Seek with the Remote tab (fast-forward / rewind) works. Try a long (>5 min) video and a video from the Downloads folder.
27. Pick a portrait photo: shown upright and full screen on the TV; app says the Roku has the photo; notification does not stay.
28. Pick a HEIC photo and a large (12 MP+) photo, and a transparent PNG (should have a white background).
29. Back on the Roku and Stop in the app both clear the photo/video.
30. Phone on mobile data only (Wi-Fi off): clear "connect to the same Wi-Fi" message. Guest-network/isolated Wi-Fi: "Roku never downloaded it" message.
31. Cast a photo then a video, then a video then a photo, without leaving the tab: each replaces the last.
