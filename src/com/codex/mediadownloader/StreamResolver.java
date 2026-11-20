package com.codex.mediadownloader;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;

public final class StreamResolver {
    interface Trace { void event(String component, String detail); }
    static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36";
    private static final String MOBILE_USER_AGENT = "Mozilla/5.0 (Linux; Android 15; Pixel 9 Pro) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";
    private static final BrowserProfile DESKTOP = new BrowserProfile(USER_AGENT, false);
    private static final BrowserProfile WIDE_DESKTOP = new BrowserProfile(USER_AGENT, true);
    private static final BrowserProfile MOBILE = new BrowserProfile(MOBILE_USER_AGENT, false);
    private static final Pattern VIDEO_ID = Pattern.compile("/video/(\\d{15,22})");
    private static final Pattern PHOTO_ID = Pattern.compile("/photo/(\\d{15,22})");
    private static final Pattern UNIVERSAL = Pattern.compile("<script[^>]*id=\"__UNIVERSAL_DATA_FOR_REHYDRATION__\"[^>]*>(.*?)</script>", Pattern.DOTALL);

    private StreamResolver() { }

    private static final class BrowserProfile {
        final String userAgent;
        final boolean wide;
        BrowserProfile(String userAgent, boolean wide) {
            this.userAgent = userAgent; this.wide = wide;
        }
    }

    public static final class Result {
        final String profileName;
        final String accountId;
        final String videoId;
        final String coverUrl;
        final String pageUrl;
        final PostText postText;
        final CookieManager cookies;
        final long publishTimeSeconds;
        final List<Candidate> candidates;
        final List<PhotoCandidate> photos;
        final String platform;
        Result(String profileName, String accountId, String videoId, String coverUrl, String pageUrl,
               PostText postText, long publishTimeSeconds, List<Candidate> candidates,
               List<PhotoCandidate> photos, CookieManager cookies) {
            this(profileName, accountId, videoId, coverUrl, pageUrl, postText,
                publishTimeSeconds, candidates, photos, cookies, "tiktok");
        }
        Result(String profileName, String accountId, String videoId, String coverUrl, String pageUrl,
               PostText postText, long publishTimeSeconds, List<Candidate> candidates,
               List<PhotoCandidate> photos, CookieManager cookies, String platform) {
            this.profileName = profileName;
            this.accountId = accountId;
            this.videoId = videoId;
            this.coverUrl = coverUrl;
            this.pageUrl = pageUrl;
            this.postText = postText;
            this.cookies = cookies;
            this.publishTimeSeconds = publishTimeSeconds;
            this.candidates = candidates;
            this.photos = photos;
            this.platform = platform;
        }

        boolean isAlbum() { return !photos.isEmpty(); }
    }

    static final class PhotoCandidate {
        final int index, width, height;
        final List<String> urls;
        PhotoCandidate(int index, int width, int height, List<String> urls) {
            this.index = index; this.width = width; this.height = height; this.urls = urls;
        }
    }

    static final class Candidate {
        final String url;
        final int width;
        final int height;
        final int bitrate;
        final String variant;
        Candidate(String url, int width, int height, int bitrate, String variant) {
            this.url = url; this.width = width; this.height = height; this.bitrate = bitrate; this.variant = variant;
        }
    }

    public static Result resolve(String shareUrl, Trace trace) throws Exception {
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        String videoId = idFromUrl(shareUrl);
        boolean photoLink = false;
        if (videoId == null) {
            StringBuilder redirected = new StringBuilder();
            String sharedHtml = fetchText(shareUrl, redirected, DESKTOP, cookies, trace);
            Matcher photo = PHOTO_ID.matcher(redirected.toString());
            if (photo.find()) {
                videoId = photo.group(1);
                photoLink = true;
            } else {
                JSONObject shared = itemFromPage(sharedHtml, "webapp.reflow.video.detail");
                videoId = shared.optString("id", "");
                if (videoId.isEmpty()) throw new Exception("Could not identify the shared post");
            }
        }
        String pageUrl = "https://www.tiktok.com/@_/video/" + videoId;
        // TikTok sometimes omits its visitor cookie from a direct video-page response.
        // Establish the same anonymous web session before requesting signed media URLs.
        try { openSession(cookies, trace); } catch (Exception ignored) { /* The video page may still work. */ }
        JSONObject item = itemFromPage(fetchText(pageUrl, null, DESKTOP, cookies, trace), "webapp.video-detail");
        if (!videoId.equals(item.optString("id", ""))) throw new Exception("TikTok returned a different video");
        JSONObject author = item.optJSONObject("author");
        String accountId = author == null ? "" : author.optString("id", "");
        if (accountId.isEmpty()) accountId = item.optString("authorId", "");
        if (!accountId.matches("\\d{6,24}")) throw new Exception("TikTok did not provide the creator account ID");
        String profileName = author == null ? "" : author.optString("uniqueId", "");
        profileName = profileName.replaceAll("[^A-Za-z0-9._-]", "_");
        if (profileName.replace("_", "").isEmpty()) profileName = accountId;
        long publishTimeSeconds = item.optLong("createTime", 0);
        if (publishTimeSeconds < 1000000000L || publishTimeSeconds > System.currentTimeMillis() / 1000L + 86400L)
            throw new Exception("TikTok did not provide a valid publish time");
        List<String> listedHashtags = new ArrayList<>();
        JSONArray textExtra = item.optJSONArray("textExtra");
        if (textExtra != null) for (int i = 0; i < textExtra.length(); i++) {
            JSONObject extra = textExtra.optJSONObject(i);
            if (extra != null) listedHashtags.add(extra.optString("hashtagName", ""));
        }
        PostText postText = PostText.from(item.optString("title", ""), item.optString("desc", ""), listedHashtags);
        JSONObject imagePost = item.optJSONObject("imagePost");
        JSONArray images = imagePost == null ? null : imagePost.optJSONArray("images");
        if (images != null && images.length() > 0) {
            if (images.length() > 100) throw new Exception("Photo album has too many images");
            List<PhotoCandidate> photos = photoCandidates(images);
            int addedVariants = 0;
            for (BrowserProfile profile : new BrowserProfile[]{WIDE_DESKTOP, MOBILE}) {
                try {
                    JSONObject alternative = itemFromPage(fetchText(pageUrl, null, profile, cookies, trace), "webapp.video-detail");
                    if (!videoId.equals(alternative.optString("id", ""))) continue;
                    JSONObject alternatePost = alternative.optJSONObject("imagePost");
                    JSONArray alternateImages = alternatePost == null ? null : alternatePost.optJSONArray("images");
                    if (alternateImages == null || alternateImages.length() != photos.size()) continue;
                    addedVariants += mergePhotoCandidates(photos, photoCandidates(alternateImages));
                } catch (Exception error) {
                    trace(trace, "resolver.alternative", "profile=" + profileName(profile) +
                        " error=" + DiagnosticsFormat.describe(error));
                    Log.w("MediaDownloader", "Additional photo browser profile unavailable: " + error.getMessage());
                }
            }
            Log.i("MediaDownloader", "Album " + videoId + ": " + photos.size() +
                " photos, " + addedVariants + " additional renditions");
            String albumPage = "https://www.tiktok.com/@" + profileName + "/photo/" + videoId;
            String albumCover = "";
            for (PhotoCandidate photo : photos) if (!photo.urls.isEmpty()) {
                albumCover = photo.urls.get(0);
                break;
            }
            return new Result(profileName, accountId, videoId, albumCover, albumPage,
                postText, publishTimeSeconds, Collections.emptyList(), photos, cookies);
        }
        if (photoLink) throw new Exception("TikTok did not provide the album images");
        JSONObject video = item.optJSONObject("video");
        if (video == null) throw new Exception("TikTok did not provide video streams");
        String coverUrl = video.optString("cover", "");
        if (!coverUrl.startsWith("https://")) coverUrl = video.optString("originCover", "");
        if (!coverUrl.startsWith("https://")) coverUrl = video.optString("dynamicCover", "");
        List<Candidate> candidates = new ArrayList<>();
        JSONArray bitrates = video.optJSONArray("bitrateInfo");
        if (bitrates != null) for (int i = 0; i < bitrates.length(); i++) {
            JSONObject bitrate = bitrates.optJSONObject(i);
            if (bitrate == null) continue;
            JSONObject play = bitrate.optJSONObject("PlayAddr");
            if (play == null || play.optString("UrlKey").contains("bytevc2")) continue;
            int width = play.optInt("Width", 0);
            int height = play.optInt("Height", 0);
            int rate = bitrate.optInt("Bitrate", 0);
            String variant = play.optString("UrlKey", "");
            if (variant.isEmpty()) variant = "bitrate-" + i;
            JSONArray urls = play.optJSONArray("UrlList");
            if (urls == null) continue;
            for (int j = 0; j < urls.length(); j++) {
                String url = urls.optString(j, "");
                if (isMediaUrl(url)) candidates.add(new Candidate(url, width, height, rate, variant));
            }
        }
        int defaultWidth = video.optInt("width", 0);
        int defaultHeight = video.optInt("height", 0);
        addPlayback(candidates, video.opt("playAddr"), defaultWidth, defaultHeight, "default");
        addPlayback(candidates, video.opt("playAddrH264"), defaultWidth, defaultHeight, "h264");
        addPlayback(candidates, video.opt("playAddrBytevc1"), defaultWidth, defaultHeight, "bytevc1");
        if (candidates.isEmpty()) throw new Exception("No downloadable playback stream was listed");
        Collections.sort(candidates, (a, b) -> {
            boolean wideA = a.width > a.height;
            boolean wideB = b.width > b.height;
            if (wideA != wideB) return wideA ? -1 : 1;
            long pixelsA = (long) a.width * a.height;
            long pixelsB = (long) b.width * b.height;
            int resolution = Long.compare(pixelsB, pixelsA);
            if (resolution != 0) return resolution;
            boolean defaultA = a.variant.equals("default");
            boolean defaultB = b.variant.equals("default");
            if (defaultA != defaultB) return defaultA ? -1 : 1;
            return Integer.compare(b.bitrate, a.bitrate);
        });
        return new Result(profileName, accountId, videoId, coverUrl, pageUrl, postText,
            publishTimeSeconds, candidates, Collections.emptyList(), cookies);
    }

    private static void addPlayback(List<Candidate> candidates, Object address, int width, int height, String variant) {
        if (address instanceof String) {
            String url = (String) address;
            if (isMediaUrl(url)) candidates.add(new Candidate(url, width, height, 0, variant));
        } else if (address instanceof JSONObject) {
            JSONObject object = (JSONObject) address;
            addPlayback(candidates, object.optString("src", ""), width, height, variant);
            addPlayback(candidates, object.optString("url", ""), width, height, variant);
            JSONArray urls = object.optJSONArray("UrlList");
            if (urls == null) urls = object.optJSONArray("urlList");
            if (urls != null) for (int i = 0; i < urls.length(); i++)
                addPlayback(candidates, urls.optString(i, ""), width, height, variant);
        }
    }

    private static boolean isMediaUrl(String url) {
        try {
            URL parsed = new URL(url);
            return "https".equals(parsed.getProtocol()) && !"www.tiktok.com".equalsIgnoreCase(parsed.getHost());
        } catch (Exception ignored) { return false; }
    }

    private static List<PhotoCandidate> photoCandidates(JSONArray images) {
        List<PhotoCandidate> photos = new ArrayList<>();
        for (int i = 0; i < images.length(); i++) {
            JSONObject image = images.optJSONObject(i);
            JSONObject address = image == null ? null : image.optJSONObject("imageURL");
            JSONArray urlList = address == null ? null : address.optJSONArray("urlList");
            List<String> urls = new ArrayList<>();
            if (urlList != null) for (int j = 0; j < urlList.length(); j++) {
                String url = urlList.optString(j, "");
                if (isMediaUrl(url) && !urls.contains(url)) urls.add(url);
            }
            photos.add(new PhotoCandidate(i + 1, image == null ? 0 : image.optInt("imageWidth", 0),
                image == null ? 0 : image.optInt("imageHeight", 0), urls));
        }
        return photos;
    }

    static int mergePhotoCandidates(List<PhotoCandidate> base, List<PhotoCandidate> alternate) {
        int added = 0;
        for (int i = 0; i < base.size(); i++) {
            PhotoCandidate current = base.get(i);
            PhotoCandidate other = alternate.get(i);
            if (other.urls.isEmpty()) continue;
            if (!current.urls.isEmpty() && !assetKey(current.urls.get(0)).equals(assetKey(other.urls.get(0))))
                continue;
            Set<String> existing = new HashSet<>();
            for (String url : current.urls) existing.add(renditionKey(url));
            Set<String> newRenditions = new HashSet<>();
            List<String> urls = new ArrayList<>(current.urls);
            for (String url : other.urls) {
                String key = renditionKey(url);
                if (!existing.contains(key) && !urls.contains(url)) {
                    urls.add(url);
                    newRenditions.add(key);
                }
            }
            added += newRenditions.size();
            boolean larger = (long) other.width * other.height > (long) current.width * current.height;
            base.set(i, new PhotoCandidate(current.index, larger ? other.width : current.width,
                larger ? other.height : current.height, urls));
        }
        return added;
    }

    private static String assetKey(String url) {
        try {
            String path = new URL(url).getPath();
            int template = path.indexOf('~');
            return template < 0 ? path : path.substring(0, template);
        } catch (Exception ignored) { return url; }
    }

    private static String renditionKey(String url) {
        try {
            URL parsed = new URL(url);
            StringBuilder key = new StringBuilder(parsed.getPath());
            String query = parsed.getQuery();
            if (query != null) for (String part : query.split("&")) {
                int equals = part.indexOf('=');
                String name = equals < 0 ? part : part.substring(0, equals);
                if (name.equals("x-signature") || name.equals("x-expires") ||
                    name.equals("t") || name.equals("idc")) continue;
                key.append('&').append(part);
            }
            return key.toString();
        } catch (Exception ignored) { return url; }
    }

    private static void openSession(CookieManager cookies, Trace trace) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL("https://www.tiktok.com/").openConnection();
        long started = System.nanoTime();
        try {
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            MediaRequestCookies.attach(connection, cookies, true);
            int status = connection.getResponseCode();
            MediaRequestCookies.capture(connection, cookies);
            trace(trace, "resolver.visitor_page", "host=" + connection.getURL().getHost() +
                " http=" + status + " elapsedMs=" + (System.nanoTime() - started) / 1_000_000 +
                " cookieCount=" + cookies.getCookieStore().getCookies().size());
        } catch (Exception error) {
            trace(trace, "resolver.visitor_page", "host=www.tiktok.com elapsedMs=" +
                (System.nanoTime() - started) / 1_000_000 + " error=" + DiagnosticsFormat.describe(error));
            throw error;
        } finally { connection.disconnect(); }
    }

    private static String idFromUrl(String url) {
        Matcher match = VIDEO_ID.matcher(url);
        return match.find() ? match.group(1) : null;
    }

    private static JSONObject itemFromPage(String html, String scopeKey) throws Exception {
        Matcher script = UNIVERSAL.matcher(html);
        if (!script.find()) throw new Exception("TikTok did not expose public video data");
        JSONObject scope = new JSONObject(script.group(1)).optJSONObject("__DEFAULT_SCOPE__");
        JSONObject detail = scope == null ? null : scope.optJSONObject(scopeKey);
        if (detail == null && scope != null) {
            detail = scope.optJSONObject(scopeKey.equals("webapp.video-detail") ? "webapp.reflow.video.detail" : "webapp.video-detail");
        }
        if (detail == null) throw new Exception("TikTok post details were unavailable");
        int status = detail.optInt("statusCode", 0);
        if (status != 0) throw new Exception("TikTok returned video status " + status);
        JSONObject info = detail.optJSONObject("itemInfo");
        JSONObject item = info == null ? null : info.optJSONObject("itemStruct");
        if (item == null) throw new Exception("Could not read the video details");
        return item;
    }

    private static String fetchText(String url, StringBuilder resolvedUrl, BrowserProfile profile,
                                    CookieManager cookies, Trace trace) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        long started = System.nanoTime();
        try {
            connection.setConnectTimeout(profile == DESKTOP ? 15000 : 10000);
            connection.setReadTimeout(profile == DESKTOP ? 15000 : 10000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", profile.userAgent);
            if (profile.wide) {
                connection.setRequestProperty("Viewport-Width", "4096");
                connection.setRequestProperty("Sec-CH-Viewport-Width", "4096");
                connection.setRequestProperty("DPR", "2");
                connection.setRequestProperty("Sec-CH-DPR", "2");
                connection.setRequestProperty("Width", "4096");
            }
            connection.setRequestProperty("Accept", "text/html,application/xhtml+xml");
            MediaRequestCookies.attach(connection, cookies, true);
            int code = connection.getResponseCode();
            MediaRequestCookies.capture(connection, cookies);
            if (resolvedUrl != null) resolvedUrl.append(connection.getURL());
            if (code != 200) throw new Exception("TikTok page returned HTTP " + code);
            try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    out.write(buffer, 0, count);
                    if (out.size() > 5_000_000) throw new Exception("TikTok page was too large");
                }
                trace(trace, "resolver.page", "host=" + connection.getURL().getHost() +
                    " profile=" + profileName(profile) + " http=" + code +
                    " bytes=" + out.size() + " elapsedMs=" + (System.nanoTime() - started) / 1_000_000);
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } catch (Exception error) {
            trace(trace, "resolver.page", "host=" + connection.getURL().getHost() +
                " profile=" + profileName(profile) +
                " elapsedMs=" + (System.nanoTime() - started) / 1_000_000 +
                " error=" + DiagnosticsFormat.describe(error));
            throw error;
        } finally { connection.disconnect(); }
    }

    private static String profileName(BrowserProfile profile) {
        return profile == WIDE_DESKTOP ? "wide_desktop" : profile == MOBILE ? "mobile" : "desktop";
    }

    private static void trace(Trace sink, String component, String detail) {
        if (sink == null) return;
        try { sink.event(component, detail); }
        catch (RuntimeException ignored) { /* Diagnostics must not affect resolution. */ }
    }

}
