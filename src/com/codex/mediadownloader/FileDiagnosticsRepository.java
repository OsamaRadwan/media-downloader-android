package com.codex.mediadownloader;

import android.content.Context;
import java.io.OutputStream;

final class FileDiagnosticsRepository implements DiagnosticsRepository {
    private final Context app;

    FileDiagnosticsRepository(Context context) { app = context.getApplicationContext(); }

    @Override public int eventCount() { return DiagnosticsStore.eventCount(app); }

    @Override public void exportTo(OutputStream output) throws Exception { DiagnosticsStore.export(app, output); }
}
