package com.codex.mediadownloader;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A storage-independent snapshot for the Downloads screen. */
final class DownloadItem {
    final int id;
    final long createdAt, updatedAt, finishedAt, publishTimeSeconds;
    final long downloadedBytes, totalBytes, speedBytesPerSecond, durationMs;
    final int width, height, streamOption;
    final String state, stage, profileName, accountId, videoId, shareUrl, fileName;
    final String thumbnailPath, savedUri, error, videoMime, audioMime;
    final String mediaType, sourceUrl, postTitle, caption, hashtags;
    final boolean savedFilePresent;
    final List<PhotoItem> photos;

    static final class PhotoItem {
        final int index, width, height, sourceWidth, sourceHeight;
        final long bytes, durationMs;
        final String fileName, savedUri, error, mime, thumbnailPath, kind, videoMime, audioMime;
        final boolean filePresent;
        PhotoItem(int index, int width, int height, int sourceWidth, int sourceHeight, long bytes, String fileName,
                  String savedUri, String error, String mime, String thumbnailPath) {
            this(index, width, height, sourceWidth, sourceHeight, bytes, fileName, savedUri, error, mime,
                thumbnailPath, !savedUri.isEmpty());
        }
        PhotoItem(int index, int width, int height, int sourceWidth, int sourceHeight, long bytes, String fileName,
                  String savedUri, String error, String mime, String thumbnailPath, boolean filePresent) {
            this(index, width, height, sourceWidth, sourceHeight, bytes, fileName, savedUri, error,
                mime, thumbnailPath, filePresent, "photo", "", "", 0);
        }
        PhotoItem(int index, int width, int height, int sourceWidth, int sourceHeight, long bytes, String fileName,
                  String savedUri, String error, String mime, String thumbnailPath, boolean filePresent,
                  String kind, String videoMime, String audioMime, long durationMs) {
            this.index = index; this.width = width; this.height = height;
            this.sourceWidth = sourceWidth; this.sourceHeight = sourceHeight; this.bytes = bytes;
            this.fileName = fileName; this.savedUri = savedUri; this.error = error; this.mime = mime;
            this.thumbnailPath = thumbnailPath;
            this.filePresent = filePresent;
            this.kind = "video".equals(kind) ? "video" : "photo";
            this.videoMime = videoMime; this.audioMime = audioMime; this.durationMs = durationMs;
        }
    }

    DownloadItem(int id, long createdAt, long updatedAt, long finishedAt, long publishTimeSeconds,
                 long downloadedBytes, long totalBytes, long speedBytesPerSecond, long durationMs,
                 int width, int height, int streamOption, String state, String stage, String profileName,
                 String accountId, String videoId, String shareUrl, String fileName, String thumbnailPath, String savedUri,
                 String error, String videoMime, String audioMime) {
        this(id, createdAt, updatedAt, finishedAt, publishTimeSeconds, downloadedBytes, totalBytes,
            speedBytesPerSecond, durationMs, width, height, streamOption, state, stage, profileName,
            accountId, videoId, shareUrl, fileName, thumbnailPath, savedUri, error, videoMime, audioMime,
            "video", "", "", "", "", Collections.emptyList());
    }

    DownloadItem(int id, long createdAt, long updatedAt, long finishedAt, long publishTimeSeconds,
                 long downloadedBytes, long totalBytes, long speedBytesPerSecond, long durationMs,
                 int width, int height, int streamOption, String state, String stage, String profileName,
                 String accountId, String videoId, String shareUrl, String fileName, String thumbnailPath, String savedUri,
                 String error, String videoMime, String audioMime, String mediaType, String sourceUrl,
                 String postTitle, String caption, String hashtags, List<PhotoItem> photos) {
        this(id, createdAt, updatedAt, finishedAt, publishTimeSeconds, downloadedBytes, totalBytes,
            speedBytesPerSecond, durationMs, width, height, streamOption, state, stage, profileName,
            accountId, videoId, shareUrl, fileName, thumbnailPath, savedUri, error, videoMime, audioMime,
            mediaType, sourceUrl, postTitle, caption, hashtags, photos, !savedUri.isEmpty());
    }

    DownloadItem(int id, long createdAt, long updatedAt, long finishedAt, long publishTimeSeconds,
                 long downloadedBytes, long totalBytes, long speedBytesPerSecond, long durationMs,
                 int width, int height, int streamOption, String state, String stage, String profileName,
                 String accountId, String videoId, String shareUrl, String fileName, String thumbnailPath, String savedUri,
                 String error, String videoMime, String audioMime, String mediaType, String sourceUrl,
                 String postTitle, String caption, String hashtags, List<PhotoItem> photos, boolean savedFilePresent) {
        this.id = id;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.finishedAt = finishedAt;
        this.publishTimeSeconds = publishTimeSeconds;
        this.downloadedBytes = downloadedBytes;
        this.totalBytes = totalBytes;
        this.speedBytesPerSecond = speedBytesPerSecond;
        this.durationMs = durationMs;
        this.width = width;
        this.height = height;
        this.streamOption = streamOption;
        this.state = state;
        this.stage = stage;
        this.profileName = profileName;
        this.accountId = accountId;
        this.videoId = videoId;
        this.shareUrl = shareUrl;
        this.fileName = fileName;
        this.thumbnailPath = thumbnailPath;
        this.savedUri = savedUri;
        this.error = error;
        this.videoMime = videoMime;
        this.audioMime = audioMime;
        this.mediaType = mediaType;
        this.sourceUrl = sourceUrl;
        this.postTitle = postTitle;
        this.caption = caption;
        this.hashtags = hashtags;
        this.savedFilePresent = savedFilePresent;
        this.photos = Collections.unmodifiableList(new ArrayList<>(photos));
    }

    boolean isActive() {
        return !"complete".equals(state) && !"failed".equals(state) && !"partial".equals(state) && !"interrupted".equals(state);
    }

    boolean missingFile() {
        if (!"complete".equals(state)) return false;
        if (!"album".equals(mediaType)) return !savedFilePresent;
        if (photos.isEmpty()) return true;
        for (PhotoItem photo : photos) if (!photo.filePresent) return true;
        return false;
    }

    String tiktokUrl() {
        String path = "album".equals(mediaType) ? "photo" : "video";
        if (videoId.matches("[0-9]{15,22}") && profileName.matches("[A-Za-z0-9._-]+"))
            return "https://www.tiktok.com/@" + profileName + "/" + path + "/" + videoId;
        String validShareUrl = validShareUrl();
        if (!validShareUrl.isEmpty()) return validShareUrl;
        if (videoId.matches("[0-9]{15,22}"))
            return "https://www.tiktok.com/@_/" + path + "/" + videoId;
        return "";
    }

    String postUrl() {
        String preferred = isRedNoteUrl(sourceUrl) ? sourceUrl :
            isRedNoteUrl(shareUrl) ? shareUrl :
            isInstagramUrl(sourceUrl) ? sourceUrl :
            isInstagramUrl(shareUrl) ? shareUrl : tiktokUrl();
        String publicUrl = DiagnosticsFormat.publicPostUrl(preferred);
        return "unavailable".equals(publicUrl) ? "" : publicUrl;
    }

    boolean fromInstagram() {
        return isInstagramUrl(sourceUrl) || isInstagramUrl(shareUrl);
    }

    boolean fromRedNote() {
        return isRedNoteUrl(sourceUrl) || isRedNoteUrl(shareUrl);
    }

    private static boolean isRedNoteUrl(String value) {
        try {
            URI uri = new URI(value);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getPort() != -1) return false;
            host = host.toLowerCase(java.util.Locale.ROOT);
            if (host.equals("xhslink.cn") || host.equals("www.xhslink.cn"))
                return uri.getPath().matches("/o/[A-Za-z0-9]{6,32}");
            return (host.equals("rednote.com") || host.equals("www.rednote.com") ||
                host.equals("xiaohongshu.com") || host.equals("www.xiaohongshu.com")) &&
                uri.getPath().matches("/(discovery/item|explore)/[a-fA-F0-9]{24}/?");
        } catch (Exception ignored) { return false; }
    }

    private static boolean isInstagramUrl(String value) {
        try {
            URI uri = new URI(value);
            String host = uri.getHost();
            return "https".equalsIgnoreCase(uri.getScheme()) && host != null &&
                (host.equalsIgnoreCase("instagram.com") || host.equalsIgnoreCase("www.instagram.com")) &&
                (uri.getPath().matches("/(p|reel|tv)/[A-Za-z0-9_-]{5,20}/?") ||
                 uri.getPath().matches("/stories/[A-Za-z0-9._]{1,40}/[0-9]{15,22}/?"));
        } catch (Exception ignored) { return false; }
    }

    private String validShareUrl() {
        try {
            URI parsed = new URI(shareUrl);
            String host = parsed.getHost();
            if (!"https".equalsIgnoreCase(parsed.getScheme()) || host == null) return "";
            host = host.toLowerCase(java.util.Locale.ROOT);
            if (host.equals("tiktok.com") || host.endsWith(".tiktok.com") ||
                host.equals("tiktokv.com") || host.endsWith(".tiktokv.com")) return shareUrl;
        } catch (Exception ignored) { }
        return "";
    }
}
