# Pending changes

This file contains only open implementation or verification work. Delete every line, leaving a zero-byte file, when all items are resolved. Source: `DEVELOPMENT_GOAL.md` CR-001 and the gaps explicitly documented in `FEATURES.md` and `docs/architecture.md`.

## TikTok

- [ ] Verify a live **photo Story** from button tap through saved full-size photo, correct Story identity, filename, metadata, and Open photo. Seven video Stories were verified; a live photo Story was not available in that test.
- [ ] Exercise missing-photo Redownload and document-picker relinking on a TikTok album after deleting or restoring one photo; verify that intact photos remain and filename plus actual byte size are enforced.

## Instagram

- [ ] Implement account-wide Story collection from one Story-viewer tap, validating every Story ID and account before queueing. The current implementation handles one copied Story URL at a time.
- [ ] Investigate public media discovery for visible Stories. Tested NASA and National Geographic Stories returned no anonymous media; do not save a linked post or preview in place of the Story. Verify video with audio and largest available photo renditions on multiple Stories once accessible.
- [ ] Investigate playable feed/Reel posts that fail because an anonymous response omits media or offers only a video-only file. Preserve exact post identity and the one-file-with-audio requirement; verify multiple feed videos and opened Reels after a fix.
- [ ] Exercise missing-photo Redownload and document-picker relinking on an Instagram album, including a partial album, and verify file name and actual byte size.

## RedNote

- [ ] Inspect the installed app for a Story surface. If RedNote Stories are available, add current-Story capture and account-wide collection under the generic Story requirement, then test both video and photo. If that surface is absent, record the observed app version and narrow the requirement in `FEATURES.md`.
- [ ] Exercise missing-photo Redownload and document-picker relinking on a RedNote album, including an original HEIC photo, and verify file name and actual byte size.
- [ ] Test multiple public videos after clean-original selection. Record any post that offers only stamped or video-only assets; keep such failures visible rather than saving a branded or silent file.

## Shared app behavior

- [ ] Test a real user-supplied IKEv2 VPN profile through provision, Android consent, connect, route check, disconnect, and restoration of the previous VPN. Configuration UI and proxy transfers were tested, but no tunnel was established.
- [ ] Resolve or explicitly re-scope Arab Bank compatibility while the floating button's Accessibility service remains enabled. Package-scoped events and work-profile isolation were tested and did not remove the bank's warning; do not claim the current app can override the bank's device-wide check.
- [ ] After the feature bundle is accepted, run the full app matrix, prepare one consolidated release note and signed APK, then publish a single release and verify the download page. The local v1.80 code has not been committed or released.
