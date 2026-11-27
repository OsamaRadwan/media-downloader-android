package com.codex.mediadownloader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable state consumed by the Downloads view. */
final class DownloadsState {
    final List<DownloadItem> items;
    final int activeCount, completeCount, failedCount, missingCount;
    final int totalCount, matchedCount, pageIndex;

    DownloadsState(List<DownloadItem> items, int activeCount, int completeCount, int failedCount,
                   int missingCount) {
        this(items, activeCount, completeCount, failedCount, missingCount, items.size(), items.size(), 0);
    }

    DownloadsState(List<DownloadItem> items, int activeCount, int completeCount, int failedCount,
                   int missingCount, int totalCount, int matchedCount, int pageIndex) {
        this.items = Collections.unmodifiableList(new ArrayList<>(items));
        this.activeCount = activeCount;
        this.completeCount = completeCount;
        this.failedCount = failedCount;
        this.missingCount = missingCount;
        this.totalCount = totalCount;
        this.matchedCount = matchedCount;
        this.pageIndex = pageIndex;
    }
}
