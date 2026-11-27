package com.codex.mediadownloader;

import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Caps the entire request, including TLS handshakes that outlive read timeouts. */
final class HttpDeadline implements AutoCloseable {
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "media-http-deadline");
        thread.setDaemon(true);
        return thread;
    });

    private final AtomicBoolean expired = new AtomicBoolean();
    private final ScheduledFuture<?> timer;

    HttpDeadline(HttpURLConnection connection, int seconds) {
        timer = TIMER.schedule(() -> {
            expired.set(true);
            connection.disconnect();
        }, seconds, TimeUnit.SECONDS);
    }

    Exception translate(Exception error) {
        if (!expired.get()) return error;
        SocketTimeoutException timeout = new SocketTimeoutException("Request exceeded its time limit");
        timeout.initCause(error);
        return timeout;
    }

    @Override public void close() { timer.cancel(false); }
}
