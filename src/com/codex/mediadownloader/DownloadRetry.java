package com.codex.mediadownloader;

import java.net.URI;
import java.util.Locale;

/** Rules shared by the Downloads screen and the background retry entry point. */
final class DownloadRetry {
    private DownloadRetry() { }

    static String url(String shareUrl, String sourceUrl) {
        if (redNoteShortLink(shareUrl)) return shareUrl;
        if (sourceUrl != null && !sourceUrl.isEmpty()) return sourceUrl;
        return shareUrl == null ? "" : shareUrl;
    }

    private static boolean redNoteShortLink(String link) {
        if (link == null) return false;
        try {
            URI uri = new URI(link);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null ||
                uri.getPort() != -1 || uri.getUserInfo() != null) return false;
            host = host.toLowerCase(Locale.ROOT);
            return ("xhslink.cn".equals(host) || "www.xhslink.cn".equals(host)) &&
                uri.getPath().matches("^/o/[A-Za-z0-9]{6,32}$");
        } catch (Exception ignored) { return false; }
    }

    static String url(String shareUrl, String sourceUrl, String videoId) {
        String saved = url(shareUrl, sourceUrl);
        if (!saved.isEmpty()) return saved;
        return videoId != null && videoId.matches("[0-9]{15,22}") ?
            "https://www.tiktok.com/@_/video/" + videoId : "";
    }

    /** A newly copied RedNote short link can refresh a failed record after post-ID validation. */
    static String retryUrl(String shareUrl, String sourceUrl, String videoId, String freshLink) {
        if (redNoteShortLink(freshLink) &&
            DownloadIdentity.fromEntry(sourceUrl, shareUrl, videoId).startsWith("rednote:"))
            return freshLink;
        return url(shareUrl, sourceUrl, videoId);
    }

    static boolean allowed(String state, String mediaType, int availablePhotos, int photoCount,
                           boolean videoPresent, String shareUrl, String sourceUrl, String videoId) {
        if (url(shareUrl, sourceUrl, videoId).isEmpty()) return false;
        boolean failed = "failed".equals(state) || "interrupted".equals(state);
        if ("album".equals(mediaType))
            return ((failed || "complete".equals(state)) && photoCount == 0) ||
                (failed || "partial".equals(state) || "complete".equals(state)) &&
                photoCount > 0 && availablePhotos < photoCount;
        if (failed) return !videoPresent;
        return "complete".equals(state) && !videoPresent;
    }

    /** An explicit retry may restore its own missing file even if another history card is complete. */
    static boolean blockedByOther(boolean otherActive, boolean otherPresent,
                                  boolean restoringRecordedFile) {
        return otherActive || otherPresent && !restoringRecordedFile;
    }
}
