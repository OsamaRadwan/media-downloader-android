package com.codex.mediadownloader;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class HttpDeadlineTest {
    public static void main(String[] args) throws Exception {
        FakeConnection stalled = new FakeConnection();
        HttpDeadline deadline = new HttpDeadline(stalled, 1);
        check(stalled.disconnected.await(2, TimeUnit.SECONDS), "Stalled connection was not closed");
        check(deadline.translate(new IOException("closed")) instanceof SocketTimeoutException,
            "Expired request was not identified as a timeout");
        deadline.close();

        FakeConnection completed = new FakeConnection();
        HttpDeadline canceled = new HttpDeadline(completed, 1);
        canceled.close();
        check(!completed.disconnected.await(1200, TimeUnit.MILLISECONDS),
            "Completed request was disconnected later");
    }

    private static void check(boolean valid, String message) {
        if (!valid) throw new AssertionError(message);
    }

    private static final class FakeConnection extends HttpURLConnection {
        final CountDownLatch disconnected = new CountDownLatch(1);
        FakeConnection() throws Exception { super(new URL("https://example.com/")); }
        @Override public void disconnect() { disconnected.countDown(); }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() { }
    }
}
