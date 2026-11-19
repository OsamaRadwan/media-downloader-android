package com.codex.mediadownloader;

import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class MediaRequestCookiesTest {
    public static void main(String[] args) throws Exception {
        CookieManager visitor = new CookieManager();
        HttpCookie cookie = new HttpCookie("ttwid", "anonymous-visitor");
        cookie.setPath("/");
        visitor.getCookieStore().add(new URI("https://cdn.example/"), cookie);

        StubConnection publicRequest = new StubConnection();
        MediaRequestCookies.attach(publicRequest, visitor, false);
        check(publicRequest.getRequestProperty("Cookie") == null,
            "Public media requests must omit the visitor cookie");

        StubConnection visitorRequest = new StubConnection();
        MediaRequestCookies.attach(visitorRequest, visitor, true);
        String visitorHeader = visitorRequest.getRequestProperty("Cookie");
        check(visitorHeader != null && visitorHeader.contains("ttwid") && visitorHeader.contains("anonymous-visitor"),
            "An access fallback may use its own anonymous visitor cookie: " + visitorHeader);

        check(MediaRequestCookies.retryWithVisitorCookies(new Exception("HTTP 403")),
            "Access denial may try the visitor fallback");
        check(!MediaRequestCookies.retryWithVisitorCookies(new Exception("HTTP 429")),
            "Rate limits must not trigger more requests");
        check(!MediaRequestCookies.retryWithVisitorCookies(new Exception("HTTP 404")),
            "Missing media must not trigger a visitor retry");

        CookieManager firstJob = new CookieManager();
        CookieManager secondJob = new CookieManager();
        MediaRequestCookies.capture(new StubConnection(true), firstJob);
        StubConnection firstJobRequest = new StubConnection();
        StubConnection secondJobRequest = new StubConnection();
        MediaRequestCookies.attach(firstJobRequest, firstJob, true);
        MediaRequestCookies.attach(secondJobRequest, secondJob, true);
        check(firstJobRequest.getRequestProperty("Cookie") != null,
            "Page cookies must be available to the same resolution job");
        check(secondJobRequest.getRequestProperty("Cookie") == null,
            "Another download job must not inherit page cookies");
        System.out.println("MediaRequestCookiesTest passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class StubConnection extends HttpURLConnection {
        private final boolean setsCookie;
        StubConnection() throws Exception { this(false); }
        StubConnection(boolean setsCookie) throws Exception {
            super(new URL("https://cdn.example/media.mp4"));
            this.setsCookie = setsCookie;
        }
        @Override public Map<String, List<String>> getHeaderFields() {
            return setsCookie ? Collections.singletonMap("Set-Cookie", Arrays.asList("visitor=job-one; Path=/")) :
                Collections.emptyMap();
        }
        @Override public void disconnect() { }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() { }
    }
}
