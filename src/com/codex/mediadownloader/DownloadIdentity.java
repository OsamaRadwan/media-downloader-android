package com.codex.mediadownloader;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Stable post keys; tracking parameters and profile-name changes do not make a new post. */
final class DownloadIdentity {
    private static final Pattern TIKTOK = Pattern.compile("^/@[^/]+/(?:video|photo)/([0-9]{15,22})/?$");
    private static final Pattern INSTAGRAM = Pattern.compile("^/(?:p|reel|tv)/([A-Za-z0-9_-]{5,20})/?$");
    private static final Pattern INSTAGRAM_STORY = Pattern.compile("^/stories/[^/]+/([0-9]{15,22})/?$");
    private static final Pattern REDNOTE = Pattern.compile("^/(?:discovery/item|explore)/([a-fA-F0-9]{24})/?$");

    private DownloadIdentity() { }

    static String fromUrl(String value) {
        if (value == null || value.isEmpty()) return "";
        try {
            URI uri = new URI(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null ||
                uri.getPort() != -1 || uri.getHost() == null || uri.getPath() == null) return "";
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            String path = uri.getPath();
            Matcher match;
            if (host.equals("tiktok.com") || host.endsWith(".tiktok.com")) {
                match = TIKTOK.matcher(path);
                if (match.matches()) return "tiktok:id:" + match.group(1);
                return "tiktok:link:" + host + path.replaceAll("/$", "");
            }
            if (host.equals("instagram.com") || host.equals("www.instagram.com")) {
                match = INSTAGRAM.matcher(path);
                if (match.matches()) return "instagram:post:" + match.group(1);
                match = INSTAGRAM_STORY.matcher(path);
                if (match.matches()) return "instagram:story:" + match.group(1);
            }
            if (host.equals("rednote.com") || host.equals("www.rednote.com") ||
                host.equals("xiaohongshu.com") || host.equals("www.xiaohongshu.com")) {
                match = REDNOTE.matcher(path);
                if (match.matches()) return "rednote:id:" + match.group(1).toLowerCase(Locale.ROOT);
            }
            if (host.equals("xhslink.cn") || host.equals("www.xhslink.cn"))
                return "rednote:link:" + path.replaceAll("/$", "");
        } catch (Exception ignored) { }
        return "";
    }

    static String fromResolved(String platform, String id) {
        if (platform == null || id == null || id.isEmpty()) return "";
        if ("tiktok".equals(platform) && id.matches("[0-9]{15,22}")) return "tiktok:id:" + id;
        if ("instagram".equals(platform) && id.matches("[0-9]{1,24}")) return "instagram:id:" + id;
        if ("rednote".equals(platform) && id.matches("[a-fA-F0-9]{24}"))
            return "rednote:id:" + id.toLowerCase(Locale.ROOT);
        return "";
    }

    static String fromEntry(String sourceUrl, String shareUrl, String videoId) {
        String platform = platform(sourceUrl);
        if (platform.isEmpty()) platform = platform(shareUrl);
        if (platform.isEmpty() && videoId != null && videoId.matches("[0-9]{15,22}")) platform = "tiktok";
        return fromResolved(platform, videoId);
    }

    private static String platform(String url) {
        String key = fromUrl(url);
        int separator = key.indexOf(':');
        return separator < 0 ? "" : key.substring(0, separator);
    }

    static boolean matches(String requestedUrlKey, String resolvedKey,
                           String sourceUrl, String shareUrl, String videoId) {
        String entryResolved = fromEntry(sourceUrl, shareUrl, videoId);
        if (!resolvedKey.isEmpty() && resolvedKey.equals(entryResolved)) return true;
        if (requestedUrlKey.isEmpty()) return false;
        if (requestedUrlKey.equals(entryResolved)) return true;
        return requestedUrlKey.equals(fromUrl(sourceUrl)) || requestedUrlKey.equals(fromUrl(shareUrl));
    }
}
