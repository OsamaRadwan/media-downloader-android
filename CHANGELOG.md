# Development changelog

This log distinguishes code installed locally from GitHub releases. It summarizes completed work; `docs/architecture.md` holds the detailed verification record, and `PENDING_CHANGES.md` holds unfinished work.

## Local development bundle after v1.68 — not yet published

- **Unreleased album recovery fix:** A missing TikTok album photo was detected while the other photo stayed intact. The first retry timed out and exposed a bug that erased the missing photo's expected byte size. Retry now retains that size. An affected JPEG record can be relinked through Android's picker only when its filename, decoded dimensions, and embedded public post link match. On the HMD Skyline, relinking restored a complete 2-of-2 album and Open photo launched Photos. A second Redownload saved the missing photo with the original SHA-256; the other photo's SHA-256 was unchanged. Both saved files are present, and the temporary test backup was removed.
- **1.80:** Placed the search-field X at the far right and Paste immediately to its left. On the HMD Skyline, Paste filtered 441 records to 20 RedNote matches and X restored the full list. The Java tests and signed build passed.
- **1.77–1.79:** Indexed history off the UI thread; added full-history search, 15 records per page, Newer/Older controls at the top and bottom, notification focus on the correct page, a Paste action, and saved-file relinking through Android's picker. The phone rendered 441 records by page and restored a selected MP4 after reinstall. Album-photo relinking still needs phone verification.
- **1.75–1.76:** Blocked repeat transfers for the same post and validated saved-file identity using both filename and actual byte size. Added Redownload when a saved file is missing or changed. A TikTok duplicate and a deleted-video redownload passed on the phone. Missing-photo recovery still needs phone verification.
- **1.74:** Added the dark Downloads and Settings theme. Phone screenshots confirmed the cards, controls, dialog, and live button preview.
- **1.73:** Added Retry download on failed cards, reusing the existing record and notification identity. A failed TikTok download completed on retry as a 1080×1920 H.265/AAC MP4.
- **1.72:** Added system, HTTP proxy, and SOCKS route settings and Android-managed IKEv2 profile controls. Public HTTP and SOCKS test routes downloaded a TikTok MP4 with audio on the phone. VPN profile provisioning and tunnel connection remain untested.
- **1.71:** Added a wall-clock deadline and one exact-post retry for transient TikTok page transport failures. Two formerly failing links completed as muxed MP4s on the phone.
- **1.69–1.70:** Added Android Share-menu intake without Accessibility and minimized inspection of unrelated apps' window content. Share-to-download completed on the phone with Accessibility disabled. Arab Bank still rejected the enabled Accessibility service when it was restored.

## Published baseline

- **v1.68:** Published the TikTok account Story batch. A phone test collected and saved seven distinct video Stories from one account. See `docs/releases/v1.68.md` for release evidence.
