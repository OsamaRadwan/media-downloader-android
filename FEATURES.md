# Media Downloader feature catalog

This document describes implemented features in terms that can be reused for additional source applications. **The current app integrates TikTok, Instagram, and RedNote.** Each additional source still needs code for its on-screen controls, links, playback data, and post details.

## Floating download control

- An Android Accessibility service watches the foreground application and shows the download control only over a supported source application. It hides the control over other apps and over Media Downloader itself.
- A small, transparent download arrow sits on the chosen screen side. It uses an application overlay below Android's system controls, including the volume panel. The overlay stays attached while a system control is shown when Android permits it.
- A short tap starts a download for the **currently displayed** video or photo album. The source integration obtains a fresh link at tap time instead of reusing a link from an earlier post. The source app stays in the foreground while the transfer continues.
- A long press opens Media Downloader on the Downloads page without starting a transfer.
- The app has a generic name, adaptive launcher icon with a themed monochrome version, launcher entry, and Android package so its identity is not tied to one source application.

## Button appearance and settings

- The Settings page shows both a large icon preview and a live-size button at its actual screen position. Changes appear immediately in the preview and the floating control and persist across normal app relaunches.
- Position controls: left or right side, vertical position, and distance from the screen border. TikTok portrait posts, TikTok full-screen landscape videos, Instagram feed posts, opened Instagram Reels, RedNote photo posts, and RedNote videos have independent saved placement values. The live border preview can switch among them.
- Appearance controls: button size, padding around the arrow, arrow color and opacity, optional background shape, background color, and background opacity. The background can be absent, circular, or rounded.
- Four built-in transparent icon styles are available: solid, outline, simple, and tray. Users can also import transparent images from the phone, select or remove them, and keep up to 30 custom icons in app-private storage. Custom images retain their own colors.
- Settings include permission status and links to Android's Accessibility, overlay, and notification settings, plus a reset action.

## Capturing and downloading media

- In TikTok's rotated full-screen video view, the arrow remains visible and tappable. A tap briefly returns to that video's portrait post to use its Share control, copies a fresh link, starts the background transfer, then reopens the full-screen view. If the current post cannot be identified, it reports a failure rather than using a previous link.
- The source integration identifies the current post and retrieves its account/profile name, account ID, video ID, publication time, caption, hashtags, thumbnail, and available playback URLs when those fields are supplied.
- For public media, resolution uses a fresh anonymous web session separate from the source app's signed-in account. The transfer first requests each candidate without a Cookie header. A public, full-resolution candidate with valid media completes without sending visitor cookies. An access rejection, or a page returned in place of MP4, may retry that candidate with the resolver's own anonymous visitor cookies; rate limits do not trigger that retry. For albums, all public renditions of each photo are checked before a visitor retry is considered when their best decoded size is below the source-listed size. This limits account-linked traffic but cannot guarantee that a platform will not rate-limit or block requests.
- A foreground download service transfers media independently of the source application's own save/download action. Up to **three** downloads can run at once.
- Available playback candidates are ranked with wide/landscape variants first, then by pixel count and other available quality signals. The downloader tries the highest ranked accessible candidate and falls back to another candidate when a request fails or lacks audio.
- The selected file must be **one MP4 containing both video and audio**. The app checks that both tracks have samples, records the actual dimensions and codecs, and checks the tracks again after adding metadata. It does not save a video-only file or join a separate audio download.
- The transfer records progress, downloaded and total bytes when known, speed, stage, and estimated remaining time when calculable. Failed jobs remain visible with their error reason.
- Completed MP4s are saved to the phone's public Downloads collection. A temporary transfer file is removed after saving or failure.

## File names and embedded metadata

- New files use `<profile-name>_profile_<account-id>_VideoID_<video-id>_<yyyy-MM-dd_hh-mm>.mp4`. The timestamp comes from the post's publication time; the separator uses a hyphen in place of a filename-invalid colon.
- Each new MP4 embeds a validated public source-post permalink in Comment, an available title in Title, the full caption or post text in Description, and hashtags in Keywords. The permalink contains only the supported post path: all share query parameters and fragments, including account or session tokens, are removed before saving. This applies to TikTok, Instagram (posts, Reels, and Stories), and RedNote. Unsupported or malformed source links are rejected before file metadata is written. Missing source fields remain absent rather than being invented.
- The app verifies that metadata writing did not remove or alter the video and audio tracks before publishing the file.

## Downloads page and notifications

- The Downloads page lists active, completed, and failed jobs with counts, a progress bar, thumbnail, account/profile, filename, bytes transferred, file size, speed, and error or stage text as applicable.
- Expandable details show available video and account IDs, publication time, resolution, duration, video and audio codecs, start and finish times, and save location.
- A source-post link opens the post in its source application when possible, with Android's URL handler as a fallback.
- Every completed card has a **compact Open video button that remains visible with details collapsed**. Its thumbnail is also tappable. Both open the saved MP4 through Android's video-player handler.
- Progress and result notifications are silent. Tapping a job notification opens Media Downloader on the Downloads page and focuses that job and its details; the summary notification opens the Downloads page. Completion and failure remain visible in the list after the transfer ends.
- Download records and appearance settings are stored locally; custom icon images are stored in app-private files. The Downloads list can show its saved records without network access.

## Diagnostics and export

- Download, album, overlay, thumbnail, history-storage, icon-import, and unexpected app-crash failures are written to app-private files that survive normal relaunches and app updates. Separate operation traces record the stages and sanitized HTTP outcomes around a failure without displacing failure records.
- The app keeps separate log files for the current UTC date and six preceding dates, so later activity cannot rotate away an earlier day's file. On the eighth day, the oldest date's files are deleted. It checks retention at app startup, at each UTC day change while its process runs, and whenever logs are written, counted, or exported. Each day allows up to 512 KiB of failures and 256 KiB of operation traces; reaching a limit records an omission marker. No Android alarm or external cleanup process is involved.
- Settings shows the saved failure count. Export diagnostic report opens Android's document picker and writes a text report to the location the user chooses. It includes app and source-app versions, Android build and device details, display and network state, storage, permissions, button settings, recent active/unsuccessful job snapshots, failure events, and the operation trace. The job snapshot includes validated public post links without query strings, transfer sizes, selected formats, and photo errors so an issue can be investigated without connecting the phone. A report can be exported even when no failure has been recorded.
- Stored event details remove web URLs, local paths, long numeric IDs, and common secret values. Exported public post links are included only from validated TikTok, Instagram, or RedNote hosts and omit tracking parameters. The app does not read system logcat or upload reports automatically.

## Photo album downloads

- One tap on a photo post downloads the complete ordered image list supplied in the post data, including photos not yet swiped to. The resolver checks desktop, wide desktop, and mobile page responses for distinct signed renditions of each photo. The transfer fetches each discovered image address directly, without the source app's Save action or screenshots. For each photo it decodes the actual dimensions, chooses the most pixels (then the larger file at equal dimensions), and records whether the saved image matches TikTok's listed dimensions. The original upload resolution cannot be guaranteed when TikTok does not expose it.
- Each photo is saved to public Downloads as `<profile>_profile_<account>_PostID_<post>_<yyyy-MM-dd_hh-mm>_photo_<index>-of-<total>.<extension>`. Its actual image format and dimensions are checked before saving. The download record keeps the validated public permalink without query parameters or fragments, title, caption, hashtags, and each file's details. JPEGs also embed that sanitized permalink, available post text, hashtags, and creator in EXIF fields; PNG, WebP, and original HEIC metadata remain in the app record because Android's EXIF writer cannot save HEIC attributes. This rule applies to photo downloads from every supported app.
- One album job displays the saved photo count, bytes, speed while transferring, and one ordered row per photo with a thumbnail, actual dimensions, source-listed resolution comparison, filename, error, and Open photo action when available. Silent progress and result notifications focus the album card. A partial failure retains saved photos; Retry missing photos resolves fresh image URLs and skips photos already saved in that job.

## Instagram integration

- The button appears over Instagram with separate positions for scrolling feed posts and opened Reels. The share control must belong to the post occupying the main screen area; a previous post's controls at the top of a scrolling feed are ignored.
- At tap time, Accessibility opens the current post's share sheet and copies a fresh Instagram URL. The public embed resolver uses an anonymous request and reads the owner, post ID, caption, video URL, or ordered photo list when Instagram exposes them. If a single-photo embed has no structured media, the resolver accepts its responsive image list only after the image cache key matches the requested post ID. It accepts caption, hashtags, and owner ID from anonymous oEmbed only when its media ID and owner match that post; the public profile is an owner-ID fallback. It does not read the installed Instagram app's account session.
- Photo albums use the shared per-photo quality comparison and save each photo that matches Instagram's listed resolution. Video downloads use the shared muxed MP4 and embedded-source checks. Download cards link back to Instagram, including failed jobs with a valid post link.
- Availability depends on the public response and network route. Two tested single-photo posts saved the largest publicly listed responsive images, 1439×1455 and 1440×1440. Matching oEmbed text is recorded when exposed; missing text is not invented. One Reel exposed only a video-only file and failed visibly. A publicly listed rendition does not establish the original upload resolution.

## RedNote integration

- The button appears on RedNote video and photo post screens, with separate saved placements. It stays hidden on the Explore grid. A tap uses that post's Share control, including the unlabeled share icon on photo posts, and copies a fresh RedNote link.
- An anonymous mobile share page supplies the exact note ID, creator, publish time, title, caption, tags, and available video streams or ordered images. The resolver validates the note ID against a direct link and never imports RedNote's signed-in app session. Video streams are ranked by listed pixels and bitrate and must pass the shared muxed video and audio check.
- RedNote videos try the public original-upload asset first, without a post Referer, so platform branding and the account label are absent from the encoded picture when that asset is available. The downloader rejects known stamped `259` and `309` playback streams and uses only original assets or identified clean stream types. If RedNote exposes no accessible clean stream with both video and audio, the job fails visibly instead of saving a stamped video. This does not remove text that a creator put into the original upload.
- For albums, the resolver checks the public original-image CDN asset identified by each image's file ID along with the page's web renditions. The transfer selects the largest actual decoded dimensions and rejects a photo below RedNote's listed dimensions. Original assets may be HEIC; the app preserves that format and records source text in its download entry. JPEG renditions receive embedded EXIF metadata. The tested three-photo album saved two 4284×5712 HEIC originals and one 1080×1440 JPEG; a video post saved a 720×1214 H.264/AAC MP4 and a second saved at 720×1280.
- The Downloads page links each job to its RedNote post and retains per-photo progress, thumbnails, dimensions, failures, and retry. Public availability and quality can differ by post; the listed source dimensions alone do not establish the upload's original dimensions.

## Story downloads

- The target behavior is one tap on the currently visible TikTok or Instagram Story to save that Story's video with audio or its photo at the largest accessible source-listed resolution. The app must identify the individual Story at tap time and must never silently substitute a linked post, profile picture, screenshot, or smaller preview.
- TikTok Story share links that resolve to `/video/<id>` or `/photo/<id>` use the existing anonymous post resolver, video-track validation, and per-photo resolution comparison. Story-specific Share labels are recognized by the floating control. A Story that does not provide a current share link or public media appears as a failure.
- Instagram Stories have a separate link and media path: `/stories/<account>/<story-id>/`. The button copies the Story link from Instagram's Story share sheet. The resolver looks for that exact Story ID in anonymously available Story data, compares photo renditions or video variants, and uses the same save, metadata, progress, notification, and Downloads-page flows. Saved Story filenames use `StoryID` and the Downloads card identifies a Story.
- Story availability is time limited and source controlled. On tested visible NASA and National Geographic Stories, Instagram's anonymous Story endpoint returned no media even though each Story was visible in the installed app. Those Stories fail with an explicit reason. This integration does not access Instagram's signed-in session or claim full-resolution support for Stories whose media is withheld from anonymous requests.

## Adding another source application

The shared UI and transfer behavior can be reused, but capture and media discovery remain source-specific. Each new source integration needs:

1. A way to recognize the source application and show the floating control while it is in the foreground, with a clear response if no supported post is available.
2. A reliable, fresh way to identify the **current** video or photo album and obtain its post URL or ID without opening Media Downloader.
3. Parsing for account/profile, post ID, publication time, post text, hashtags, thumbnail, and source-post link where available; photo sources also need the complete ordered album list.
4. Discovery and ranking of accessible playback streams that contain audio, including wide variants when the source exposes them; photo sources need the best accessible image URL for each album item.
5. Source-specific URL validation, public cookie-free media requests, isolated anonymous visitor fallback when needed, failure handling, and an Open in source app link. A source integration must not import the source app's signed-in cookies or credentials for public media.

These are integration requirements, not claims that other applications already work. The app can only choose among playback streams it can discover and access; it cannot guarantee the original upload resolution or a download when a source rejects every stream. Android may disable the Accessibility service after a deliberate force-stop, requiring it to be enabled again. Files owned by an earlier Android package remain in Downloads but are not automatically imported into this package's history.
