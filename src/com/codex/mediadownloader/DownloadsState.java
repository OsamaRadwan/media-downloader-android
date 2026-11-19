package com.codex.mediadownloader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable state consumed by the Downloads view. */
final class DownloadsState {
    final List<DownloadItem> items;
    final int activeCount, completeCount, failedCount;

    DownloadsState(List<DownloadItem> items, int activeCount, int completeCount, int failedCount) {
        this.items = Collections.unmodifiableList(new ArrayList<>(items));
        this.activeCount = activeCount;
        this.completeCount = completeCount;
        this.failedCount = failedCount;
    }
}
