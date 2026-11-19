package com.codex.mediadownloader;

import java.util.List;

/** Application-facing read contract for download history. */
interface DownloadRepository {
    List<DownloadItem> list();
    void importExistingAsync();
}
