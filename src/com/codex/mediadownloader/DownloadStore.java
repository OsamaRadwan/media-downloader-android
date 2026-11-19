package com.codex.mediadownloader;

import android.content.ContentUris;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Log;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONException;
import org.json.JSONArray;
import org.json.JSONObject;

final class DownloadStore {
    private static final String FILE = "download_history";
    private static final String PREFIX = "job_";
    private static boolean importRunning;
    private static final Pattern CURRENT_NAME = Pattern.compile("^(.+)_profile_(\\d+)_VideoID_(\\d+)_");
    private static final Pattern PRIOR_NAME = Pattern.compile("^profile_(\\d+)_VideoID_(\\d+)_");
    private static final Pattern OLD_NAME = Pattern.compile("^TikTok-(\\d+)-(\\d+)-");

    private DownloadStore() { }

    static final class Entry {
        static final class Photo {
            final int index;
            String fileName = "", savedUri = "", error = "", mime = "", thumbnailPath = "";
            long bytes;
            int width, height, sourceWidth, sourceHeight;
            Photo(int index) { this.index = index; }
            JSONObject toJson() throws JSONException {
                JSONObject json = new JSONObject();
                json.put("index", index); json.put("fileName", fileName); json.put("savedUri", savedUri);
                json.put("error", error); json.put("mime", mime); json.put("bytes", bytes);
                json.put("thumbnailPath", thumbnailPath);
                json.put("width", width); json.put("height", height);
                json.put("sourceWidth", sourceWidth); json.put("sourceHeight", sourceHeight);
                return json;
            }
            static Photo fromJson(JSONObject json) {
                Photo photo = new Photo(json.optInt("index", 0));
                photo.fileName = json.optString("fileName", "");
                photo.savedUri = json.optString("savedUri", "");
                photo.error = json.optString("error", "");
                photo.mime = json.optString("mime", "");
                photo.thumbnailPath = json.optString("thumbnailPath", "");
                photo.bytes = json.optLong("bytes", 0);
                photo.width = json.optInt("width", 0);
                photo.height = json.optInt("height", 0);
                photo.sourceWidth = json.optInt("sourceWidth", 0);
                photo.sourceHeight = json.optInt("sourceHeight", 0);
                return photo;
            }
        }
        final int id;
        long createdAt;
        long updatedAt;
        long finishedAt;
        String state = "queued";
        String stage = "Waiting to start";
        String shareUrl = "";
        String profileName = "";
        String accountId = "";
        String videoId = "";
        String fileName = "";
        String thumbnailPath = "";
        String savedUri = "";
        String error = "";
        String videoMime = "";
        String audioMime = "";
        long publishTimeSeconds;
        long downloadedBytes;
        long totalBytes = -1;
        long speedBytesPerSecond;
        long durationMs;
        int width;
        int height;
        int streamOption;
        String mediaType = "video", sourceUrl = "", postTitle = "", caption = "", hashtags = "";
        final List<Photo> photos = new ArrayList<>();

        Entry(int id) {
            this.id = id;
            createdAt = System.currentTimeMillis();
            updatedAt = createdAt;
        }

        boolean isActive() {
            return !"complete".equals(state) && !"failed".equals(state) && !"partial".equals(state) && !"interrupted".equals(state);
        }

        JSONObject toJson() throws JSONException {
            JSONObject data = new JSONObject();
            data.put("id", id);
            data.put("createdAt", createdAt);
            data.put("updatedAt", updatedAt);
            data.put("finishedAt", finishedAt);
            data.put("state", state);
            data.put("stage", stage);
            data.put("shareUrl", shareUrl);
            data.put("profileName", profileName);
            data.put("accountId", accountId);
            data.put("videoId", videoId);
            data.put("fileName", fileName);
            data.put("thumbnailPath", thumbnailPath);
            data.put("savedUri", savedUri);
            data.put("error", error);
            data.put("videoMime", videoMime);
            data.put("audioMime", audioMime);
            data.put("publishTimeSeconds", publishTimeSeconds);
            data.put("downloadedBytes", downloadedBytes);
            data.put("totalBytes", totalBytes);
            data.put("speedBytesPerSecond", speedBytesPerSecond);
            data.put("durationMs", durationMs);
            data.put("width", width);
            data.put("height", height);
            data.put("streamOption", streamOption);
            data.put("mediaType", mediaType);
            data.put("sourceUrl", sourceUrl);
            data.put("postTitle", postTitle);
            data.put("caption", caption);
            data.put("hashtags", hashtags);
            JSONArray album = new JSONArray();
            for (Photo photo : photos) album.put(photo.toJson());
            data.put("photos", album);
            return data;
        }

        static Entry fromJson(String source) throws JSONException {
            JSONObject data = new JSONObject(source);
            Entry entry = new Entry(data.getInt("id"));
            entry.createdAt = data.optLong("createdAt", entry.createdAt);
            entry.updatedAt = data.optLong("updatedAt", entry.createdAt);
            entry.finishedAt = data.optLong("finishedAt", 0);
            entry.state = data.optString("state", "queued");
            entry.stage = data.optString("stage", "");
            entry.shareUrl = data.optString("shareUrl", "");
            entry.profileName = data.optString("profileName", "");
            entry.accountId = data.optString("accountId", "");
            entry.videoId = data.optString("videoId", "");
            entry.fileName = data.optString("fileName", "");
            entry.thumbnailPath = data.optString("thumbnailPath", "");
            entry.savedUri = data.optString("savedUri", "");
            entry.error = data.optString("error", "");
            entry.videoMime = data.optString("videoMime", "");
            entry.audioMime = data.optString("audioMime", "");
            entry.publishTimeSeconds = data.optLong("publishTimeSeconds", 0);
            entry.downloadedBytes = data.optLong("downloadedBytes", 0);
            entry.totalBytes = data.optLong("totalBytes", -1);
            entry.speedBytesPerSecond = data.optLong("speedBytesPerSecond", 0);
            entry.durationMs = data.optLong("durationMs", 0);
            entry.width = data.optInt("width", 0);
            entry.height = data.optInt("height", 0);
            entry.streamOption = data.optInt("streamOption", 0);
            entry.mediaType = data.optString("mediaType", "video");
            entry.sourceUrl = data.optString("sourceUrl", "");
            entry.postTitle = data.optString("postTitle", "");
            entry.caption = data.optString("caption", "");
            entry.hashtags = data.optString("hashtags", "");
            JSONArray album = data.optJSONArray("photos");
            if (album != null) for (int i = 0; i < album.length(); i++) {
                JSONObject photo = album.optJSONObject(i);
                if (photo != null) entry.photos.add(Photo.fromJson(photo));
            }
            return entry;
        }
    }

    static synchronized void save(Context context, Entry entry, boolean durable) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
            String old = prefs.getString(PREFIX + entry.id, null);
            if (old != null) {
                Entry previous = Entry.fromJson(old);
                if (entry.thumbnailPath.isEmpty()) entry.thumbnailPath = previous.thumbnailPath;
                for (int i = 0; i < entry.photos.size() && i < previous.photos.size(); i++)
                    if (entry.photos.get(i).thumbnailPath.isEmpty())
                        entry.photos.get(i).thumbnailPath = previous.photos.get(i).thumbnailPath;
            }
            entry.updatedAt = System.currentTimeMillis();
            SharedPreferences.Editor editor = prefs.edit().putString(PREFIX + entry.id, entry.toJson().toString());
            if (durable) {
                if (!editor.commit()) DiagnosticsStore.record(context, "history.commit", "Download history commit returned false");
            } else editor.apply();
        } catch (JSONException error) {
            DiagnosticsStore.record(context, "history.save", error);
            Log.e("TikTokStream", "Could not save download details", error);
        }
    }

    static synchronized void setThumbnail(Context context, int id, String path) {
        SharedPreferences prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        String saved = prefs.getString(PREFIX + id, null);
        if (saved == null) return;
        try {
            Entry entry = Entry.fromJson(saved);
            if (!entry.thumbnailPath.isEmpty()) return;
            entry.thumbnailPath = path;
            save(context, entry, true);
        } catch (JSONException error) {
            DiagnosticsStore.record(context, "history.thumbnail", error);
            Log.e("TikTokStream", "Could not save thumbnail", error);
        }
    }

    static synchronized void setPhotoThumbnail(Context context, int id, int index, String path) {
        SharedPreferences prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        String saved = prefs.getString(PREFIX + id, null);
        if (saved == null) return;
        try {
            Entry entry = Entry.fromJson(saved);
            if (index < 1 || index > entry.photos.size()) return;
            Entry.Photo photo = entry.photos.get(index - 1);
            if (!photo.thumbnailPath.isEmpty()) return;
            photo.thumbnailPath = path;
            save(context, entry, true);
        } catch (JSONException error) {
            DiagnosticsStore.record(context, "history.photo_thumbnail", error);
            Log.e("MediaDownloader", "Could not save photo thumbnail", error);
        }
    }

    static synchronized boolean hasThumbnail(Context context, int id) {
        String saved = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(PREFIX + id, null);
        if (saved == null) return false;
        try { return !Entry.fromJson(saved).thumbnailPath.isEmpty(); }
        catch (JSONException error) { return false; }
    }

    static List<Entry> list(Context context) {
        Map<String, ?> all = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getAll();
        List<Entry> entries = new ArrayList<>();
        for (Map.Entry<String, ?> item : all.entrySet()) {
            if (!item.getKey().startsWith(PREFIX) || !(item.getValue() instanceof String)) continue;
            try { entries.add(Entry.fromJson((String) item.getValue())); }
            catch (JSONException error) {
                DiagnosticsStore.record(context, "history.damaged_record", error);
                Log.w("TikTokStream", "Skipping damaged download record", error);
            }
        }
        Collections.sort(entries, (a, b) -> Long.compare(b.createdAt, a.createdAt));
        return entries;
    }

    static synchronized Entry find(Context context, int id) {
        String saved = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(PREFIX + id, null);
        if (saved == null) return null;
        try { return Entry.fromJson(saved); }
        catch (JSONException error) { return null; }
    }

    static synchronized void importExistingAsync(Context context) {
        Context app = context.getApplicationContext();
        if (importRunning || app.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("legacy_imported", false)) return;
        importRunning = true;
        new Thread(() -> {
            try {
                importExisting(app);
                app.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean("legacy_imported", true).commit();
            } catch (Exception error) {
                DiagnosticsStore.record(app, "history.import", error);
                Log.w("TikTokStream", "Could not import earlier downloads", error);
            } finally {
                synchronized (DownloadStore.class) { importRunning = false; }
            }
        }, "TikTokDownloadImport").start();
    }

    private static void importExisting(Context context) throws Exception {
        Set<String> known = new HashSet<>();
        for (Entry entry : list(context)) if (!entry.savedUri.isEmpty()) known.add(entry.savedUri);
        Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
        String[] columns = {MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.OWNER_PACKAGE_NAME};
        try (Cursor cursor = context.getContentResolver().query(collection, columns,
            MediaStore.MediaColumns.MIME_TYPE + "=?", new String[]{"video/mp4"}, null)) {
            if (cursor == null) throw new Exception("Downloads database unavailable");
            int idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID);
            int nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME);
            int sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE);
            int dateColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED);
            int ownerColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.OWNER_PACKAGE_NAME);
            while (cursor.moveToNext()) {
                if (!context.getPackageName().equals(cursor.getString(ownerColumn))) continue;
                long mediaId = cursor.getLong(idColumn);
                Uri uri = ContentUris.withAppendedId(collection, mediaId);
                if (known.contains(uri.toString())) continue;
                String name = cursor.getString(nameColumn);
                if (name == null) continue;
                int jobId = -((int) (mediaId % 1_000_000_000L) + 1);
                Entry entry = new Entry(jobId);
                entry.createdAt = cursor.getLong(dateColumn) * 1000L;
                if (entry.createdAt <= 0) entry.createdAt = System.currentTimeMillis();
                entry.finishedAt = entry.createdAt;
                entry.state = "complete";
                entry.stage = "Complete";
                entry.fileName = name;
                entry.savedUri = uri.toString();
                entry.downloadedBytes = cursor.getLong(sizeColumn);
                entry.totalBytes = entry.downloadedBytes;
                parseName(entry, name);
                try {
                    MediaMux.Tracks tracks = MediaMux.inspectMuxed(context, uri);
                    entry.width = tracks.width;
                    entry.height = tracks.height;
                    entry.videoMime = tracks.videoMime;
                    entry.audioMime = tracks.audioMime;
                    entry.durationMs = tracks.durationMs;
                } catch (Exception error) {
                    Log.d("TikTokStream", "Could not inspect earlier video " + name, error);
                }
                save(context, entry, true);
                DownloadThumbnail.fromUri(context, jobId, uri);
                known.add(uri.toString());
            }
        }
    }

    private static void parseName(Entry entry, String name) {
        Matcher current = CURRENT_NAME.matcher(name);
        if (current.find()) {
            entry.profileName = current.group(1);
            entry.accountId = current.group(2);
            entry.videoId = current.group(3);
            return;
        }
        Matcher prior = PRIOR_NAME.matcher(name);
        if (prior.find()) {
            entry.accountId = prior.group(1);
            entry.videoId = prior.group(2);
            return;
        }
        Matcher old = OLD_NAME.matcher(name);
        if (old.find()) {
            entry.accountId = old.group(1);
            entry.videoId = old.group(2);
        }
    }
}
