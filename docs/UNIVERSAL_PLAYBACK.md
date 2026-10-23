# Universal playback expansion

## Preserved working baseline
The last revision verified working on a real Roku TV (install wizard, page scan, multi-stream
selection, independent Roku playback, remote pause/resume/seek) is
`a00c55c00b70ac15274b1d13529c21acff06a168` (APK `apk-11`, branch `claude/roku-receiver-installer`).
No tag was created or moved. This work lives on `claude/universal-playback`, branched from that commit.

## Principle: progressive enhancement
Direct Roku playback stays the default and is untouched. Other methods are only *offered* after a
failure, a recognised streaming-service link, or when the user opens "More ways to play".
