package com.codex.mediadownloader;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** Reads media listed by a public RedNote share page, without the installed app's session. */
final class RedNoteResolver {
    private static final String MOBILE_AGENT = "Mozilla/5.0 (Linux; Android 15; Pixel 9 Pro) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";
    private static final Pattern NOTE_PATH = Pattern.compile("^/(?:discovery/item|explore)/([a-fA-F0-9]{24})/?$");
    private static final int MAX_PAGE_BYTES = 4_000_000;

    private RedNoteResolver() { }

    static boolean accepts(String link) {
        try {
            URI uri = new URI(link);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getPort() != -1) return false;
            host = host.toLowerCase(java.util.Locale.ROOT);
            if ("xhslink.cn".equals(host) || "www.xhslink.cn".equals(host))
                return uri.getPath().matches("^/o/[A-Za-z0-9]{6,32}$");
            return ("www.rednote.com".equals(host) || "www.xiaohongshu.com".equals(host)) &&
                NOTE_PATH.matcher(uri.getPath()).matches();
        } catch (Exception ignored) { return false; }
    }

    static StreamResolver.Result resolve(String link, StreamResolver.Trace trace) throws Exception {
        if (!accepts(link)) throw new Exception("Invalid RedNote post link");
        String html = fetch(link, trace);
        return parse(html, link, trace);
    }

    static StreamResolver.Result parse(String html, String link, StreamResolver.Trace trace) throws Exception {
        String marker = "window.__INITIAL_STATE__=";
        int start = html.indexOf(marker);
        if (start < 0) throw new Exception("RedNote did not expose public post data");
        start += marker.length();
        int end = html.indexOf("</script>", start);
        if (end < 0 || end - start > MAX_PAGE_BYTES) throw new Exception("RedNote post data is incomplete");
        String raw = html.substring(start, end).replaceAll(":undefined(?=[,}])", ":null").trim();
        if (raw.endsWith(";")) raw = raw.substring(0, raw.length() - 1);
        JSONObject state = new JSONObject(raw);
        JSONObject noteData = state.optJSONObject("noteData");
        JSONObject data = noteData == null ? null : noteData.optJSONObject("data");
        JSONObject note = data == null ? null : data.optJSONObject("noteData");
        if (note == null) throw new Exception("RedNote post details are unavailable publicly");
        String id = note.optString("noteId", "");
        if (!id.matches("[a-fA-F0-9]{24}")) throw new Exception("RedNote post ID is missing");
        Matcher requested = NOTE_PATH.matcher(new URI(link).getPath());
        if (requested.matches() && !id.equalsIgnoreCase(requested.group(1)))
            throw new Exception("RedNote returned a different post");
        JSONObject author = note.optJSONObject("user");
        String account = author == null ? "" : author.optString("userId", "");
        if (!account.matches("[a-fA-F0-9]{24}")) throw new Exception("RedNote creator ID is missing");
        String profile = author.optString("nickName", "").replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (profile.isEmpty()) profile = account;
        if (profile.length() > 60) profile = profile.substring(0, 60);
        long publishedMs = note.optLong("time", 0);
        if (publishedMs < 1262304000000L || publishedMs > System.currentTimeMillis() + 86400000L)
            throw new Exception("RedNote publish time is invalid");
        List<String> tags = new ArrayList<>();
        JSONArray tagList = note.optJSONArray("tagList");
        if (tagList != null) for (int i = 0; i < tagList.length(); i++) {
            JSONObject tag = tagList.optJSONObject(i);
            if (tag != null && "topic".equals(tag.optString("type"))) tags.add(tag.optString("name", ""));
        }
        PostText postText = PostText.from(note.optString("title", ""), note.optString("desc", ""), tags);
        String canonical = "https://www.rednote.com/discovery/item/" + id;
        JSONArray images = note.optJSONArray("imageList");
        String cover = "";
        if (images != null && images.length() > 0) {
            JSONObject first = images.optJSONObject(0);
            if (first != null) cover = secure(first.optString("url", ""));
        }
        List<StreamResolver.Candidate> videos = new ArrayList<>();
        JSONObject video = note.optJSONObject("video");
        if ("video".equals(note.optString("type")) || video != null) {
            JSONObject media = video == null ? null : video.optJSONObject("media");
            JSONObject streams = media == null ? null : media.optJSONObject("stream");
            if (streams != null) for (String codec : new String[]{"h264", "h265", "h266", "av1"}) {
                JSONArray versions = streams.optJSONArray(codec);
                if (versions == null) continue;
                for (int i = 0; i < versions.length() && i < 32; i++) {
                    JSONObject version = versions.optJSONObject(i);
                    if (version == null) continue;
                    String url = secure(version.optString("masterUrl", ""));
                    if (!isMedia(url)) continue;
                    videos.add(new StreamResolver.Candidate(url, version.optInt("width", 0),
                        version.optInt("height", 0), version.optInt("videoBitrate", 0), codec + "-" + i));
                }
            }
            if (videos.isEmpty()) throw new Exception("RedNote did not list a public video stream");
            Collections.sort(videos, (a, b) -> {
                int pixels = Long.compare((long) b.width * b.height, (long) a.width * a.height);
                if (pixels != 0) return pixels;
                int bitrate = Integer.compare(b.bitrate, a.bitrate);
                if (bitrate != 0) return bitrate;
                return Boolean.compare(!a.variant.startsWith("h264"), !b.variant.startsWith("h264"));
            });
        }
        List<StreamResolver.PhotoCandidate> photos = new ArrayList<>();
        if (videos.isEmpty()) {
            if (images == null || images.length() == 0 || images.length() > 100)
                throw new Exception("RedNote did not list this post's photos");
            for (int i = 0; i < images.length(); i++) {
                JSONObject image = images.optJSONObject(i);
                if (image == null) throw new Exception("RedNote album image is missing");
                Set<String> urls = new LinkedHashSet<>();
                String fileId = image.optString("fileId", "");
                if (fileId.matches("[A-Za-z0-9]{20,80}"))
                    urls.add("https://sns-img-hw.xhscdn.com/" + fileId);
                String main = secure(image.optString("url", ""));
                if (isMedia(main)) urls.add(main);
                JSONArray info = image.optJSONArray("infoList");
                if (info != null) for (int j = 0; j < info.length() && j < 32; j++) {
                    JSONObject rendition = info.optJSONObject(j);
                    String url = rendition == null ? "" : secure(rendition.optString("url", ""));
                    if (isMedia(url)) urls.add(url);
                }
                if (urls.isEmpty()) throw new Exception("RedNote album photo " + (i + 1) + " has no public URL");
                photos.add(new StreamResolver.PhotoCandidate(i + 1, image.optInt("width", 0),
                    image.optInt("height", 0), new ArrayList<>(urls)));
            }
            cover = photos.get(0).urls.get(0);
        }
        if (trace != null) trace.event("rednote.resolved", "kind=" + (photos.isEmpty() ? "video" : "photos") +
            " streams=" + videos.size() + " photos=" + photos.size());
        return new StreamResolver.Result(profile, account, id, cover, canonical, postText,
            publishedMs / 1000L, videos, photos,
            new CookieManager(null, CookiePolicy.ACCEPT_NONE), "rednote");
    }

    private static String fetch(String link, StreamResolver.Trace trace) throws Exception {
        URL current = new URL(link);
        for (int redirect = 0; redirect < 5; redirect++) {
            if (!accepts(current.toString())) throw new Exception("RedNote redirected to an unexpected host");
            HttpURLConnection connection = (HttpURLConnection) current.openConnection();
            try {
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(20000);
                connection.setRequestProperty("User-Agent", MOBILE_AGENT);
                connection.setRequestProperty("Accept", "text/html,application/xhtml+xml");
                int status = connection.getResponseCode();
                if (trace != null) trace.event("rednote.page", "host=" + current.getHost() + " http=" + status +
                    " bytes=" + connection.getContentLengthLong());
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new Exception("RedNote redirect has no location");
                    current = new URL(current, location);
                    continue;
                }
                if (status != 200) throw new Exception("RedNote public post returned HTTP " + status);
                try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[32768];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (output.size() + count > MAX_PAGE_BYTES) throw new Exception("RedNote post page is too large");
                        output.write(buffer, 0, count);
                    }
                    return output.toString(StandardCharsets.UTF_8.name());
                }
            } finally { connection.disconnect(); }
        }
        throw new Exception("RedNote redirected too many times");
    }

    private static String secure(String url) {
        return url.startsWith("http://") ? "https://" + url.substring(7) : url;
    }

    private static boolean isMedia(String url) {
        try {
            URI uri = new URI(url);
            String host = uri.getHost();
            return "https".equalsIgnoreCase(uri.getScheme()) && host != null && uri.getPort() == -1 &&
                (host.endsWith(".rednotecdn.com") || host.endsWith(".xhscdn.com"));
        } catch (Exception ignored) { return false; }
    }
}
