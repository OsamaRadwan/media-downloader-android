# Continuous development goal

This is the public change-request file for Media Downloader. Edit the **Requests** section to add or change work. `FEATURES.md` describes the product behavior; `PENDING_CHANGES.md` lists confirmed gaps and missing verification by source app; `CHANGELOG.md` records completed work and its evidence.

## Requests

| ID | Status | Request | Completion condition |
| --- | --- | --- | --- |
| CR-001 | In progress | Audit `FEATURES.md` against TikTok, Instagram, and RedNote; implement missing supported behavior and verify it. | Every actionable gap is implemented and tested, or its exact external blocker is documented in `PENDING_CHANGES.md`. |
| CR-002 | Complete | Align the floating download button when TikTok plays a video from a playlist. A later misalignment report prompted a fresh check, and the user confirmed the button was fixed in the tested view. | Button position follows the visible video and remains stable when entering and leaving a playlist, in portrait and rotated views; on-phone checks cover tap and long press without covering TikTok controls. |

Add future requests as `CR-003`, `CR-004`, and so on with status `New`. Keep the original request and its acceptance condition visible when changing its status. Do not treat an unverified feature as complete.

**User scope rule (2026-11-28):** Do not add a feature, change request, or pending item unless the user asks for it. The broad CR-001 audit does not authorize adding inferred gaps, speculative edge cases, or extra verification tasks. Existing entries remain for reference; work on them only when the user directs it. Report incidental findings in the conversation without expanding the backlog.

## Development rules

1. At the start of each development turn, read this file, `FEATURES.md`, `PENDING_CHANGES.md`, `AGENTS.md`, `docs/Android_Architecture_Standard_for_Codex.md`, and `docs/architecture.md`. Inspect uncommitted changes and the baseline build before editing. User instructions in the conversation take precedence over repository guidance.
2. Check the apps and journeys affected by the user's specific request. Test the relevant behavior in each app the request names. Do not expand a request into unrelated apps, media types, or new pending work.
3. Work in small, buildable changes. Preserve installed settings, history JSON, filenames, saved media, notification routing, and overlay behavior unless a request changes them. Validate source/post identity before saving; never substitute an earlier or different item. Keep public post permalinks free of account/session tokens. Record actual saved dimensions and audio tracks rather than inferring them from a source listing.
4. For each affected app, exercise multiple public posts of the relevant media type when available. Include normal and failed/partial cases where the feature has them. Run `test.ps1` and `build.ps1`, install the signed APK on a connected phone when available, and inspect the actual file or UI result. For MP4, verify video and audio samples, actual dimensions, post identity, and embedded metadata. For albums, verify item count, order, decoded dimensions, filenames, and retry behavior. For UI changes, verify the screen state and notification entry point. Record commands, sample identity, outcomes, and limitations in `docs/architecture.md` or release notes without publishing private links or tokens.
5. Update `FEATURES.md` only for implemented behavior or explicit requirements marked as such. Add an item to `PENDING_CHANGES.md` only when the user asks for that item. Move an existing item out only after its acceptance check passes or the user removes it. Record completed requested changes and test evidence in `CHANGELOG.md`. If no pending items remain, make `PENDING_CHANGES.md` a zero-byte file with no header or placeholder text.
6. Bundle related tested features into a single release. Do not tag or publish every intermediate app version. Follow `docs/releasing.md` when a release bundle is ready, and distinguish local installs from published releases.

## Goal lifecycle

On each goal continuation, act only on the user's latest explicit request. Do not resume the broad feature audit or existing pending entries without direction. The check runs in the development conversation; editing this file alone does not wake a paused goal or start an external watcher. A new user request can resume the work. An externally blocked requested item stays documented with its exact blocker and can be revisited when the user asks.
