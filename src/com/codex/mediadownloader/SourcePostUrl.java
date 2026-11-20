package com.codex.mediadownloader;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** A public post permalink suitable for saved records and file metadata. */
final class SourcePostUrl {
    private SourcePostUrl() { }

    static String forMetadata(String value) throws IOException {
        if (value == null) throw new IOException("Invalid source post URL");
        try {
            URI uri = new URI(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null ||
                uri.getPort() != -1 || uri.getHost() == null || uri.getRawPath() == null)
                throw new IOException("Invalid source post URL");
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            String path = uri.getRawPath();
            if ((host.equals("tiktok.com") || host.equals("www.tiktok.com")) &&
                path.matches("/@[A-Za-z0-9._-]+/(video|photo)/[0-9]{15,22}/?"))
                return "https://www.tiktok.com" + withoutTrailingSlash(path);
            if (host.equals("instagram.com") || host.equals("www.instagram.com")) {
                if (path.matches("/(p|reel|tv)/[A-Za-z0-9_-]{5,20}/?"))
                    return "https://www.instagram.com" + withTrailingSlash(path);
                if (path.matches("/stories/[A-Za-z0-9._]{1,40}/[0-9]{15,22}/?"))
                    return "https://www.instagram.com" + withTrailingSlash(path);
            }
            if ((host.equals("rednote.com") || host.equals("www.rednote.com") ||
                 host.equals("xiaohongshu.com") || host.equals("www.xiaohongshu.com")) &&
                path.matches("/(discovery/item|explore)/[a-fA-F0-9]{24}/?")) {
                String cleanPath = withoutTrailingSlash(path);
                String id = cleanPath.substring(cleanPath.lastIndexOf('/') + 1);
                return "https://www.rednote.com/discovery/item/" + id.toLowerCase(Locale.ROOT);
            }
        } catch (URISyntaxException | IllegalArgumentException error) {
            // Never include the rejected URL in an exception or diagnostic record.
        }
        throw new IOException("Invalid source post URL");
    }

    private static String withoutTrailingSlash(String path) {
        return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    private static String withTrailingSlash(String path) {
        return path.endsWith("/") ? path : path + "/";
    }
}
