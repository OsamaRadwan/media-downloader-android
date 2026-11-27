package com.codex.mediadownloader;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Local, source-independent search over persisted download details. */
final class DownloadHistoryQuery {
    private DownloadHistoryQuery() { }

    static List<DownloadItem> filter(List<DownloadItem> items, String query) {
        String normalized = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) return new ArrayList<>(items);
        String[] terms = normalized.split("\\s+");
        List<DownloadItem> matches = new ArrayList<>();
        for (DownloadItem item : items) {
            String text = searchable(item);
            if (matchesText(text, terms)) matches.add(item);
        }
        return matches;
    }

    static boolean matchesText(String text, String[] terms) {
        for (String term : terms) if (!text.contains(term)) return false;
        return true;
    }

    static String[] terms(String query) {
        String normalized = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? new String[0] : normalized.split("\\s+");
    }

    static String searchable(DownloadItem item) {
        StringBuilder text = new StringBuilder();
        add(text, item.fromInstagram() ? "Instagram" : item.fromRedNote() ? "RedNote" : "TikTok");
        add(text, item.mediaType); add(text, item.state); add(text, item.stage);
        add(text, item.profileName); add(text, item.accountId); add(text, item.videoId);
        add(text, item.fileName); add(text, item.shareUrl); add(text, item.sourceUrl);
        add(text, item.postTitle); add(text, item.caption); add(text, item.hashtags);
        add(text, item.videoMime); add(text, item.audioMime); add(text, item.error);
        add(text, item.width + "x" + item.height);
        add(text, Long.toString(item.downloadedBytes));
        if (item.publishTimeSeconds > 0) add(text, date(item.publishTimeSeconds * 1000));
        if (item.createdAt > 0) add(text, date(item.createdAt));
        for (DownloadItem.PhotoItem photo : item.photos) {
            add(text, photo.fileName); add(text, photo.mime); add(text, photo.error);
            add(text, photo.kind); add(text, photo.videoMime); add(text, photo.audioMime);
            add(text, photo.width + "x" + photo.height);
        }
        return text.toString().toLowerCase(Locale.ROOT);
    }

    private static String date(long millis) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(new Date(millis));
    }

    private static void add(StringBuilder text, String value) {
        if (value != null && !value.isEmpty()) text.append(' ').append(value);
    }
}
