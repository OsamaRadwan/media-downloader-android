package com.codex.mediadownloader;

import java.util.List;

/** Owns screen-level state calculation; the Activity owns Android lifecycle polling. */
final class DownloadsController {
    private final DownloadRepository repository;

    DownloadsController(DownloadRepository repository) {
        this.repository = repository;
    }

    DownloadsState load() {
        List<DownloadItem> items = repository.list();
        int active = 0, complete = 0, failed = 0;
        for (DownloadItem item : items) {
            if (item.isActive()) active++;
            else if ("complete".equals(item.state)) complete++;
            else failed++;
        }
        return new DownloadsState(items, active, complete, failed);
    }
}
