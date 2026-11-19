package com.codex.mediadownloader;

import java.io.OutputStream;

interface DiagnosticsRepository {
    int eventCount();
    void exportTo(OutputStream output) throws Exception;
}
