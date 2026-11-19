# Media Downloader feature catalog

This document describes implemented features in terms that can be reused for additional source applications. **The current app implements one source integration: TikTok.** Adding another source still requires code for that source's on-screen controls, links, playback data, and post details.

## Floating download control

- An Android Accessibility service watches the foreground application and shows the download control only over a supported source application. It hides the control over other apps and over Media Downloader itself.
- A small, transparent download arrow sits on the chosen screen side. It uses an application overlay below Android's system controls, including the volume panel. The overlay stays attached while a system control is shown when Android permits it.
- A short tap starts a download for the **currently displayed** video or photo album. The source integration obtains a fresh link at tap time instead of reusing a link from an earlier post. The source app stays in the foreground while the transfer continues.
- A long press opens Media Downloader on the Downloads page without starting a transfer.
- The app has a generic name, adaptive launcher icon with a themed monochrome version, launcher entry, and Android package so its identity is not tied to one source application.

## Button appearance and settings

- The Settings page shows both a large icon preview and a live-size button at its actual screen position. Changes appear immediately in the preview and the floating control and persist across normal app relaunches.
- Position controls: left or right side, vertical position, and distance from the screen border.
- Appearance controls: button size, padding around the arrow, arrow color and opacity, optional background shape, background color, and background opacity. The background can be absent, circular, or rounded.
- Four built-in transparent icon styles are available: solid, outline, simple, and tray. Users can also import transparent images from the phone, select or remove them, and keep up to 30 custom icons in app-private storage. Custom images retain their own colors.
- Settings include permission status and links to Android's Accessibility, overlay, and notification settings, plus a reset action.

## Capturing and downloading media

- The source integration identifies the current post and retrieves its account/profile name, account ID, video ID, publication time, caption, hashtags, thumbnail, and available playback URLs when those fields are supplied.
- For public media, resolution uses a fresh anonymous web session separate from the source app's signed-in account. The transfer first requests each candidate without a Cookie header. A public, full-resolution candidate with valid media completes without sending visitor cookies. An access rejection, or a page returned in place of MP4, may retry that candidate with the resolver's own anonymous visitor cookies; rate limits do not trigger that retry. For albums, all public renditions of each photo are checked before a visitor retry is considered when their best decoded size is below the source-listed size. This limits account-linked traffic but cannot guarantee that a platform will not rate-limit or block requests.
- A foreground download service transfers media independently of the source application's own save/download action. Up to **three** downloads can run at once.
- Available playback candidates are ranked with wide/landscape variants first, then by pixel count and other available quality signals. The downloader tries the highest ranked accessible candidate and falls back to another candidate when a request fails or lacks audio.
- The selected file must be **one MP4 containing both video and audio**. The app checks that both tracks have samples, records the actual dimensions and codecs, and checks the tracks again after adding metadata. It does not save a video-only file or join a separate audio download.
- The transfer records progress, downloaded and total bytes when known, speed, stage, and estimated remaining time when calculable. Failed jobs remain visible with their error reason.
- Completed MP4s are saved to the phone's public Downloads collection. A temporary transfer file is removed after saving or failure.

## File names and embedded metadata

- New files use `<profile-name>_profile_<account-id>_VideoID_<video-id>_<yyyy-MM-dd_hh-mm>.mp4`. The timestamp comes from the post's publication time; the separator uses a hyphen in place of a filename-invalid colon.
- Each new MP4 embeds the canonical source-post URL in Comment, an available title in Title, the full caption or post text in Description, and hashtags in Keywords. Missing source fields remain absent rather than being invented.
- The app verifies that metadata writing did not remove or alter the video and audio tracks before publishing the file.

## Downloads page and notifications

- The Downloads page lists active, completed, and failed jobs with counts, a progress bar, thumbnail, account/profile, filename, bytes transferred, file size, speed, and error or stage text as applicable.
- Expandable details show available video and account IDs, publication time, resolution, duration, video and audio codecs, start and finish times, and save location.
- A source-post link opens the post in its source application when possible, with Android's URL handler as a fallback.
- Every completed card has a **compact Open video button that remains visible with details collapsed**. Its thumbnail is also tappable. Both open the saved MP4 through Android's video-player handler.
- Progress and result notifications are silent. Tapping a job notification opens Media Downloader on the Downloads page and focuses that job and its details; the summary notification opens the Downloads page. Completion and failure remain visible in the list after the transfer ends.
- Download records and appearance settings are stored locally; custom icon images are stored in app-private files. The Downloads list can show its saved records without network access.

## Diagnostics and export

- Download, album, overlay, thumbnail, history-storage, icon-import, and unexpected app-crash failures are written to a bounded app-private log that survives normal relaunches and app updates. The app keeps at most two 128 KiB log files.
- Settings shows the number of saved failure events. Export diagnostic report opens Android's document picker and writes a text report to the location the user chooses. The report includes app and Android versions, device model and ABI, overlay/Accessibility/notification status, and saved failure events. It can also be exported when no failures have been recorded.
- Stored event details remove web URLs, local paths, long numeric IDs, and common secret values. The app does not read system logcat or upload reports automatically.

## Photo album downloads

- One tap on a photo post downloads the complete ordered image list supplied in the post data, including photos not yet swiped to. The resolver checks desktop, wide desktop, and mobile page responses for distinct signed renditions of each photo. The transfer fetches each discovered image address directly, without the source app's Save action or screenshots. For each photo it decodes the actual dimensions, chooses the most pixels (then the larger file at equal dimensions), and records whether the saved image matches TikTok's listed dimensions. The original upload resolution cannot be guaranteed when TikTok does not expose it.
- Each photo is saved to public Downloads as `<profile>_profile_<account>_PostID_<post>_<yyyy-MM-dd_hh-mm>_photo_<index>-of-<total>.<extension>`. Its actual image format and dimensions are checked before saving. The download record keeps the canonical source URL, title, caption, hashtags, and each file's details. JPEGs also embed the source URL, available post text, hashtags, and creator in EXIF fields; PNG and WebP metadata remain in the app record.
- One album job displays the saved photo count, bytes, speed while transferring, and one ordered row per photo with a thumbnail, actual dimensions, source-listed resolution comparison, filename, error, and Open photo action when available. Silent progress and result notifications focus the album card. A partial failure retains saved photos; Retry missing photos resolves fresh image URLs and skips photos already saved in that job.

## Adding another source application

The shared UI and transfer behavior can be reused, but the current capture and resolver code is still specific to TikTok. Each new source integration needs:

1. A way to recognize the source application and show the floating control while it is in the foreground, with a clear response if no supported post is available.
2. A reliable, fresh way to identify the **current** video or photo album and obtain its post URL or ID without opening Media Downloader.
3. Parsing for account/profile, post ID, publication time, post text, hashtags, thumbnail, and source-post link where available; photo sources also need the complete ordered album list.
4. Discovery and ranking of accessible playback streams that contain audio, including wide variants when the source exposes them; photo sources need the best accessible image URL for each album item.
5. Source-specific URL validation, public cookie-free media requests, isolated anonymous visitor fallback when needed, failure handling, and an Open in source app link. A source integration must not import the source app's signed-in cookies or credentials for public media.

These are integration requirements, not claims that other applications already work. The app can only choose among playback streams it can discover and access; it cannot guarantee the original upload resolution or a download when a source rejects every stream. Android may disable the Accessibility service after a deliberate force-stop, requiring it to be enabled again. Files owned by an earlier Android package remain in Downloads but are not automatically imported into this package's history.
