package com.codex.mediadownloader;

import android.app.Application;

public final class MediaDownloaderApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            DiagnosticsStore.record(this, "app.crash thread=" + thread.getName(), error);
            if (previous != null) previous.uncaughtException(thread, error);
            else android.os.Process.killProcess(android.os.Process.myPid());
        });
    }
}
