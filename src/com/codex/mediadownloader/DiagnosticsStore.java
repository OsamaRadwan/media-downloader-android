package com.codex.mediadownloader;

import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** App-private, bounded failure history. It never reads system logcat. */
final class DiagnosticsStore {
    private static final Object LOCK = new Object();
    private static final String CURRENT = "failure-events.log";
    private static final String PREVIOUS = "failure-events.previous.log";
    private static final long MAX_FILE_BYTES = 128 * 1024;

    private DiagnosticsStore() { }

    static void record(Context context, String component, Throwable error) {
        try { append(context, component, DiagnosticsFormat.describe(error)); }
        catch (Throwable ignored) { /* Diagnostics must never replace the original failure. */ }
    }

    static void record(Context context, String component, String message) {
        try { append(context, component, DiagnosticsFormat.clean(message)); }
        catch (Throwable ignored) { /* Diagnostics must never replace the original failure. */ }
    }

    private static void append(Context context, String component, String detail) {
        try {
            String line = utc(System.currentTimeMillis()) + " | " + DiagnosticsFormat.clean(component) +
                " | " + detail + "\n";
            byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
            synchronized (LOCK) {
                File current = file(context, CURRENT);
                if (current.length() + bytes.length > MAX_FILE_BYTES) {
                    File previous = file(context, PREVIOUS);
                    if (previous.exists() && !previous.delete()) throw new Exception("Could not rotate prior log");
                    if (current.exists() && !current.renameTo(previous)) throw new Exception("Could not rotate active log");
                }
                try (FileOutputStream output = new FileOutputStream(current, true)) {
                    output.write(bytes);
                    output.getFD().sync();
                }
            }
        } catch (Throwable storageFailure) {
            Log.e("MediaDownloader", "Could not persist diagnostic failure", storageFailure);
        }
    }

    static int eventCount(Context context) {
        synchronized (LOCK) {
            return count(file(context, PREVIOUS)) + count(file(context, CURRENT));
        }
    }

    static void export(Context context, OutputStream output) throws Exception {
        synchronized (LOCK) {
            OutputStreamWriter writer = new OutputStreamWriter(output, StandardCharsets.UTF_8);
            writer.write("Media Downloader diagnostic report\n");
            writer.write("Generated (UTC): " + utc(System.currentTimeMillis()) + "\n");
            try {
                PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
                writer.write("App version: " + info.versionName + " (" + info.getLongVersionCode() + ")\n");
            } catch (Exception error) { writer.write("App version: unavailable\n"); }
            writer.write("Package: " + context.getPackageName() + "\n");
            writer.write("Android: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n");
            writer.write("Device: " + DiagnosticsFormat.clean(Build.MANUFACTURER) + " " + DiagnosticsFormat.clean(Build.MODEL) + "\n");
            writer.write("ABIs: " + (Build.SUPPORTED_ABIS == null ? "unavailable" : String.join(", ", Build.SUPPORTED_ABIS)) + "\n");
            writer.write("Overlay permission: " + overlayStatus(context) + "\n");
            writer.write("Accessibility service: " + accessibilityStatus(context) + "\n");
            writer.write("Notifications: " + notificationStatus(context) + "\n");
            writer.write("Saved failure events: " + eventCount(context) + "\n\n");
            writer.write("Events (oldest first):\n");
            if (eventCount(context) == 0) writer.write("No failures have been recorded.\n");
            copy(file(context, PREVIOUS), writer);
            copy(file(context, CURRENT), writer);
            writer.flush();
        }
    }

    private static String overlayStatus(Context context) {
        try { return Settings.canDrawOverlays(context) ? "allowed" : "off"; }
        catch (RuntimeException error) { return "unavailable"; }
    }

    private static String accessibilityStatus(Context context) {
        try {
            String enabled = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            String service = context.getPackageName() + "/" + context.getPackageName() + ".OverlayAccessibilityService";
            return enabled != null && enabled.contains(service) ? "on" : "off";
        } catch (RuntimeException error) { return "unavailable"; }
    }

    private static String notificationStatus(Context context) {
        try {
            NotificationManager notifications = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            return notifications != null && notifications.areNotificationsEnabled() ? "allowed" : "off";
        } catch (RuntimeException error) { return "unavailable"; }
    }

    private static int count(File file) {
        int lines = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            while (reader.readLine() != null) lines++;
        } catch (Exception ignored) { /* Missing log means zero events. */ }
        return lines;
    }

    private static void copy(File file, OutputStreamWriter writer) throws Exception {
        if (!file.isFile()) return;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) writer.write(line + "\n");
        }
    }

    private static File file(Context context, String name) { return new File(context.getFilesDir(), name); }

    private static String utc(long millis) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(millis));
    }
}
