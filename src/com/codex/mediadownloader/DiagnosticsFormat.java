package com.codex.mediadownloader;

import java.util.regex.Pattern;
import java.net.URI;

/** Formats failure details without copying media URLs, local paths, or account identifiers. */
final class DiagnosticsFormat {
    private static final Pattern URL = Pattern.compile("(?i)(?:https?|content|file)://\\S+");
    private static final Pattern PATH = Pattern.compile("(?i)(?:/storage/|/data/|/sdcard/|[a-z]:\\\\)[^\\s]+(?:[/\\\\][^\\s]+)*");
    private static final Pattern SECRET = Pattern.compile("(?i)\\b(cookie|authorization|token|signature|password|secret)\\s*[:=]\\s*[^\\s,;]+");
    private static final Pattern LONG_ID = Pattern.compile("\\b\\d{12,}\\b");

    private DiagnosticsFormat() { }

    static String clean(String value) {
        if (value == null) return "";
        String text = value.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
        text = URL.matcher(text).replaceAll("[url]");
        text = PATH.matcher(text).replaceAll("[path]");
        text = SECRET.matcher(text).replaceAll("$1=[redacted]");
        text = LONG_ID.matcher(text).replaceAll("[id]");
        text = text.replaceAll(" +", " ").trim();
        return text.length() > 320 ? text.substring(0, 320) + "…" : text;
    }

    static String describe(Throwable error) {
        if (error == null) return "Unknown failure";
        StringBuilder result = new StringBuilder();
        Throwable current = error;
        for (int depth = 0; current != null && depth < 4; depth++) {
            if (depth > 0) result.append(" | caused by ");
            result.append(current.getClass().getName());
            String message = clean(current.getMessage());
            if (!message.isEmpty()) result.append(": ").append(message);
            int frames = 0;
            for (StackTraceElement frame : current.getStackTrace()) {
                if (!frame.getClassName().startsWith("com.codex.mediadownloader.")) continue;
                result.append(" | at ").append(frame.getClassName()).append('.').append(frame.getMethodName())
                    .append(':').append(frame.getLineNumber());
                if (++frames == 8) break;
            }
            if (current.getCause() == current) break;
            current = current.getCause();
        }
        return result.length() > 2048 ? result.substring(0, 2048) + "…" : result.toString();
    }

    /** Only a public TikTok post or short-link path; query and fragment are never exported. */
    static String publicPostUrl(String value) {
        try {
            URI uri = new URI(value);
            String host = uri.getHost();
            String path = uri.getRawPath();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null ||
                !(host.equalsIgnoreCase("tiktok.com") || host.toLowerCase().endsWith(".tiktok.com")) ||
                uri.getPort() != -1 || path == null || !path.matches("/[A-Za-z0-9@._/-]{1,220}"))
                return "unavailable";
            return "https://" + host.toLowerCase() + path;
        } catch (Exception error) { return "unavailable"; }
    }
}
