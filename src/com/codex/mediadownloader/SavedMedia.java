package com.codex.mediadownloader;

import android.content.Context;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.media.ExifInterface;
import android.net.Uri;
import android.media.MediaScannerConnection;
import android.os.Environment;
import android.os.SystemClock;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import java.io.File;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Compares the actual MediaStore file with its recorded name and byte size. */
final class SavedMedia {
    private static final Map<String, Long> lastScan = new ConcurrentHashMap<>();
    private SavedMedia() { }

    static boolean matches(Context context, String value, String expectedName, long expectedBytes) {
        return !resolve(context, value, expectedName, expectedBytes).isEmpty();
    }

    static boolean matchesSelected(Context context, Uri uri, String expectedName, long expectedBytes) {
        return uri != null && expectedName != null && !expectedName.isEmpty() && expectedBytes > 0 &&
            matchesUri(context, uri, expectedName, expectedBytes);
    }

    /** Repairs legacy retry records that lost their byte count using the photo's embedded post identity. */
    static long recoverSelectedJpegSize(Context context, Uri uri, String expectedName,
                                        int expectedWidth, int expectedHeight, String sourceUrl) {
        if (uri == null || expectedName == null || !expectedName.toLowerCase(java.util.Locale.ROOT).endsWith(".jpg") ||
            expectedWidth <= 0 || expectedHeight <= 0) return 0;
        try {
            String expectedSource = SourcePostUrl.forMetadata(sourceUrl);
            String actualName;
            try (Cursor cursor = context.getContentResolver().query(uri,
                new String[]{MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor == null || !cursor.moveToFirst()) return 0;
                actualName = cursor.getString(0);
            }
            if (!expectedName.equals(actualName)) return 0;
            long bytes;
            try (ParcelFileDescriptor file = context.getContentResolver().openFileDescriptor(uri, "r")) {
                if (file == null || (bytes = file.getStatSize()) <= 0) return 0;
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream input = context.getContentResolver().openInputStream(uri)) {
                if (input == null) return 0;
                BitmapFactory.decodeStream(input, null, bounds);
            }
            if (bounds.outWidth != expectedWidth || bounds.outHeight != expectedHeight) return 0;
            try (InputStream input = context.getContentResolver().openInputStream(uri)) {
                if (input == null) return 0;
                String comment = new ExifInterface(input).getAttribute(ExifInterface.TAG_USER_COMMENT);
                if (comment == null) return 0;
                String embeddedSource = comment.split("\\n", 2)[0].trim();
                return expectedSource.equals(SourcePostUrl.forMetadata(embeddedSource)) ? bytes : 0;
            }
        } catch (Exception invalid) { return 0; }
    }

    /** Returns a readable URI only after checking its filename and actual byte count. */
    static String resolve(Context context, String value, String expectedName, long expectedBytes) {
        if (expectedName == null || expectedName.isEmpty() || expectedBytes <= 0) return "";
        if (value != null && !value.isEmpty() && matchesUri(context, Uri.parse(value), expectedName, expectedBytes))
            return value;
        String found = findByNameAndSize(context, expectedName, expectedBytes);
        if (!found.isEmpty()) return found;
        boolean scanned = scanRestoredFile(context, expectedName);
        if (scanned)
            return findByNameAndSize(context, expectedName, expectedBytes);
        return "";
    }

    private static String findByNameAndSize(Context context, String expectedName, long expectedBytes) {
        try (Cursor cursor = context.getContentResolver().query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            new String[]{MediaStore.MediaColumns._ID}, MediaStore.MediaColumns.DISPLAY_NAME + "=?",
            new String[]{expectedName}, null)) {
            if (cursor == null) return "";
            while (cursor.moveToNext()) {
                Uri candidate = android.content.ContentUris.withAppendedId(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(0));
                if (matchesUri(context, candidate, expectedName, expectedBytes)) return candidate.toString();
            }
        } catch (Exception ignored) { }
        return "";
    }

    private static boolean scanRestoredFile(Context context, String name) {
        if (name.contains("/") || name.contains("\\") || name.equals(".") || name.equals("..")) return false;
        File path = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), name);
        if (!path.isFile()) return false;
        long now = SystemClock.elapsedRealtime();
        Long prior = lastScan.put(name, now);
        if (prior != null && now - prior < 60_000) return false;
        CountDownLatch scanned = new CountDownLatch(1);
        MediaScannerConnection.scanFile(context, new String[]{path.getAbsolutePath()}, null,
            (scannedPath, uri) -> scanned.countDown());
        try { return scanned.await(2, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static boolean matchesUri(Context context, Uri uri, String expectedName, long expectedBytes) {
        String actualName;
        try (Cursor cursor = context.getContentResolver().query(uri,
            new String[]{MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) return false;
            actualName = cursor.getString(0);
        } catch (Exception ignored) {
            return false;
        }
        try (ParcelFileDescriptor file = context.getContentResolver().openFileDescriptor(uri, "r")) {
            return file != null && MediaFileIdentity.matches(expectedName, expectedBytes,
                actualName, file.getStatSize());
        } catch (Exception ignored) {
            return false;
        }
    }

    static int availablePhotos(Context context, DownloadStore.Entry entry) {
        int present = 0;
        for (DownloadStore.Entry.Photo photo : entry.photos)
            if (matches(context, photo.savedUri, photo.fileName, photo.bytes)) present++;
        return present;
    }

    static boolean complete(Context context, DownloadStore.Entry entry) {
        if ("album".equals(entry.mediaType))
            return !entry.photos.isEmpty() && availablePhotos(context, entry) == entry.photos.size();
        return matches(context, entry.savedUri, entry.fileName,
            entry.downloadedBytes > 0 ? entry.downloadedBytes : entry.totalBytes);
    }
}
