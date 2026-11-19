package com.codex.mediadownloader;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.net.http.HttpEngine;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.HttpCookie;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DownloadService extends Service {
    private static final String CHANNEL = "tiktok_stream_download_silent_v2";
    private static final String RESULTS_CHANNEL = "tiktok_stream_results_silent_v2";
    private static final int NOTIFICATION_ID = 2101;
    private static final int FIRST_JOB_ID = 3000;
    private NotificationManager notifications;
    private ExecutorService transfers;
    private final Object serviceLock = new Object();
    private int pendingDownloads;
    private boolean foregroundStarted;

    @Override public void onCreate() {
        super.onCreate();
        notifications = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel progress = new NotificationChannel(CHANNEL, "Media downloads", NotificationManager.IMPORTANCE_LOW);
        progress.setSound(null, null);
        progress.enableVibration(false);
        notifications.createNotificationChannel(progress);
        NotificationChannel results = new NotificationChannel(RESULTS_CHANNEL, "Download results", NotificationManager.IMPORTANCE_LOW);
        results.setSound(null, null);
        results.enableVibration(false);
        notifications.createNotificationChannel(results);
        transfers = Executors.newFixedThreadPool(3);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        int retryJobId = intent == null ? 0 : intent.getIntExtra("retryJobId", 0);
        DownloadStore.Entry retry = retryJobId > 0 ? DownloadStore.find(this, retryJobId) : null;
        if (retryJobId > 0 && (retry == null || !"album".equals(retry.mediaType) || retry.isActive()))
            return START_NOT_STICKY;
        String shareUrl = retry == null ? (intent == null ? null : intent.getStringExtra("shareUrl")) : retry.shareUrl;
        if (shareUrl == null || shareUrl.isEmpty()) {
            synchronized (serviceLock) {
                if (pendingDownloads == 0) stopSelfResult(startId);
            }
            return START_NOT_STICKY;
        }
        int jobId;
        synchronized (serviceLock) {
            if (retry != null) jobId = retryJobId;
            else {
                SharedPreferences jobs = getSharedPreferences("download_jobs", MODE_PRIVATE);
                jobId = jobs.getInt("next_id", FIRST_JOB_ID);
                jobs.edit().putInt("next_id", jobId == Integer.MAX_VALUE ? FIRST_JOB_ID : jobId + 1).commit();
            }
            pendingDownloads++;
            if (!foregroundStarted) {
                startForeground(NOTIFICATION_ID, summaryNotice(pendingDownloads), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                foregroundStarted = true;
            } else {
                notifications.notify(NOTIFICATION_ID, summaryNotice(pendingDownloads));
            }
        }
        DownloadStore.Entry entry = retry == null ? new DownloadStore.Entry(jobId) : retry;
        entry.shareUrl = shareUrl;
        entry.state = "queued";
        entry.stage = retry == null ? "Waiting to start" : "Retrying missing photos";
        entry.error = "";
        entry.finishedAt = 0;
        DownloadStore.save(this, entry, true);
        notifications.notify(jobId, notice(jobId, "Download " + jobId, "Waiting to start", 0, true, true));
        transfers.execute(() -> runDownload(shareUrl, jobId, startId, entry, retry != null));
        return START_NOT_STICKY;
    }

    private void runDownload(String shareUrl, int jobId, int startId, DownloadStore.Entry entry, boolean retry) {
        String title = "Download " + jobId;
        String fileName = null;
        try {
            entry.state = "resolving";
            entry.stage = "Finding post details";
            DownloadStore.save(this, entry, false);
            notifications.notify(jobId, notice(jobId, title, "Finding post media", 0, true, true));
            StreamResolver.Result result = StreamResolver.resolve(shareUrl);
            if (retry && (!result.isAlbum() || !result.videoId.equals(entry.videoId)))
                throw new Exception("The shared album no longer matches this job");
            for (HttpCookie cookie : result.cookies.getCookieStore().getCookies())
                Log.d("TikTokStream", "Page cookie " + cookie.getName() + " domain=" + cookie.getDomain());
            title = result.profileName + " • " + result.videoId;
            fileName = fileName(result);
            entry.profileName = result.profileName;
            entry.accountId = result.accountId;
            entry.videoId = result.videoId;
            entry.publishTimeSeconds = result.publishTimeSeconds;
            entry.sourceUrl = result.pageUrl;
            entry.postTitle = result.postText.title;
            entry.caption = result.postText.description;
            entry.hashtags = result.postText.hashtags;
            if (result.isAlbum()) {
                final String albumTitle = title;
                entry.mediaType = "album";
                entry.state = "selecting";
                entry.stage = "Preparing " + result.photos.size() + " photos";
                DownloadStore.save(this, entry, true);
                AlbumTransfer.Summary summary = AlbumTransfer.download(this, result, entry, (changed, current, total) -> {
                    DownloadStore.save(this, changed, false);
                    notifications.notify(jobId, albumNotice(jobId, albumTitle, changed, current, total));
                });
                entry.state = summary.failed == 0 ? "complete" : summary.complete == 0 ? "failed" : "partial";
                entry.stage = summary.complete + " of " + result.photos.size() + " photos saved";
                entry.error = summary.failed == 0 ? "" : summary.failed + " photo" + (summary.failed == 1 ? "" : "s") + " failed";
                entry.speedBytesPerSecond = 0;
                entry.finishedAt = System.currentTimeMillis();
                DownloadStore.save(this, entry, true);
                String resultTitle = summary.failed == 0 ? "Album complete" : "Album incomplete";
                notifications.notify(-jobId, resultNotice(resultTitle, entry.stage,
                    "Post: " + result.pageUrl + "\n" + entry.stage + "\nSize: " + formatBytes(entry.downloadedBytes), jobId));
                return;
            }
            entry.fileName = fileName;
            entry.state = "selecting";
            entry.stage = "Selecting stream";
            DownloadStore.save(this, entry, true);
            DownloadThumbnail.fetchAsync(this, jobId, result.coverUrl);
            notifications.notify(jobId, detailNotice(jobId, title, fileName, "Selecting stream", 0, 0, -1, -1, null));
            SavedVideo saved = transfer(result, jobId, title, fileName, entry);
            entry.state = "complete";
            entry.stage = "Complete";
            entry.fileName = saved.name;
            entry.savedUri = saved.uri.toString();
            entry.downloadedBytes = saved.bytes;
            entry.totalBytes = saved.bytes;
            entry.speedBytesPerSecond = 0;
            entry.width = saved.tracks.width;
            entry.height = saved.tracks.height;
            entry.videoMime = saved.tracks.videoMime;
            entry.audioMime = saved.tracks.audioMime;
            entry.durationMs = saved.tracks.durationMs;
            entry.finishedAt = System.currentTimeMillis();
            DownloadStore.save(this, entry, true);
            String details = "File: " + saved.name + "\nResolution: " + saved.tracks.width + " × " + saved.tracks.height +
                "\nSize: " + formatBytes(saved.bytes) + "\nVideo: " + codecName(saved.tracks.videoMime) +
                "\nAudio: " + codecName(saved.tracks.audioMime) + "\nSaved to Downloads";
            notifications.notify(-jobId, resultNotice("Download complete", saved.name, details, jobId));
        } catch (Exception error) {
            Log.e("TikTokStream", "Download " + jobId + " failed", error);
            DiagnosticsStore.record(this, "download.failed job=" + jobId + " stage=" + entry.stage, error);
            String reason = error.getMessage() == null ? "Unknown error" : error.getMessage();
            entry.state = "failed";
            entry.stage = "Failed";
            entry.error = reason;
            entry.speedBytesPerSecond = 0;
            entry.finishedAt = System.currentTimeMillis();
            DownloadStore.save(this, entry, true);
            String details = (fileName == null ? "" : "File: " + fileName + "\n") + "Reason: " + reason;
            notifications.notify(-jobId, resultNotice("Download failed", reason, details, jobId));
        } finally {
            notifications.cancel(jobId);
            synchronized (serviceLock) {
                pendingDownloads--;
                if (pendingDownloads == 0) {
                    stopForeground(STOP_FOREGROUND_REMOVE);
                    foregroundStarted = false;
                    stopSelfResult(startId);
                } else {
                    notifications.notify(NOTIFICATION_ID, summaryNotice(pendingDownloads));
                }
            }
        }
    }

    private SavedVideo transfer(StreamResolver.Result result, int jobId, String title, String fileName,
                                DownloadStore.Entry entry) throws Exception {
        String videoId = result.videoId;
        File video = new File(getCacheDir(), "video-" + videoId + "-" + jobId + ".mp4");
        Uri pending = null;
        try {
            MediaMux.Tracks tracks = null;
            Set<String> tried = new HashSet<>();
            Set<String> videoOnlyVariants = new HashSet<>();
            int option = 0;
            int accessDenied = 0;
            int noAudio = 0;
            String lastError = null;
            for (StreamResolver.Candidate candidate : result.candidates) {
                if (videoOnlyVariants.contains(candidate.variant) || !tried.add(candidate.url)) continue;
                option++;
                try {
                    entry.streamOption = option;
                    entry.width = candidate.width;
                    entry.height = candidate.height;
                    entry.downloadedBytes = 0;
                    entry.totalBytes = -1;
                    entry.speedBytesPerSecond = 0;
                    entry.state = "connecting";
                    entry.stage = "Connecting to stream " + option;
                    DownloadStore.save(this, entry, false);
                    Log.i("TikTokStream", "Trying stream option " + option + " " + candidate.width +
                        "x" + candidate.height + " " + (candidate.variant.equals("default") ? "default" : "bitrate"));
                    Exception accessError = null;
                    try {
                        fetch(candidate.url, result.pageUrl, result.cookies, 0, false, video,
                            "Downloading stream • option " + option, jobId, title, fileName,
                            candidate.width, candidate.height, entry);
                        Log.i("TikTokStream", "Stream option " + option + " downloaded without visitor cookies");
                    } catch (Exception publicError) {
                        accessError = publicError;
                        DiagnosticsStore.record(this, "stream.public job=" + jobId + " option=" + option, publicError);
                        video.delete();
                        if (!MediaRequestCookies.retryWithVisitorCookies(publicError)) throw publicError;
                        for (int profile = 0; profile < (Build.VERSION.SDK_INT >= 34 ? 6 : 5); profile++) {
                            try {
                                fetch(candidate.url, result.pageUrl, result.cookies, profile, true, video,
                                    "Downloading stream • option " + option, jobId, title, fileName,
                                    candidate.width, candidate.height, entry);
                                accessError = null;
                                break;
                            } catch (Exception error) {
                                accessError = error;
                                DiagnosticsStore.record(this, "stream.visitor job=" + jobId + " option=" + option + " profile=" + profile, error);
                                Log.w("TikTokStream", "Stream option " + option + " visitor profile " + profile +
                                    " failed: " + error.getMessage());
                                video.delete();
                                if (!MediaRequestCookies.retryWithVisitorCookies(error)) break;
                            }
                        }
                    }
                    if (accessError != null) throw accessError;
                    entry.state = "checking";
                    entry.stage = "Checking video and audio";
                    entry.speedBytesPerSecond = 0;
                    DownloadStore.save(this, entry, false);
                    notifications.notify(jobId, detailNotice(jobId, title, fileName, "Checking video and audio", candidate.width,
                        candidate.height, video.length(), video.length(), null));
                    try {
                        tracks = MediaMux.inspectMuxed(video);
                    } catch (Exception invalidTracks) {
                        videoOnlyVariants.add(candidate.variant);
                        throw invalidTracks;
                    }
                    Log.i("TikTokStream", "Selected muxed stream option=" + option +
                        " listed=" + candidate.width + "x" + candidate.height +
                        " actual=" + tracks.width + "x" + tracks.height +
                        " video=" + tracks.videoMime + " audio=" + tracks.audioMime);
                    break;
                } catch (Exception candidateError) {
                    DiagnosticsStore.record(this, "stream.candidate job=" + jobId + " option=" + option, candidateError);
                    lastError = candidateError.getMessage();
                    if (lastError != null && lastError.contains("HTTP 403")) accessDenied++;
                    if (lastError != null && lastError.contains("audio")) noAudio++;
                    Log.w("TikTokStream", "Skipping stream option " + option + " " +
                        candidate.width + "x" + candidate.height + ": " + candidateError.getMessage());
                    video.delete();
                    entry.state = "retrying";
                    entry.stage = "Trying another stream";
                    entry.downloadedBytes = 0;
                    entry.totalBytes = -1;
                    entry.speedBytesPerSecond = 0;
                    DownloadStore.save(this, entry, false);
                    notifications.notify(jobId, detailNotice(jobId, title, fileName, "Trying another stream", 0, 0, -1, -1, null));
                }
            }
            if (tracks == null) {
                if (accessDenied > 0 && accessDenied + noAudio == option)
                    throw new Exception("TikTok refused " + accessDenied + " playback stream" +
                        (accessDenied == 1 ? "" : "s") + " (HTTP 403); please retry");
                if (noAudio > 0 && noAudio == option)
                    throw new Exception("TikTok's listed streams did not include audio");
                throw new Exception("No usable stream with video and audio: " +
                    (lastError == null ? "unknown response" : lastError));
            }
            entry.state = "tagging";
            entry.stage = "Adding TikTok source link";
            DownloadStore.save(this, entry, false);
            notifications.notify(jobId, detailNotice(jobId, title, fileName, entry.stage, tracks.width,
                tracks.height, video.length(), video.length(), tracks));
            String sourceUrl = "https://www.tiktok.com/@" + result.profileName + "/video/" + result.videoId;
            Mp4SourceMetadata.write(video, sourceUrl, result.postText);
            MediaMux.Tracks taggedTracks = MediaMux.inspectMuxed(video);
            if (taggedTracks.width != tracks.width || taggedTracks.height != tracks.height ||
                !taggedTracks.videoMime.equals(tracks.videoMime) || !taggedTracks.audioMime.equals(tracks.audioMime))
                throw new Exception("MP4 source metadata changed video or audio tracks");
            tracks = taggedTracks;
            entry.state = "saving";
            entry.stage = "Saving to Downloads";
            entry.width = tracks.width;
            entry.height = tracks.height;
            entry.videoMime = tracks.videoMime;
            entry.audioMime = tracks.audioMime;
            entry.durationMs = tracks.durationMs;
            entry.downloadedBytes = video.length();
            entry.totalBytes = video.length();
            DownloadStore.save(this, entry, false);
            DownloadThumbnail.fromVideo(this, jobId, video);
            notifications.notify(jobId, detailNotice(jobId, title, fileName, "Saving to Downloads", tracks.width, tracks.height,
                video.length(), video.length(), tracks));
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
            values.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/");
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            pending = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (pending == null) throw new Exception("Could not create file in Downloads");
            try (InputStream input = new FileInputStream(video); OutputStream output = getContentResolver().openOutputStream(pending)) {
                if (output == null) throw new Exception("Could not write the download file");
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                output.flush();
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            getContentResolver().update(pending, done, null, null);
            String actualName = fileName;
            try (Cursor names = getContentResolver().query(pending, new String[]{MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null)) {
                if (names != null && names.moveToFirst()) actualName = names.getString(0);
            }
            Uri savedFile = pending;
            pending = null;
            Log.i("TikTokStream", "Saved " + actualName + " bytes=" + video.length() +
                " resolution=" + tracks.width + "x" + tracks.height + " audio=" + tracks.audioMime);
            return new SavedVideo(actualName, savedFile, video.length(), tracks);
        } catch (Exception error) {
            if (pending != null) getContentResolver().delete(pending, null, null);
            throw error;
        } finally {
            video.delete();
        }
    }

    private static String fileName(StreamResolver.Result result) {
        SimpleDateFormat timestamp = new SimpleDateFormat("yyyy-MM-dd_hh-mm", Locale.US);
        String publishedAt = timestamp.format(new Date(result.publishTimeSeconds * 1000L));
        return result.profileName + "_profile_" + result.accountId + "_VideoID_" + result.videoId + "_" + publishedAt + ".mp4";
    }

    private void fetch(String url, String referer, java.net.CookieManager cookies, int profile,
                       boolean visitorCookies,
                       File file, String label, int jobId, String title, String fileName,
                       int width, int height, DownloadStore.Entry entry) throws Exception {
        HttpEngine engine = profile == 5 ? new HttpEngine.Builder(this).build() : null;
        HttpURLConnection connection = engine == null ? (HttpURLConnection) new URL(url).openConnection() :
            (HttpURLConnection) engine.openConnection(new URL(url));
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setInstanceFollowRedirects(true);
        String userAgent = profile >= 3 ?
            "Mozilla/5.0 (Linux; Android 15; Mobile) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36" :
            StreamResolver.USER_AGENT;
        connection.setRequestProperty("User-Agent", userAgent);
        if (profile == 0 || profile == 3 || profile == 5) connection.setRequestProperty("Referer", referer);
        else if (profile == 1) connection.setRequestProperty("Referer", "https://www.tiktok.com/");
        connection.setRequestProperty("Accept", "video/mp4,video/*;q=0.9,*/*;q=0.8");
        if (profile == 4) connection.setRequestProperty("Range", "bytes=0-");
        MediaRequestCookies.attach(connection, cookies, visitorCookies);
        try {
            int code = connection.getResponseCode();
            Log.d("TikTokStream", "Media response profile=" + profile + " host=" + connection.getURL().getHost() +
                " status=" + code + " type=" + connection.getContentType());
            if (code != 200 && code != 206) throw new Exception(label + ": HTTP " + code);
            long total = connection.getContentLengthLong();
            entry.state = "downloading";
            entry.stage = label;
            entry.totalBytes = total;
            DownloadStore.save(this, entry, false);
            notifications.notify(jobId, detailNotice(jobId, title, fileName, label, width, height, 0, total, null));
            try (InputStream input = connection.getInputStream(); OutputStream output = new FileOutputStream(file)) {
                byte[] first = new byte[64];
                int head = 0;
                while (head < first.length) {
                    int count = input.read(first, head, first.length - head);
                    if (count < 0) break;
                    head += count;
                }
                if (head < 8 || first[4] != 'f' || first[5] != 't' || first[6] != 'y' || first[7] != 'p') {
                    throw new Exception("Video server returned a page instead of MP4");
                }
                output.write(first, 0, head);
                long written = head;
                long lastUpdate = SystemClock.elapsedRealtime();
                long lastBytes = 0;
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                    written += count;
                    long now = SystemClock.elapsedRealtime();
                    if (now - lastUpdate >= 800) {
                        entry.downloadedBytes = written;
                        entry.speedBytesPerSecond = Math.max(0, (written - lastBytes) * 1000 / (now - lastUpdate));
                        DownloadStore.save(this, entry, false);
                        notifications.notify(jobId, detailNotice(jobId, title, fileName, label, width, height, written, total, null));
                        lastUpdate = now;
                        lastBytes = written;
                    }
                }
                output.flush();
                if (written < 1024) throw new Exception(label + " was unexpectedly short");
                entry.downloadedBytes = written;
                entry.totalBytes = total > 0 ? total : written;
                entry.speedBytesPerSecond = 0;
                DownloadStore.save(this, entry, false);
                notifications.notify(jobId, detailNotice(jobId, title, fileName, "Stream received", width, height, written, total, null));
            }
        } finally {
            connection.disconnect();
            if (engine != null) {
                try { engine.shutdown(); }
                catch (Exception error) { Log.d("TikTokStream", "HTTP engine shutdown deferred", error); }
            }
        }
    }

    private Notification summaryNotice(int count) {
        return new Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Media Downloader")
            .setContentText(count + (count == 1 ? " download active or queued" : " downloads active or queued"))
            .setContentIntent(openDownloads(0))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build();
    }

    private Notification notice(int jobId, String title, String message, int progress, boolean indeterminate, boolean ongoing) {
        return new Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(message)
            .setContentIntent(openDownloads(jobId))
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress, indeterminate)
            .build();
    }

    private Notification detailNotice(int jobId, String title, String fileName, String status, int width, int height,
                                      long received, long total, MediaMux.Tracks tracks) {
        String resolution = width > 0 && height > 0 ? width + " × " + height : "Unknown";
        String size = received < 0 ? "Waiting for stream" : formatBytes(received) +
            (total > 0 ? " / " + formatBytes(total) : " downloaded");
        int percent = total > 0 && received >= 0 ? (int) Math.min(100, received * 100 / total) : 0;
        StringBuilder details = new StringBuilder("File: ").append(fileName)
            .append("\nStatus: ").append(status)
            .append("\nResolution: ").append(resolution);
        if (tracks == null && width > 0 && height > 0) details.append(" (reported)");
        details.append("\nSize: ").append(size);
        if (total > 0 && received >= 0) details.append(" (").append(percent).append("%)");
        if (tracks == null) {
            details.append("\nAudio: checking after transfer");
        } else {
            details.append("\nVideo: ").append(codecName(tracks.videoMime));
            details.append("\nAudio: ").append(codecName(tracks.audioMime));
        }
        return new Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(status + " • " + resolution + " • " + size)
            .setContentIntent(openDownloads(jobId))
            .setStyle(new Notification.BigTextStyle().bigText(details.toString()))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent, total <= 0)
            .build();
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024));
        return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private static String codecName(String mime) {
        if ("video/avc".equals(mime)) return "H.264";
        if ("video/hevc".equals(mime)) return "H.265";
        if ("audio/mp4a-latm".equals(mime)) return "AAC";
        return mime;
    }

    private Notification resultNotice(String title, String message, String details, int jobId) {
        Notification.Builder builder = new Notification.Builder(this, RESULTS_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(new Notification.BigTextStyle().bigText(details))
            .setContentIntent(openDownloads(jobId))
            .setAutoCancel(true);
        return builder.build();
    }

    private Notification albumNotice(int jobId, String title, DownloadStore.Entry entry,
                                     int current, int total) {
        int saved = 0;
        for (DownloadStore.Entry.Photo photo : entry.photos) if (!photo.savedUri.isEmpty()) saved++;
        String detail = "Photo " + Math.min(current, total) + " of " + total +
            " • " + saved + " saved • " + formatBytes(entry.downloadedBytes);
        return new Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(detail)
            .setContentIntent(openDownloads(jobId))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(Math.max(1, total), saved, false)
            .build();
    }

    private PendingIntent openDownloads(int jobId) {
        Intent open = new Intent(this, MainActivity.class)
            .putExtra(MainActivity.EXTRA_PAGE, MainActivity.DOWNLOADS_PAGE)
            .putExtra(MainActivity.EXTRA_JOB_ID, jobId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, jobId, open,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static final class SavedVideo {
        final String name;
        final Uri uri;
        final long bytes;
        final MediaMux.Tracks tracks;
        SavedVideo(String name, Uri uri, long bytes, MediaMux.Tracks tracks) {
            this.name = name; this.uri = uri; this.bytes = bytes; this.tracks = tracks;
        }
    }

    @Override public void onDestroy() {
        transfers.shutdownNow();
        super.onDestroy();
    }

    @Override public void onTimeout(int startId, int foregroundServiceType) { stopSelf(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
