package com.codex.mediadownloader;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import android.util.Log;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Iterator;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** Resolves public Instagram embed data without using the installed app's session. */
final class InstagramResolver {
    private static final long INSTAGRAM_EPOCH_MS = 1314220021721L;
    private static final int MAX_PAGE_BYTES = 2_000_000;
    private static final String MODERN_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36";
    private static final Pattern DATA_SJS = Pattern.compile("<script\\b[^>]*\\bdata-sjs[^>]*>(\\{.*?\\})</script>",
        Pattern.DOTALL);

    private InstagramResolver() { }

    static boolean accepts(String link) { return shortcode(link) != null || storyParts(link) != null; }

    static StreamResolver.Result resolve(String link, StreamResolver.Trace trace) throws Exception {
        String[] story = storyParts(link);
        if (story != null) return resolveStory(story, trace);
        String code = shortcode(link);
        if (code == null) throw new Exception("Invalid Instagram post link");
        String kind = new URI(link).getPath().startsWith("/reel/") ? "reel" : "p";
        String canonical = "https://www.instagram.com/" + kind + "/" + code + "/";
        // The public post embed also serves Reel media. No account or source-app cookie is attached.
        String embed = "https://www.instagram.com/p/" + code + "/embed/";
        String html = null;
        for (String agent : new String[]{"Mozilla/5.0", "facebookexternalhit/1.1", "Instagram 219.0.0.12.117 Android"}) {
            html = fetch(embed, agent, trace);
            Log.d("MediaDownloader", "Instagram embed for " + code + " chars=" + html.length() +
                " context=" + (contextJson(html) != null) + " agent=" + agent);
            if (contextJson(html) != null || ("p".equals(kind) && singlePhotoProfile(html, code) != null)) break;
        }
        StreamResolver.Result result;
        if (contextJson(html) != null) {
            result = parse(html, canonical, code);
        } else if ("p".equals(kind)) {
            // Some single-photo embeds omit contextJSON but still list the main image's
            // signed renditions. Bind that image to the shortcode via ig_cache_key;
            // other images on the page may belong to suggested posts.
            String profile = singlePhotoProfile(html, code);
            if (profile == null) {
                result = resolveModern(canonical, code, trace);
                trace(trace, "instagram.resolved", "kind=video streams=" + result.candidates.size());
                return result;
            }
            String postId = idFromShortcode(code);
            String accountId = "";
            String caption = "";
            try {
                String endpoint = "https://www.instagram.com/api/v1/oembed/?url=" +
                    URLEncoder.encode(canonical, "UTF-8");
                JSONObject metadata = new JSONObject(fetch(endpoint, "Mozilla/5.0", trace));
                String ownerId = metadata.optString("author_id", "");
                if (profile.equals(metadata.optString("author_name")) && ownerId.matches("[0-9]{1,24}") &&
                    (postId + "_" + ownerId).equals(metadata.optString("media_id"))) {
                    accountId = ownerId;
                    caption = metadata.optString("title", "");
                    trace(trace, "instagram.oembed", "matched=true caption_chars=" + caption.length());
                } else {
                    trace(trace, "instagram.oembed", "matched=false");
                }
            } catch (Exception error) {
                trace(trace, "instagram.oembed", "available=false " + error.getClass().getSimpleName());
            }
            if (accountId.isEmpty()) {
                String profileHtml = fetch("https://www.instagram.com/" + profile + "/", "Mozilla/5.0", trace);
                if (!profileHtml.contains("\"username\":\"" + profile + "\""))
                    throw new Exception("Instagram did not confirm the photo owner");
                Matcher ownerId = Pattern.compile("\"profile_id\":\"([0-9]{1,24})\"").matcher(profileHtml);
                if (!ownerId.find()) throw new Exception("Instagram did not expose the photo owner ID");
                accountId = ownerId.group(1);
            }
            result = parseSinglePhoto(html, accountId, caption, canonical, code);
        } else {
            result = resolveModern(canonical, code, trace);
        }
        trace(trace, "instagram.resolved", "kind=" + (result.isAlbum() ? "photos" : "video") +
            " photos=" + result.photos.size() + " streams=" + result.candidates.size());
        return result;
    }

    private static StreamResolver.Result resolveModern(String canonical, String code,
            StreamResolver.Trace trace) throws Exception {
        try {
            return parseModern(fetchModern(canonical, trace), canonical, code, trace);
        } catch (Exception first) {
            if (canonical.contains("/p/") || String.valueOf(first.getMessage()).contains("HTTP 429")) throw first;
            // Instagram's post route can expose the same Reel data when its Reel route
            // returns only the application shell. The shortcode and numeric ID must match.
            String postRoute = "https://www.instagram.com/p/" + code + "/";
            trace(trace, "instagram.modern", "retrying_post_route=true");
            try { return parseModern(fetchModern(postRoute, trace), canonical, code, trace); }
            catch (Exception second) { second.addSuppressed(first); throw second; }
        }
    }

    private static StreamResolver.Result parseModern(String html, String canonical, String code,
            StreamResolver.Trace trace) throws Exception {
        String postId = idFromShortcode(code);
        trace(trace, "instagram.modern_page", "bytes=" + html.length() +
            " mediaRef=" + html.contains("xig_polaris_media") +
            " versionsRef=" + html.contains("video_versions"));
        Log.d("MediaDownloader", "Instagram modern page chars=" + html.length() +
            " mediaRef=" + html.contains("xig_polaris_media") +
            " versionsRef=" + html.contains("video_versions"));
        Matcher scripts = DATA_SJS.matcher(html);
        JSONObject product = null;
        int count = 0;
        while (scripts.find() && count++ < 80) {
            try {
                product = findModernProduct(new JSONObject(scripts.group(1)), code, postId, 0);
                if (product != null) break;
            } catch (org.json.JSONException ignored) { /* Other script blocks are not post data. */ }
        }
        if (product == null) throw new Exception("Instagram did not expose public media details for this post");
        JSONObject user = product.optJSONObject("user");
        String profile = user == null ? "" : user.optString("username", "");
        String account = user == null ? "" : user.optString("pk", user.optString("id", ""));
        if (!profile.matches("[A-Za-z0-9._]{1,40}") || !account.matches("[0-9]{1,24}"))
            throw new Exception("Instagram did not provide a valid video owner");
        long published = product.optLong("taken_at", 0);
        if (published < 1262304000L || published > System.currentTimeMillis() / 1000L + 86400L)
            throw new Exception("Instagram did not provide a valid video publish time");
        JSONObject caption = product.optJSONObject("caption");
        PostText postText = PostText.from("", caption == null ? "" : caption.optString("text", ""),
            Collections.emptyList());
        List<StreamResolver.Candidate> videos = new ArrayList<>();
        JSONArray versions = product.optJSONArray("video_versions");
        if (versions != null) for (int i = versions.length() - 1; i >= 0; i--) {
            JSONObject version = versions.optJSONObject(i);
            if (version == null) continue;
            String url = version.optString("url", "");
            if (https(url)) videos.add(new StreamResolver.Candidate(url,
                version.optInt("width", 0), version.optInt("height", 0), 0,
                "instagram-modern-" + version.optString("type", Integer.toString(i))));
        }
        if (videos.isEmpty()) throw new Exception("Instagram did not expose a direct video stream");
        String cover = product.optString("display_uri", "");
        if (!https(cover)) {
            JSONObject image = product.optJSONObject("image_versions2");
            JSONArray candidates = image == null ? null : image.optJSONArray("candidates");
            JSONObject first = candidates == null ? null : candidates.optJSONObject(0);
            cover = first == null ? "" : first.optString("url", "");
            if (!https(cover)) cover = "";
        }
        trace(trace, "instagram.modern", "matched=true streams=" + videos.size() +
            " listed=" + product.optInt("original_width", 0) + "x" + product.optInt("original_height", 0));
        return new StreamResolver.Result(profile, account, postId, cover, canonical, postText,
            published, videos, new ArrayList<>(), new CookieManager(null, CookiePolicy.ACCEPT_NONE), "instagram");
    }

    private static JSONObject findModernProduct(Object node, String code, String postId, int depth) {
        if (depth > 40) return null;
        if (node instanceof JSONObject) {
            JSONObject json = (JSONObject) node;
            JSONObject media = json.optJSONObject("xig_polaris_media");
            JSONObject product = media == null ? null : media.optJSONObject("if_not_gated_logged_out");
            if (product != null && code.equals(product.optString("code")) &&
                postId.equals(product.optString("pk"))) return product;
            Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                JSONObject found = findModernProduct(json.opt(keys.next()), code, postId, depth + 1);
                if (found != null) return found;
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                JSONObject found = findModernProduct(array.opt(i), code, postId, depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static String fetchModern(String url, StreamResolver.Trace trace) throws Exception {
        return fetchPage(url, MODERN_USER_AGENT, trace, true);
    }

    /** A Story is resolved by its own ID. Never substitute an embedded/shared post. */
    private static StreamResolver.Result resolveStory(String[] story, StreamResolver.Trace trace) throws Exception {
        String profile = story[0], storyId = story[1];
        String canonical = "https://www.instagram.com/stories/" + profile + "/" + storyId + "/";
        String html = fetch("https://www.instagram.com/" + profile + "/", "Mozilla/5.0", trace);
        Matcher accountMatch = Pattern.compile("\\\"profile_id\\\":\\\"([0-9]{1,24})\\\"").matcher(html);
        if (!accountMatch.find()) throw new Exception("Instagram did not expose the Story owner ID publicly");
        String account = accountMatch.group(1);
        String json = fetchStoryData(account, canonical, trace);
        JSONObject response = new JSONObject(json);
        JSONObject reels = response.optJSONObject("reels");
        JSONObject reel = reels == null ? null : reels.optJSONObject(account);
        if (reel == null && reels != null) reel = reels.optJSONObject(profile);
        JSONArray items = reel == null ? null : reel.optJSONArray("items");
        if (items == null) throw new Exception("Instagram did not expose public Story media without an account session");
        JSONObject item = null;
        for (int i = 0; i < items.length(); i++) {
            JSONObject candidate = items.optJSONObject(i);
            if (candidate != null && storyId.equals(candidate.optString("pk", candidate.optString("id")))) {
                item = candidate;
                break;
            }
        }
        if (item == null) throw new Exception("The current Story is unavailable or has expired");
        long published = item.optLong("taken_at", 0);
        if (published < 1000000000L || published > System.currentTimeMillis() / 1000L + 86400L)
            throw new Exception("Instagram did not expose a valid Story publish time");
        JSONObject caption = item.optJSONObject("caption");
        PostText postText = PostText.from("", caption == null ? "" : caption.optString("text", ""), Collections.emptyList());
        List<StreamResolver.Candidate> videos = new ArrayList<>();
        List<StreamResolver.PhotoCandidate> photos = new ArrayList<>();
        String cover = "";
        JSONArray variants = item.optJSONArray("video_versions");
        if (variants != null && variants.length() > 0) {
            JSONObject stills = item.optJSONObject("image_versions2");
            JSONArray previews = stills == null ? null : stills.optJSONArray("candidates");
            if (previews != null) for (int i = 0; i < previews.length(); i++) {
                JSONObject preview = previews.optJSONObject(i);
                if (preview != null && https(preview.optString("url", ""))) {
                    cover = preview.optString("url");
                    break;
                }
            }
            for (int i = 0; i < variants.length() && i < 24; i++) {
                JSONObject variant = variants.optJSONObject(i);
                if (variant == null) continue;
                String url = variant.optString("url", "");
                if (https(url)) videos.add(new StreamResolver.Candidate(url,
                    variant.optInt("width", 0), variant.optInt("height", 0), 0, "instagram-story"));
            }
            if (videos.isEmpty()) throw new Exception("Instagram Story has no public video stream");
        } else {
            JSONObject versions = item.optJSONObject("image_versions2");
            JSONArray images = versions == null ? null : versions.optJSONArray("candidates");
            Set<String> urls = new LinkedHashSet<>();
            int largestWidth = 0, largestHeight = 0;
            if (images != null) for (int i = 0; i < images.length() && i < 24; i++) {
                JSONObject image = images.optJSONObject(i);
                if (image == null || !https(image.optString("url", ""))) continue;
                urls.add(image.optString("url"));
                int width = image.optInt("width", 0), height = image.optInt("height", 0);
                if ((long) width * height > (long) largestWidth * largestHeight) {
                    largestWidth = width; largestHeight = height;
                }
            }
            if (urls.isEmpty()) throw new Exception("Instagram Story has no public photo rendition");
            photos.add(new StreamResolver.PhotoCandidate(1, largestWidth, largestHeight, new ArrayList<>(urls)));
            cover = urls.iterator().next();
        }
        trace(trace, "instagram.story", "kind=" + (photos.isEmpty() ? "video" : "photo") +
            " variants=" + (photos.isEmpty() ? videos.size() : photos.get(0).urls.size()));
        return new StreamResolver.Result(profile, account, storyId, cover, canonical, postText,
            published, videos, photos, new CookieManager(null, CookiePolicy.ACCEPT_NONE), "instagram");
    }

    private static String fetchStoryData(String account, String referer, StreamResolver.Trace trace) throws Exception {
        String url = "https://www.instagram.com/api/v1/feed/reels_media/?reel_ids=" + account;
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(20000);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Referer", referer);
            int status = connection.getResponseCode();
            trace(trace, "instagram.story_api", "http=" + status + " bytes=" + connection.getContentLengthLong());
            if (status != 200) throw new Exception("Instagram public Story returned HTTP " + status);
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[32768];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > MAX_PAGE_BYTES) throw new Exception("Instagram Story response is too large");
                    output.write(buffer, 0, count);
                }
                return output.toString(StandardCharsets.UTF_8.name());
            }
        } finally { connection.disconnect(); }
    }

    private static String[] storyParts(String link) {
        try {
            URI url = new URI(link);
            String host = url.getHost();
            if (!"https".equalsIgnoreCase(url.getScheme()) || host == null || url.getPort() != -1 ||
                !(host.equalsIgnoreCase("instagram.com") || host.equalsIgnoreCase("www.instagram.com"))) return null;
            Matcher match = Pattern.compile("^/stories/([A-Za-z0-9._]{1,40})/([0-9]{15,22})/?$").matcher(url.getPath());
            return match.matches() ? new String[]{match.group(1), match.group(2)} : null;
        } catch (Exception error) { return null; }
    }

    static StreamResolver.Result parse(String html, String canonical, String code) throws Exception {
        String json = contextJson(html);
        if (json == null) throw new Exception("Instagram did not expose public media details for this post");
        JSONObject data = new JSONObject(json);
        JSONObject gql = data.optJSONObject("gql_data");
        JSONObject media = gql == null ? null : gql.optJSONObject("shortcode_media");
        if (media == null || !code.equals(media.optString("shortcode")))
            throw new Exception("Instagram returned a different post");
        String mediaId = media.optString("id", "");
        long id;
        try { id = Long.parseLong(mediaId); }
        catch (NumberFormatException error) { throw new Exception("Instagram did not provide a numeric post ID"); }
        if (!mediaId.equals(idFromShortcode(code)))
            throw new Exception("Instagram returned media for a different post");
        long published = (id >>> 23) + INSTAGRAM_EPOCH_MS;
        if (published < 1262304000000L || published > System.currentTimeMillis() + 86400000L)
            throw new Exception("Instagram post ID has an invalid publish time");
        JSONObject owner = media.optJSONObject("owner");
        String account = owner == null ? "" : owner.optString("id", "");
        String profile = owner == null ? "" : owner.optString("username", "");
        if (!account.matches("[0-9]{1,24}") || !profile.matches("[A-Za-z0-9._]{1,40}"))
            throw new Exception("Instagram did not provide a valid post owner");
        String caption = "";
        JSONObject captions = media.optJSONObject("edge_media_to_caption");
        JSONArray captionEdges = captions == null ? null : captions.optJSONArray("edges");
        if (captionEdges != null && captionEdges.length() > 0) {
            JSONObject edge = captionEdges.optJSONObject(0);
            JSONObject node = edge == null ? null : edge.optJSONObject("node");
            if (node != null) caption = node.optString("text", "");
        }
        PostText postText = PostText.from("", caption, Collections.emptyList());
        CookieManager anonymous = new CookieManager(null, CookiePolicy.ACCEPT_NONE);
        String cover = media.optString("display_url", "");
        if (!https(cover)) cover = "";
        List<StreamResolver.PhotoCandidate> photos = new ArrayList<>();
        List<StreamResolver.Candidate> videos = new ArrayList<>();
        JSONObject sidecar = media.optJSONObject("edge_sidecar_to_children");
        JSONArray edges = sidecar == null ? null : sidecar.optJSONArray("edges");
        if (edges != null) {
            if (edges.length() == 0 || edges.length() > 100)
                throw new Exception("Instagram album has an invalid number of items");
            for (int i = 0; i < edges.length(); i++) {
                JSONObject edge = edges.optJSONObject(i);
                JSONObject child = edge == null ? null : edge.optJSONObject("node");
                if (child == null) throw new Exception("Instagram album item is missing");
                if (child.optBoolean("is_video"))
                    throw new Exception("Instagram mixed video and photo albums are not supported yet");
                photos.add(photo(child, i + 1));
            }
        } else if (media.optBoolean("is_video")) {
            String url = media.optString("video_url", "");
            if (!https(url)) throw new Exception("Instagram did not provide a public video stream");
            // The post image dimensions need not match the video track; MediaMux inspects the actual file.
            videos.add(new StreamResolver.Candidate(url, 0, 0, 0, "instagram-public"));
        } else {
            photos.add(photo(media, 1));
        }
        if (cover.isEmpty() && !photos.isEmpty()) cover = photos.get(0).urls.get(0);
        return new StreamResolver.Result(profile, account, mediaId, cover, canonical,
            postText, published / 1000, videos, photos, anonymous, "instagram");
    }

    private static String singlePhotoProfile(String html, String code) throws Exception {
        String postId = idFromShortcode(code);
        Matcher images = Pattern.compile("<img\\b[^>]*>", Pattern.CASE_INSENSITIVE).matcher(html);
        while (images.find()) {
            String tag = images.group();
            String alt = attribute(tag, "alt").replace("&#064;", "@");
            Matcher owner = Pattern.compile("^Instagram post shared by @([A-Za-z0-9._]{1,40})$").matcher(alt);
            if (!owner.matches()) continue;
            String sources = attribute(tag, "srcSet");
            if (sources.isEmpty()) sources = attribute(tag, "src");
            if (hasPostImage(sources, postId)) return owner.group(1);
        }
        return null;
    }

    private static StreamResolver.Result parseSinglePhoto(String html, String accountId, String caption,
            String canonical, String code) throws Exception {
        String postId = idFromShortcode(code);
        String profile = singlePhotoProfile(html, code);
        if (profile == null) throw new Exception("Instagram did not expose this post's main photo");
        List<String> urls = new ArrayList<>();
        Set<String> unique = new LinkedHashSet<>();
        Matcher images = Pattern.compile("<img\\b[^>]*>", Pattern.CASE_INSENSITIVE).matcher(html);
        while (images.find()) {
            String tag = images.group();
            String alt = attribute(tag, "alt").replace("&#064;", "@");
            if (!alt.equals("Instagram post shared by @" + profile)) continue;
            Matcher variants = Pattern.compile("(https://\\S+?)\\s+([0-9]{2,5})w(?:,|$)")
                    .matcher(attribute(tag, "srcSet").replace("&amp;", "&"));
            while (variants.find()) {
                String url = variants.group(1);
                if (postId.equals(cachePostId(url)) && unique.add(url)) urls.add(url);
                if (urls.size() >= 24) break;
            }
            String src = attribute(tag, "src").replace("&amp;", "&");
            if (postId.equals(cachePostId(src)) && unique.add(src)) urls.add(src);
            break;
        }
        if (urls.isEmpty()) throw new Exception("Instagram did not expose this post's photo renditions");
        long numericId = Long.parseLong(postId);
        long published = (numericId >>> 23) + INSTAGRAM_EPOCH_MS;
        if (published < 1262304000000L || published > System.currentTimeMillis() + 86400000L)
            throw new Exception("Instagram post ID has an invalid publish time");
        List<StreamResolver.PhotoCandidate> photos = new ArrayList<>();
        photos.add(new StreamResolver.PhotoCandidate(1, 0, 0, urls));
        return new StreamResolver.Result(profile, accountId, postId, urls.get(0), canonical,
            PostText.from("", caption, Collections.emptyList()), published / 1000,
            new ArrayList<>(), photos, new CookieManager(null, CookiePolicy.ACCEPT_NONE), "instagram");
    }

    private static String attribute(String tag, String name) {
        Matcher match = Pattern.compile("(?:^|\\s)" + name + "=\"([^\"]*)\"", Pattern.CASE_INSENSITIVE).matcher(tag);
        return match.find() ? match.group(1) : "";
    }

    private static boolean hasPostImage(String source, String postId) {
        Matcher variants = Pattern.compile("https://[^\\s,]+", Pattern.CASE_INSENSITIVE).matcher(source.replace("&amp;", "&"));
        while (variants.find()) if (postId.equals(cachePostId(variants.group()))) return true;
        return false;
    }

    private static String cachePostId(String url) {
        Matcher key = Pattern.compile("(?:[?&])ig_cache_key=([^&]+)").matcher(url);
        if (!key.find()) return "";
        try {
            String encoded = URLDecoder.decode(key.group(1), "UTF-8").split("\\.")[0];
            String id = new String(Base64.getDecoder().decode(encoded), StandardCharsets.US_ASCII);
            return id.matches("[0-9]{1,20}") ? id : "";
        } catch (Exception error) { return ""; }
    }

    private static String idFromShortcode(String code) throws Exception {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        long id = 0;
        for (int i = 0; i < code.length(); i++) {
            int digit = alphabet.indexOf(code.charAt(i));
            if (digit < 0 || id > (Long.MAX_VALUE - digit) / 64)
                throw new Exception("Invalid Instagram post code");
            id = id * 64 + digit;
        }
        return Long.toString(id);
    }

    private static StreamResolver.PhotoCandidate photo(JSONObject item, int index) throws Exception {
        JSONObject dimensions = item.optJSONObject("dimensions");
        int width = dimensions == null ? 0 : dimensions.optInt("width", 0);
        int height = dimensions == null ? 0 : dimensions.optInt("height", 0);
        Set<String> urls = new LinkedHashSet<>();
        String display = item.optString("display_url", "");
        if (https(display)) urls.add(display);
        JSONArray resources = item.optJSONArray("display_resources");
        if (resources != null) for (int i = 0; i < resources.length(); i++) {
            JSONObject resource = resources.optJSONObject(i);
            if (resource == null) continue;
            int candidateWidth = resource.optInt("config_width", 0);
            int candidateHeight = resource.optInt("config_height", 0);
            if (width > 0 && height > 0 && candidateWidth > 0 && candidateHeight > 0 &&
                Math.abs((double) candidateWidth / candidateHeight - (double) width / height) > 0.08)
                continue; // Omit square crops of a portrait or landscape original.
            String url = resource.optString("src", "");
            if (https(url)) urls.add(url);
            if (urls.size() >= 12) break;
        }
        if (urls.isEmpty()) throw new Exception("Instagram did not provide an image for item " + index);
        return new StreamResolver.PhotoCandidate(index, width, height, new ArrayList<>(urls));
    }

    private static String contextJson(String html) throws Exception {
        String marker = "\"contextJSON\":";
        int start = html.indexOf(marker);
        if (start < 0) return null;
        start += marker.length();
        if (start >= html.length() || html.charAt(start) != '"') return null;
        boolean escaped = false;
        for (int i = start + 1; i < html.length(); i++) {
            char ch = html.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (ch == '\\') { escaped = true; continue; }
            if (ch == '"') return new JSONArray("[" + html.substring(start, i + 1) + "]").getString(0);
        }
        throw new Exception("Instagram embed contains incomplete media data");
    }

    private static String fetch(String url, String agent, StreamResolver.Trace trace) throws Exception {
        return fetchPage(url, agent, trace, false);
    }

    private static String fetchPage(String url, String agent, StreamResolver.Trace trace,
            boolean modern) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(20000);
            // Instagram's legacy public embed currently serves structured media for this
            // minimal browser identity; a current desktop Chrome identity gets the app shell.
            connection.setRequestProperty("User-Agent", agent);
            connection.setRequestProperty("Accept", modern ?
                "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8" :
                "text/html,application/xhtml+xml");
            connection.setRequestProperty("Accept-Language", modern ? "en-us,en;q=0.5" : "en-US,en;q=0.9");
            if (modern) connection.setRequestProperty("Sec-Fetch-Mode", "navigate");
            int status = connection.getResponseCode();
            trace(trace, modern ? "instagram.page" : "instagram.embed",
                "http=" + status + " bytes=" + connection.getContentLengthLong());
            if (status != 200) throw new Exception("Instagram public post returned HTTP " + status);
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[32768];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > MAX_PAGE_BYTES) throw new Exception("Instagram public page is too large");
                    output.write(buffer, 0, count);
                }
                return output.toString(StandardCharsets.UTF_8.name());
            }
        } finally { connection.disconnect(); }
    }

    private static String shortcode(String link) {
        try {
            URI url = new URI(link);
            String host = url.getHost();
            if (!"https".equalsIgnoreCase(url.getScheme()) || host == null ||
                !(host.equalsIgnoreCase("instagram.com") || host.equalsIgnoreCase("www.instagram.com")) ||
                url.getPort() != -1) return null;
            String[] parts = url.getPath().split("/");
            if (parts.length < 3 || !("p".equals(parts[1]) || "reel".equals(parts[1]) || "tv".equals(parts[1])) ||
                !parts[2].matches("[A-Za-z0-9_-]{5,20}")) return null;
            return parts[2];
        } catch (Exception error) { return null; }
    }

    private static boolean https(String value) {
        try { return "https".equalsIgnoreCase(new URI(value).getScheme()); }
        catch (Exception error) { return false; }
    }

    private static void trace(StreamResolver.Trace trace, String component, String detail) {
        if (trace != null) trace.event(component, detail);
    }
}
