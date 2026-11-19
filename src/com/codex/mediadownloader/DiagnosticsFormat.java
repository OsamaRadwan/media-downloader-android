package com.codex.mediadownloader;

import java.util.regex.Pattern;

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
        StringBuilder result = new StringBuilder(error.getClass().getName());
        String message = clean(error.getMessage());
        if (!message.isEmpty()) result.append(": ").append(message);
        int frames = 0;
        for (StackTraceElement frame : error.getStackTrace()) {
            if (!frame.getClassName().startsWith("com.codex.mediadownloader.")) continue;
            result.append(" | ").append(frame.getClassName()).append('.').append(frame.getMethodName())
                .append(':').append(frame.getLineNumber());
            if (++frames == 3) break;
        }
        Throwable cause = error.getCause();
        if (cause != null && cause != error) {
            result.append(" | cause=").append(cause.getClass().getName());
            String causeMessage = clean(cause.getMessage());
            if (!causeMessage.isEmpty()) result.append(": ").append(causeMessage);
        }
        return result.toString();
    }
}
