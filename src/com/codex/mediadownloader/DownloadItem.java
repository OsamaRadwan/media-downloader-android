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
    final List<PhotoItem> photos;

    static final class PhotoItem {
        final int index, width, height, sourceWidth, sourceHeight;
        final long bytes;
        final String fileName, savedUri, error, mime, thumbnailPath;
        PhotoItem(int index, int width, int height, int sourceWidth, int sourceHeight, long bytes, String fileName,
                  String savedUri, String error, String mime, String thumbnailPath) {
            this.index = index; this.width = width; this.height = height;
            this.sourceWidth = sourceWidth; this.sourceHeight = sourceHeight; this.bytes = bytes;
            this.fileName = fileName; this.savedUri = savedUri; this.error = error; this.mime = mime;
            this.thumbnailPath = thumbnailPath;
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
        this.photos = Collections.unmodifiableList(new ArrayList<>(photos));
    }

    boolean isActive() {
        return !"complete".equals(state) && !"failed".equals(state) && !"partial".equals(state) && !"interrupted".equals(state);
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
