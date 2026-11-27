package com.codex.mediadownloader;

import android.net.Uri;
import java.util.List;

/** Application-facing read contract for download history. */
interface DownloadRepository {
    List<DownloadItem> list();
    void importExistingAsync();
    default boolean relink(int jobId, int photoIndex, Uri selected) { return false; }

    default DownloadsState page(String query, int requestedPage, int focusId) {
        List<DownloadItem> items = list();
        int active = 0, complete = 0, failed = 0, missing = 0;
        for (DownloadItem item : items) {
            if (item.isActive()) active++;
            else if (item.missingFile()) missing++;
            else if ("complete".equals(item.state)) complete++;
            else failed++;
        }
        List<DownloadItem> matched = DownloadHistoryQuery.filter(items, query);
        int page = Math.max(0, Math.min(requestedPage,
            Math.max(0, (matched.size() - 1) / DownloadsController.PAGE_SIZE)));
        if (focusId > 0) for (int i = 0; i < matched.size(); i++)
            if (matched.get(i).id == focusId) { page = i / DownloadsController.PAGE_SIZE; break; }
        int start = page * DownloadsController.PAGE_SIZE;
        return new DownloadsState(matched.subList(start,
            Math.min(start + DownloadsController.PAGE_SIZE, matched.size())),
            active, complete, failed, missing, items.size(), matched.size(), page);
    }
}
