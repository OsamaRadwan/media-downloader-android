package com.codex.mediadownloader;

import java.net.CookieManager;
import java.net.HttpURLConnection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Keeps an anonymous visitor cookie jar scoped to one resolution job. */
final class MediaRequestCookies {
    private MediaRequestCookies() { }

    static void attach(HttpURLConnection connection, CookieManager cookies, boolean visitorCookies) throws Exception {
        if (!visitorCookies) return;
        Map<String, List<String>> stored = cookies.get(connection.getURL().toURI(), Collections.emptyMap());
        List<String> values = stored.get("Cookie");
        if (values != null && !values.isEmpty())
            connection.setRequestProperty("Cookie", String.join("; ", values));
    }

    static void capture(HttpURLConnection connection, CookieManager cookies) throws Exception {
        cookies.put(connection.getURL().toURI(), connection.getHeaderFields());
    }

    static boolean retryWithVisitorCookies(Exception error) {
        String message = error.getMessage();
        return message != null && (message.contains("HTTP 401") || message.contains("HTTP 403") ||
            message.contains("page instead of MP4"));
    }
}
