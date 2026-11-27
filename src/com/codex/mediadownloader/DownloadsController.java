package com.codex.mediadownloader;

import android.net.Uri;
/** Owns screen-level state calculation; the Activity owns Android lifecycle polling. */
final class DownloadsController {
    static final int PAGE_SIZE = 15;
    private final DownloadRepository repository;

    DownloadsController(DownloadRepository repository) {
        this.repository = repository;
    }

    DownloadsState load() {
        return load("", 0, 0);
    }

    DownloadsState load(String query, int requestedPage, int focusId) {
        return repository.page(query, requestedPage, focusId);
    }

    boolean relink(int jobId, int photoIndex, Uri selected) {
        return repository.relink(jobId, photoIndex, selected);
    }
}
