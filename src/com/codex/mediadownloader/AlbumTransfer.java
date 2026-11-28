package com.codex.mediadownloader;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import androidx.exifinterface.media.ExifInterface;
import android.net.Uri;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Transfers ordered album items while retaining successful files on partial failure. */
final class AlbumTransfer {
    interface Progress { void changed(DownloadStore.Entry entry, int current, int total, boolean durable); }
    interface VideoDownloader {
        VideoFile download(StreamResolver.AlbumItem item, String name, long savedBytes) throws Exception;
    }

    static final class VideoFile {
        final Uri uri;
        final String name, thumbnailPath;
        final long bytes;
        final MediaMux.Tracks tracks;
        VideoFile(Uri uri, String name, String thumbnailPath, long bytes, MediaMux.Tracks tracks) {
            this.uri = uri; this.name = name; this.thumbnailPath = thumbnailPath;
            this.bytes = bytes; this.tracks = tracks;
        }
    }

    static final class Summary {
        final int complete, failed;
        Summary(int complete, int failed) { this.complete = complete; this.failed = failed; }
    }

    private AlbumTransfer() { }

    static Summary download(Context context, StreamResolver.Result result, DownloadStore.Entry entry,
                            Progress progress, VideoDownloader videoDownloader) throws Exception {
        if (!result.isAlbum()) throw new IllegalArgumentException("Post is not an album");
        if (result.hasAlbumVideos() && videoDownloader == null)
            throw new IllegalArgumentException("Album video transfer is unavailable");
        if (!entry.photos.isEmpty() && entry.photos.size() != result.albumItems.size())
            throw new Exception("Album changed since the earlier download; retry as a new job");
        if (entry.photos.isEmpty()) for (StreamResolver.AlbumItem item : result.albumItems) {
            DownloadStore.Entry.Photo record = new DownloadStore.Entry.Photo(item.index);
            record.kind = item.video == null ? "photo" : "video";
            record.sourceId = item.sourceId;
            entry.photos.add(record);
        }
        for (StreamResolver.AlbumItem item : result.albumItems) {
            DownloadStore.Entry.Photo record = entry.photos.get(item.index - 1);
            AlbumItemIdentity.validate(item, record.index, record.kind, record.sourceId);
        }
        entry.mediaType = "album";
        entry.fileName = result.hasAlbumVideos() ? result.albumItems.size() + " items • post " + result.videoId :
            result.photos.size() + " photos • post " + result.videoId;
        entry.totalBytes = -1;
        progress.changed(entry, 0, result.albumItems.size(), false);
        for (StreamResolver.AlbumItem item : result.albumItems) {
            DownloadStore.Entry.Photo photo = entry.photos.get(item.index - 1);
            if (!photo.savedUri.isEmpty()) continue;
            entry.stage = (item.video == null ? "Photo " : "Video ") + item.index +
                " of " + result.albumItems.size();
            entry.state = "downloading";
            photo.error = "";
            progress.changed(entry, item.index, result.albumItems.size(), false);
            try {
                if (item.video != null) {
                    VideoFile saved = videoDownloader.download(item,
                        videoFileName(result, item.index), savedBytes(entry));
                    photo.fileName = saved.name;
                    photo.savedUri = saved.uri.toString();
                    photo.mime = "video/mp4";
                    photo.width = saved.tracks.width;
                    photo.height = saved.tracks.height;
                    photo.sourceWidth = item.video.get(0).width;
                    photo.sourceHeight = item.video.get(0).height;
                    photo.bytes = saved.bytes;
                    photo.durationMs = saved.tracks.durationMs;
                    photo.videoMime = saved.tracks.videoMime;
                    photo.audioMime = saved.tracks.audioMime;
                    photo.thumbnailPath = saved.thumbnailPath;
                    Log.i("MediaDownloader", "Saved album video " + item.index + "/" +
                        result.albumItems.size() + " " + saved.name + " " + photo.width + "x" + photo.height);
                } else {
                    StreamResolver.PhotoCandidate candidate = item.photo;
                    File firstFile = new File(context.getCacheDir(), "photo-" + entry.id + "-" + item.index + "-a");
                    File secondFile = new File(context.getCacheDir(), "photo-" + entry.id + "-" + item.index + "-b");
                    try {
                        Selection selection = selectBest(context, result, entry, candidate, progress, firstFile, secondFile);
                        PhotoFile image = selection.image;
                        File selected = selection.file;
                        if ("image/jpeg".equals(image.mime) || "image/png".equals(image.mime) ||
                            "image/webp".equals(image.mime) || "image/heic".equals(image.mime))
                            tagPhoto(context, selected, result, entry.sourceUrl,
                                image.mime, image.width, image.height);
                        String name = fileName(result, item.index, image.extension);
                        Saved saved = save(context, selected, name, image.mime);
                        photo.fileName = saved.name;
                        photo.savedUri = saved.uri.toString();
                        photo.mime = image.mime;
                        photo.width = image.width;
                        photo.height = image.height;
                        photo.sourceWidth = candidate.width;
                        photo.sourceHeight = candidate.height;
                        photo.bytes = selected.length();
                        photo.thumbnailPath = DownloadThumbnail.fromAlbumPhoto(context, entry.id, item.index, selected);
                        entry.downloadedBytes = savedBytes(entry);
                        if (item.index == 1) DownloadThumbnail.fromImage(context, entry.id, selected);
                        Log.i("MediaDownloader", "Saved album photo " + item.index + "/" + result.albumItems.size() +
                            " " + saved.name + " " + image.width + "x" + image.height +
                            " listed=" + candidate.width + "x" + candidate.height);
                    } finally {
                        firstFile.delete();
                        secondFile.delete();
                    }
                }
            } catch (Exception error) {
                photo.error = error.getMessage() == null ? "Media unavailable" : error.getMessage();
                DiagnosticsStore.record(context, "album.item job=" + entry.id + " index=" + item.index, error);
                Log.w("MediaDownloader", "Album item " + item.index + " failed", error);
            } finally {
                entry.downloadedBytes = savedBytes(entry);
                entry.speedBytesPerSecond = 0;
                progress.changed(entry, item.index, result.albumItems.size(), true);
            }
        }
        int complete = 0;
        for (DownloadStore.Entry.Photo photo : entry.photos) if (!photo.savedUri.isEmpty()) complete++;
        return new Summary(complete, entry.photos.size() - complete);
    }

    static Selection selectBest(Context context, StreamResolver.Result result, DownloadStore.Entry entry,
                                StreamResolver.PhotoCandidate candidate, Progress progress,
                                File firstFile, File secondFile) throws Exception {
        PhotoFile best = null;
        File selected = null;
        Exception lastError = null;
        List<String> visitorFallbacks = new ArrayList<>();
        for (String url : candidate.urls) {
            File optionFile = selected == firstFile ? secondFile : firstFile;
            try {
                PhotoFile option = fetch(context, result, entry, candidate, url, optionFile, progress, false);
                if (("instagram".equals(result.platform) || "rednote".equals(result.platform)) &&
                    !PhotoQuality.matchesAspect(option.width, option.height, candidate.width, candidate.height))
                    throw new Exception("Photo rendition has a different crop from the source listing");
                if (best == null || PhotoQuality.better(option.width, option.height, optionFile.length(),
                    best.width, best.height, selected.length())) {
                    if (selected != null) selected.delete();
                    best = option;
                    selected = optionFile;
                } else optionFile.delete();
            } catch (Exception error) {
                lastError = error;
                Log.w("MediaDownloader", "Photo candidate host=" + new URL(url).getHost() +
                    " failed: " + error.getMessage());
                if ("tiktok".equals(result.platform) && MediaRequestCookies.retryWithVisitorCookies(error)) {
                    visitorFallbacks.add(url);
                    DiagnosticsStore.record(context, "album.public job=" + entry.id + " index=" + candidate.index, error);
                }
                optionFile.delete();
            }
        }
        long listedPixels = (long) candidate.width * candidate.height;
        boolean fullPublic = best != null && (listedPixels <= 0 || (long) best.width * best.height >= listedPixels);
        if (!fullPublic) for (String url : visitorFallbacks) {
            File optionFile = selected == firstFile ? secondFile : firstFile;
            try {
                PhotoFile option = fetch(context, result, entry, candidate, url, optionFile, progress, true);
                if (best == null || PhotoQuality.better(option.width, option.height, optionFile.length(),
                    best.width, best.height, selected.length())) {
                    if (selected != null) selected.delete();
                    best = option;
                    selected = optionFile;
                } else optionFile.delete();
            } catch (Exception error) {
                lastError = error;
                optionFile.delete();
            }
        }
        if (best == null) throw new Exception(lastError == null ? "No image URL worked" : lastError.getMessage());
        if (("instagram".equals(result.platform) || "rednote".equals(result.platform)) && listedPixels > 0 &&
            (long) best.width * best.height < listedPixels) {
            selected.delete();
            throw new Exception("The public page exposed only a photo below its listed resolution");
        }
        DiagnosticsStore.trace(context, "album.selected", "job=" + entry.id + " index=" + candidate.index +
            " listed=" + candidate.width + "x" + candidate.height +
            " actual=" + best.width + "x" + best.height + " bytes=" + selected.length() +
            " fullPublic=" + fullPublic);
        return new Selection(best, selected);
    }

    static final class Selection {
        final PhotoFile image;
        final File file;
        Selection(PhotoFile image, File file) { this.image = image; this.file = file; }
    }

    private static PhotoFile fetch(Context context, StreamResolver.Result result, DownloadStore.Entry entry,
                                   StreamResolver.PhotoCandidate candidate, String url, File destination,
                                   Progress progress, boolean visitorCookies) throws Exception {
        HttpURLConnection connection = NetworkRouteStore.open(new URL(url));
        try {
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", StreamResolver.USER_AGENT);
            // RedNote's public original-image CDN rejects a post Referer even though
            // the same file is available anonymously without one.
            if (!("rednote".equals(result.platform) && new URL(url).getHost().endsWith(".xhscdn.com")))
                connection.setRequestProperty("Referer", result.pageUrl);
            connection.setRequestProperty("Accept", "image/heic,image/avif,image/webp,image/png,image/jpeg,image/*;q=0.8");
            MediaRequestCookies.attach(connection, result.cookies, visitorCookies);
            long requestStarted = SystemClock.elapsedRealtime();
            DiagnosticsStore.trace(context, "album.request", "job=" + entry.id + " index=" + candidate.index +
                " host=" + connection.getURL().getHost() +
                " mode=" + (visitorCookies ? "visitor" : "public") +
                " listed=" + candidate.width + "x" + candidate.height);
            int status = connection.getResponseCode();
            DiagnosticsStore.trace(context, "album.response", "job=" + entry.id + " index=" + candidate.index +
                " host=" + connection.getURL().getHost() + " http=" + status +
                " type=" + connection.getContentType() + " bytes=" + connection.getContentLengthLong() +
                " elapsedMs=" + (SystemClock.elapsedRealtime() - requestStarted));
            if (status != 200 && status != 206)
                throw new Exception("Image server returned HTTP " + status);
            long length = connection.getContentLengthLong();
            if (length > 40_000_000) throw new Exception("Photo exceeds 40 MB limit");
            long previousBytes = 0;
            long previousTime = SystemClock.elapsedRealtime();
            long written = 0;
            try (InputStream input = connection.getInputStream(); OutputStream output = new FileOutputStream(destination)) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    written += count;
                    if (written > 40_000_000) throw new Exception("Photo exceeds 40 MB limit");
                    output.write(buffer, 0, count);
                    long now = SystemClock.elapsedRealtime();
                    if (now - previousTime >= 800) {
                        entry.downloadedBytes = savedBytes(entry) + written;
                        entry.speedBytesPerSecond = (written - previousBytes) * 1000 / Math.max(1, now - previousTime);
                        progress.changed(entry, candidate.index, result.albumItems.size(), false);
                        previousBytes = written;
                        previousTime = now;
                    }
                }
                output.flush();
            }
            MediaResponseLength.verify(status, length, connection.getHeaderField("Content-Range"), written);
            if (written < 100) throw new Exception("Image response was too short");
            byte[] head = new byte[12];
            try (InputStream input = new FileInputStream(destination)) {
                if (input.read(head) < head.length) throw new Exception("Image header was incomplete");
            }
            String mime, extension;
            if ((head[0] & 255) == 0xff && (head[1] & 255) == 0xd8) {
                mime = "image/jpeg"; extension = "jpg";
            } else if ((head[0] & 255) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G') {
                mime = "image/png"; extension = "png";
            } else if (head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F' &&
                       head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P') {
                mime = "image/webp"; extension = "webp";
            } else if (head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p' &&
                       head[8] == 'h' && head[9] == 'e' && head[10] == 'i' &&
                       (head[11] == 'c' || head[11] == 'x')) {
                mime = "image/heic"; extension = "heic";
            } else throw new Exception("Image server returned an unsupported file");
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(destination.getAbsolutePath(), bounds);
            if (bounds.outWidth < 1 || bounds.outHeight < 1) throw new Exception("Image could not be decoded");
            DiagnosticsStore.trace(context, "album.received", "job=" + entry.id + " index=" + candidate.index +
                " bytes=" + written + " actual=" + bounds.outWidth + "x" + bounds.outHeight +
                " elapsedMs=" + (SystemClock.elapsedRealtime() - requestStarted));
            Log.i("MediaDownloader", "Photo candidate " + candidate.index + " from " +
                connection.getURL().getHost() + " " + bounds.outWidth + "x" + bounds.outHeight +
                " mime=" + mime);
            return new PhotoFile(mime, extension, bounds.outWidth, bounds.outHeight);
        } finally { connection.disconnect(); }
    }

    private static void tagPhoto(Context context, File file, StreamResolver.Result result,
                                 String sourceUrl, String mime, int width, int height) throws Exception {
        try {
            String description = result.postText.description;
            String comment = SourcePostUrl.forMetadata(sourceUrl);
            if ("image/heic".equals(mime)) {
                String previousXmp = null;
                try {
                    previousXmp = HeicXmpMetadata.read(file);
                } catch (Exception unsupported) {
                    // Preserve the existing record-only behavior for HEIC layouts
                    // that cannot be updated without rewriting image data.
                    DiagnosticsStore.trace(context, "photo.heic_xmp",
                        "Unsupported HEIC metadata layout; retaining source details in the download record");
                }
                if (previousXmp != null) {
                    String xmp = PhotoSourceMetadata.xmp(previousXmp, comment,
                        result.profileName, result.postText);
                    HeicXmpMetadata.write(file, xmp);
                    if (!xmp.equals(HeicXmpMetadata.read(file)))
                        throw new Exception("Saved HEIC XMP did not match the source post");
                    if (!xmp.equals(new ExifInterface(file).getAttribute(ExifInterface.TAG_XMP)))
                        throw new Exception("Android could not read saved HEIC XMP");
                }
            } else {
                ExifInterface exif = new ExifInterface(file);
                String xmp = PhotoSourceMetadata.xmp(exif.getAttribute(ExifInterface.TAG_XMP),
                    comment, result.profileName, result.postText);
                if (!description.isEmpty() && PhotoSourceMetadata.ascii(description))
                    exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, description);
                if (PhotoSourceMetadata.ascii(result.profileName))
                    exif.setAttribute(ExifInterface.TAG_ARTIST, result.profileName);
                exif.setAttribute(ExifInterface.TAG_USER_COMMENT, comment);
                exif.setAttribute(ExifInterface.TAG_XMP, xmp);
                exif.saveAttributes();
                ExifInterface saved = new ExifInterface(file);
                if (!comment.equals(saved.getAttribute(ExifInterface.TAG_USER_COMMENT)))
                    throw new Exception("Saved photo metadata did not match the source post");
                if (!xmp.equals(saved.getAttribute(ExifInterface.TAG_XMP)))
                    throw new Exception("Saved photo XMP did not match the source post");
                if (!description.isEmpty() && PhotoSourceMetadata.ascii(description) &&
                    !description.equals(saved.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION)))
                    throw new Exception("Saved photo description did not match the source post");
                if (!result.profileName.isEmpty() && PhotoSourceMetadata.ascii(result.profileName) &&
                    !result.profileName.equals(saved.getAttribute(ExifInterface.TAG_ARTIST)))
                    throw new Exception("Saved photo creator did not match the source post");
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            if (bounds.outWidth != width || bounds.outHeight != height)
                throw new Exception("Photo dimensions changed after metadata writing");
        } catch (Exception error) {
            DiagnosticsStore.record(context, "album.metadata", error);
            Log.w("MediaDownloader", "Could not embed or verify photo metadata", error);
            throw error;
        }
    }

    private static Saved save(Context context, File file, String name, String mime) throws Exception {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new Exception("Could not create photo in Downloads");
        try {
            try (InputStream input = new FileInputStream(file);
                 OutputStream output = context.getContentResolver().openOutputStream(uri)) {
                if (output == null) throw new Exception("Could not write photo");
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                output.flush();
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            context.getContentResolver().update(uri, done, null, null);
            try (Cursor cursor = context.getContentResolver().query(uri,
                new String[]{MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
            }
            return new Saved(uri, name);
        } catch (Exception error) {
            context.getContentResolver().delete(uri, null, null);
            throw error;
        }
    }

    private static long savedBytes(DownloadStore.Entry entry) {
        long total = 0;
        for (DownloadStore.Entry.Photo photo : entry.photos) if (!photo.savedUri.isEmpty()) total += photo.bytes;
        return total;
    }

    private static String fileName(StreamResolver.Result result, int index, String extension) {
        String date = new SimpleDateFormat("yyyy-MM-dd_hh-mm", Locale.US)
            .format(new Date(result.publishTimeSeconds * 1000L));
        String idLabel = result.pageUrl.contains("/stories/") ? "StoryID" : "PostID";
        return String.format(Locale.US, "%s_profile_%s_%s_%s_%s_photo_%03d-of-%03d.%s",
            result.profileName, result.accountId, idLabel, result.videoId, date, index, result.albumItems.size(), extension);
    }

    private static String videoFileName(StreamResolver.Result result, int index) {
        String date = new SimpleDateFormat("yyyy-MM-dd_hh-mm", Locale.US)
            .format(new Date(result.publishTimeSeconds * 1000L));
        return String.format(Locale.US, "%s_profile_%s_PostID_%s_%s_video_%03d-of-%03d.mp4",
            result.profileName, result.accountId, result.videoId, date, index, result.albumItems.size());
    }

    static final class PhotoFile {
        final String mime, extension;
        final int width, height;
        PhotoFile(String mime, String extension, int width, int height) {
            this.mime = mime; this.extension = extension; this.width = width; this.height = height;
        }
    }

    private static final class Saved {
        final Uri uri;
        final String name;
        Saved(Uri uri, String name) { this.uri = uri; this.name = name; }
    }
}
