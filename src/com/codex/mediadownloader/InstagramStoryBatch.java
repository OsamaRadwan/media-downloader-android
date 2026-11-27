package com.codex.mediadownloader;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Collects only distinct Story links belonging to the visible Instagram account. */
final class InstagramStoryBatch {
    private final String account;
    private final int segmentCount;
    private final LinkedHashMap<String, String> linksById = new LinkedHashMap<>();
    private int attempts;

    InstagramStoryBatch(String account, int segmentCount) {
        if (account == null || !account.matches("[A-Za-z0-9._]{1,40}"))
            throw new IllegalArgumentException("Story account is unavailable");
        if (segmentCount < 1 || segmentCount > 100)
            throw new IllegalArgumentException("Story progress segments are unavailable");
        this.account = account;
        this.segmentCount = segmentCount;
    }

    boolean isSameAccount(String candidate) {
        return candidate != null && account.equalsIgnoreCase(candidate);
    }

    int segmentCount() { return segmentCount; }
    int collectedCount() { return linksById.size(); }
    boolean isComplete() { return linksById.size() == segmentCount; }

    void captured(String copiedLink) throws IOException {
        if (++attempts > segmentCount * 3)
            throw new IOException("Story scan reached its retry limit");
        String canonical = SourcePostUrl.forMetadata(copiedLink);
        String[] path = URI.create(canonical).getPath().split("/");
        if (path.length != 4 || !"stories".equals(path[1]) ||
            !isSameAccount(path[2]) || !path[3].matches("[0-9]{15,22}"))
            throw new IOException("Copied link is not a Story from this account");
        linksById.putIfAbsent(path[3], canonical);
        if (linksById.size() > segmentCount)
            throw new IOException("Story scan found more IDs than visible segments");
    }

    List<String> links() { return new ArrayList<>(linksById.values()); }
}
