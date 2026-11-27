package com.codex.mediadownloader;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Selects one supported post link from text sent through Android's Share menu. */
final class SharedPostLink {
    private static final Pattern URL = Pattern.compile("https://[^\\s<>\"']+", Pattern.CASE_INSENSITIVE);
    private static final int MAX_TEXT_LENGTH = 16384;

    private SharedPostLink() { }

    static String fromText(String text) {
        if (text == null || text.length() > MAX_TEXT_LENGTH) return null;
        Matcher links = URL.matcher(text);
        String selected = null;
        while (links.find()) {
            String candidate = links.group().replaceAll("[),.;!?]+$", "");
            if (!supported(candidate)) continue;
            if (selected != null && !selected.equals(candidate)) return null;
            selected = candidate;
        }
        return selected;
    }

    private static boolean supported(String value) {
        try {
            URI uri = new URI(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null ||
                uri.getPort() != -1 || uri.getHost() == null || uri.getPath() == null ||
                uri.getPath().length() < 2) return false;
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            return host.equals("tiktok.com") || host.endsWith(".tiktok.com") ||
                host.equals("instagram.com") || host.equals("www.instagram.com") ||
                host.equals("xhslink.cn") || host.equals("www.xhslink.cn") ||
                host.equals("www.rednote.com") || host.equals("www.xiaohongshu.com");
        } catch (Exception ignored) { return false; }
    }
}
