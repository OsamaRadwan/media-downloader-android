# Continuous development goal

This is the public change-request file for Media Downloader. Edit the **Requests** section to add or change work. `FEATURES.md` describes the product behavior; `PENDING_CHANGES.md` lists confirmed gaps and missing verification by source app; `CHANGELOG.md` records completed work and its evidence.

## Requests

| ID | Status | Request | Completion condition |
| --- | --- | --- | --- |
| CR-001 | In progress | Audit `FEATURES.md` against TikTok, Instagram, and RedNote; implement missing supported behavior and verify it. | Every actionable gap is implemented and tested, or its exact external blocker is documented in `PENDING_CHANGES.md`. |
| CR-002 | In progress | Align the floating download button when TikTok plays a video from a playlist. | Button position follows the visible video and remains stable when entering and leaving a playlist, in portrait and rotated views; on-phone checks cover tap and long press without covering TikTok controls. |

Add future requests as `CR-003`, `CR-004`, and so on with status `New`. Keep the original request and its acceptance condition visible when changing its status. Do not treat an unverified feature as complete.

## Development rules

1. At the start of each development turn, read this file, `FEATURES.md`, `PENDING_CHANGES.md`, `AGENTS.md`, `docs/Android_Architecture_Standard_for_Codex.md`, and `docs/architecture.md`. Inspect uncommitted changes and the baseline build before editing. User instructions in the conversation take precedence over repository guidance.
2. Compare every requested behavior with implementation and evidence for **each supported app**. Cover TikTok portrait video, rotated full-screen video, photo albums, and Stories; Instagram feed posts, opened Reels, photo albums, and Stories; RedNote videos, photo posts, albums, and any Story surface discovered. Cover shared download, metadata, duplicate/retry, notification, diagnostics, and Downloads-page flows where relevant. Record an app-specific gap in `PENDING_CHANGES.md`; do not assume a shared code path proves the app journey works.
3. Work in small, buildable changes. Preserve installed settings, history JSON, filenames, saved media, notification routing, and overlay behavior unless a request changes them. Validate source/post identity before saving; never substitute an earlier or different item. Keep public post permalinks free of account/session tokens. Record actual saved dimensions and audio tracks rather than inferring them from a source listing.
4. For each affected app, exercise multiple public posts of the relevant media type when available. Include normal and failed/partial cases where the feature has them. Run `test.ps1` and `build.ps1`, install the signed APK on a connected phone when available, and inspect the actual file or UI result. For MP4, verify video and audio samples, actual dimensions, post identity, and embedded metadata. For albums, verify item count, order, decoded dimensions, filenames, and retry behavior. For UI changes, verify the screen state and notification entry point. Record commands, sample identity, outcomes, and limitations in `docs/architecture.md` or release notes without publishing private links or tokens.
5. Update `FEATURES.md` only for implemented behavior or explicit requirements marked as such. Move an item out of `PENDING_CHANGES.md` only after its acceptance check passes or the user removes it. Record completed changes and test evidence in `CHANGELOG.md`. If no pending items remain, make `PENDING_CHANGES.md` a zero-byte file with no header or placeholder text.
6. Bundle related tested features into a single release. Do not tag or publish every intermediate app version. Follow `docs/releasing.md` when a release bundle is ready, and distinguish local installs from published releases.

## Goal lifecycle

On each goal continuation, reconcile new requests and the feature audit before implementation. When there are no `New` or `In progress` requests and no actionable pending changes, set the continuous goal to **paused**. The check runs in the development conversation; editing this file alone does not wake a paused goal or start an external watcher. A new user request resumes the work. An externally blocked item stays documented with its exact blocker and can be revisited when new evidence or configuration is available.
