package com.codex.mediadownloader;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;

public final class MediaDownloaderApplication extends Application {
    private Handler diagnosticsHandler;

    @Override public void onCreate() {
        super.onCreate();
        NetworkRouteStore.init(this);
        DownloadStore.interruptStale(this);
        DiagnosticsStore.prune(this);
        DiagnosticsStore.trace(this, "app.start", "process started");
        diagnosticsHandler = new Handler(Looper.getMainLooper());
        scheduleDailyCleanup();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            DiagnosticsStore.record(this, "app.crash thread=" + thread.getName(), error);
            if (previous != null) previous.uncaughtException(thread, error);
            else android.os.Process.killProcess(android.os.Process.myPid());
        });
    }

    private void scheduleDailyCleanup() {
        long now = System.currentTimeMillis();
        long delay = Math.max(1_000, DiagnosticsRetention.nextCleanupMillis(now) - now);
        diagnosticsHandler.postDelayed(() -> new Thread(() -> {
            DiagnosticsStore.prune(this);
            diagnosticsHandler.post(this::scheduleDailyCleanup);
        }, "diagnostics-daily-cleanup").start(), delay);
    }
}
