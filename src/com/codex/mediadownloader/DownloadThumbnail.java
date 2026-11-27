package com.codex.mediadownloader;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.util.Size;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class DownloadThumbnail {
    private static final ExecutorService albumThumbnails = Executors.newSingleThreadExecutor();
    private static final Set<String> requestedAlbumThumbnails = new HashSet<>();
    private DownloadThumbnail() { }

    static void ensureAlbumPhotoAsync(Context context, int jobId, int index, String savedUri) {
        String key = jobId + ":" + index;
        synchronized (requestedAlbumThumbnails) {
            if (!requestedAlbumThumbnails.add(key)) return;
        }
        Context app = context.getApplicationContext();
        albumThumbnails.execute(() -> {
            try {
                Bitmap image = app.getContentResolver().loadThumbnail(Uri.parse(savedUri),
                    new Size(200, 200), null);
                File directory = new File(app.getFilesDir(), "download_thumbnails");
                if (!directory.isDirectory() && !directory.mkdirs()) return;
                File output = new File(directory, jobId + "-photo-" + index + ".jpg");
                try (FileOutputStream stream = new FileOutputStream(output)) {
                    if (!image.compress(Bitmap.CompressFormat.JPEG, 78, stream)) return;
                } finally { image.recycle(); }
                DownloadStore.setPhotoThumbnail(app, jobId, index, output.getAbsolutePath());
            } catch (Exception error) {
                DiagnosticsStore.record(app, "thumbnail.album_restore job=" + jobId + " index=" + index, error);
                Log.d("MediaDownloader", "Could not restore album thumbnail " + index, error);
            }
        });
    }

    static void fetchAsync(Context context, int jobId, String coverUrl) {
        if (coverUrl == null || !coverUrl.startsWith("https://")) return;
        Context app = context.getApplicationContext();
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                connection = NetworkRouteStore.open(new URL(coverUrl));
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                connection.setRequestProperty("User-Agent", StreamResolver.USER_AGENT);
                if (connection.getResponseCode() != 200) return;
                try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        output.write(buffer, 0, count);
                        if (output.size() > 2_000_000) return;
                    }
                    byte[] data = output.toByteArray();
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(data, 0, data.length, options);
                    int longest = Math.max(options.outWidth, options.outHeight);
                    options.inSampleSize = Math.max(1, longest / 400);
                    options.inJustDecodeBounds = false;
                    Bitmap image = BitmapFactory.decodeByteArray(data, 0, data.length, options);
                    if (image != null) save(app, jobId, image);
                }
            } catch (Exception error) {
                DiagnosticsStore.record(app, "thumbnail.cover job=" + jobId, error);
                Log.d("TikTokStream", "Cover image unavailable for download " + jobId, error);
            } finally {
                if (connection != null) connection.disconnect();
            }
        }, "TikTokCover-" + jobId).start();
    }

    static void fromVideo(Context context, int jobId, File video) {
        if (DownloadStore.hasThumbnail(context, jobId)) return;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(video.getAbsolutePath());
            Bitmap frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame != null) save(context, jobId, frame);
        } catch (Exception error) {
            DiagnosticsStore.record(context, "thumbnail.video job=" + jobId, error);
            Log.d("TikTokStream", "Could not make video thumbnail for download " + jobId, error);
        } finally {
            try { retriever.release(); }
            catch (Exception error) { Log.d("TikTokStream", "Could not release thumbnail reader", error); }
        }
    }

    static void fromImage(Context context, int jobId, File imageFile) {
        if (DownloadStore.hasThumbnail(context, jobId)) return;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(imageFile.getAbsolutePath(), bounds);
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = Math.max(1, Math.max(bounds.outWidth, bounds.outHeight) / 400);
            Bitmap image = BitmapFactory.decodeFile(imageFile.getAbsolutePath(), options);
            if (image != null) save(context, jobId, image);
        } catch (Exception error) {
            DiagnosticsStore.record(context, "thumbnail.album job=" + jobId, error);
            Log.d("MediaDownloader", "Could not make album thumbnail for download " + jobId, error);
        }
    }

    static String fromAlbumPhoto(Context context, int jobId, int index, File imageFile) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(imageFile.getAbsolutePath(), bounds);
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = Math.max(1, Math.max(bounds.outWidth, bounds.outHeight) / 200);
            Bitmap image = BitmapFactory.decodeFile(imageFile.getAbsolutePath(), options);
            if (image == null) return "";
            return saveAlbumPreview(context, jobId, index, image);
        } catch (Exception error) {
            DiagnosticsStore.record(context, "thumbnail.photo job=" + jobId + " index=" + index, error);
            Log.d("MediaDownloader", "Could not make photo thumbnail " + index, error);
            return "";
        }
    }

    static String fromAlbumVideo(Context context, int jobId, int index, Uri uri) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, uri);
            Bitmap frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            return frame == null ? "" : saveAlbumPreview(context, jobId, index, frame);
        } catch (Exception error) {
            DiagnosticsStore.record(context, "thumbnail.album_video job=" + jobId + " index=" + index, error);
            Log.d("MediaDownloader", "Could not make album video thumbnail " + index, error);
            return "";
        } finally {
            try { retriever.release(); }
            catch (Exception ignored) { }
        }
    }

    private static String saveAlbumPreview(Context context, int jobId, int index, Bitmap image) throws Exception {
        try {
            File directory = new File(context.getFilesDir(), "download_thumbnails");
            if (!directory.isDirectory() && !directory.mkdirs()) return "";
            File output = new File(directory, jobId + "-photo-" + index + ".jpg");
            try (FileOutputStream stream = new FileOutputStream(output)) {
                if (!image.compress(Bitmap.CompressFormat.JPEG, 78, stream)) return "";
            }
            return output.getAbsolutePath();
        } finally { image.recycle(); }
    }

    static void fromUri(Context context, int jobId, Uri uri) {
        if (DownloadStore.hasThumbnail(context, jobId)) return;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, uri);
            Bitmap frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame != null) save(context, jobId, frame);
        } catch (Exception error) {
            DiagnosticsStore.record(context, "thumbnail.saved_video job=" + jobId, error);
            Log.d("TikTokStream", "Could not make saved video thumbnail for download " + jobId, error);
        } finally {
            try { retriever.release(); }
            catch (Exception error) { Log.d("TikTokStream", "Could not release thumbnail reader", error); }
        }
    }

    private static void save(Context context, int jobId, Bitmap image) throws Exception {
        if (DownloadStore.hasThumbnail(context, jobId)) return;
        int longest = Math.max(image.getWidth(), image.getHeight());
        Bitmap small = longest > 400 ? Bitmap.createScaledBitmap(image,
            Math.max(1, image.getWidth() * 400 / longest), Math.max(1, image.getHeight() * 400 / longest), true) : image;
        File directory = new File(context.getFilesDir(), "download_thumbnails");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new Exception("Could not create thumbnail directory");
        File output = new File(directory, jobId + ".jpg");
        try (FileOutputStream stream = new FileOutputStream(output)) {
            if (!small.compress(Bitmap.CompressFormat.JPEG, 82, stream)) throw new Exception("Could not write thumbnail");
        }
        DownloadStore.setThumbnail(context, jobId, output.getAbsolutePath());
        if (small != image) small.recycle();
        image.recycle();
    }
}
