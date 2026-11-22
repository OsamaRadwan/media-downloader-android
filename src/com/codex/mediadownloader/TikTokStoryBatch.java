package com.codex.mediadownloader;

import java.util.ArrayList;
import java.util.List;

/** Orders links collected from TikTok's one-item-at-a-time Story player. */
final class TikTokStoryBatch {
    private static final int MAX_STORIES = 100;
    private final String accountTitle;
    private final List<Integer> segmentCenters;
    private final List<String> links = new ArrayList<>();

    TikTokStoryBatch(String accountTitle, List<Integer> segmentCenters) {
        if (accountTitle == null || accountTitle.trim().isEmpty())
            throw new IllegalArgumentException("Story account is unavailable");
        if (segmentCenters == null || segmentCenters.isEmpty() || segmentCenters.size() > MAX_STORIES)
            throw new IllegalArgumentException("Story progress segments are unavailable");
        this.accountTitle = accountTitle.trim();
        this.segmentCenters = new ArrayList<>(segmentCenters);
    }

    boolean isSameTitle(String title) {
        return title != null && accountTitle.equals(title.trim());
    }

    String accountTitle() { return accountTitle; }

    void captured(String url) {
        if (url == null || url.isEmpty())
            throw new IllegalArgumentException("Story did not provide a valid link");
        if (links.size() >= segmentCenters.size() * 6)
            throw new IllegalStateException("Story scan reached its retry limit");
        links.add(url);
    }

    boolean hasStories() { return !links.isEmpty(); }
    int segmentCount() { return segmentCenters.size(); }

    List<String> links() { return new ArrayList<>(links); }
}
