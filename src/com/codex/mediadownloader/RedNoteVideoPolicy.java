package com.codex.mediadownloader;

import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Chooses RedNote source assets without accepting known stamped playback variants. */
final class RedNoteVideoPolicy {
    private static final Pattern STREAM_PATH = Pattern.compile("/stream/(?:[0-9]+/)*([0-9]+)/");

    private RedNoteVideoPolicy() { }

    static String originalUrl(String key) {
        if (key == null || !key.matches("[A-Za-z0-9_/-]{10,240}") ||
            key.startsWith("/") || key.endsWith("/") || key.contains("//") || key.contains("..")) return "";
        return "https://sns-video-bd.xhscdn.com/" + key;
    }

    static boolean cleanStream(int declaredType, String url) {
        if (declaredType == 259 || declaredType == 309) return false;
        int pathType = -1;
        try {
            Matcher match = STREAM_PATH.matcher(new URI(url).getPath());
            if (match.find()) pathType = Integer.parseInt(match.group(1));
        } catch (Exception ignored) { return false; }
        if (pathType == 259 || pathType == 309) return false;
        if (pathType != -1 && declaredType != -1 && pathType != declaredType) return false;
        return declaredType == 258 || declaredType == 301 || pathType == 258 || pathType == 301;
    }

    static boolean cleanScreencast(String url) {
        return cleanStream(-1, url);
    }
}
