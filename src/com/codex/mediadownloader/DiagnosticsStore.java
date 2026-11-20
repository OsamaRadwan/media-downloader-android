package com.codex.mediadownloader;

import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.StatFs;
import android.provider.Settings;
import android.util.Log;
import android.util.DisplayMetrics;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Collections;
import java.util.Locale;
import java.util.TimeZone;
import java.util.List;

/** App-private, age-limited diagnostic rings. It never reads system logcat. */
final class DiagnosticsStore {
    private static final Object LOCK = new Object();
    private static final String CURRENT = "failure-events.log";
    private static final String PREVIOUS = "failure-events.previous.log";
    private static final String TRACE_CURRENT = "operation-events.log";
    private static final String TRACE_PREVIOUS = "operation-events.previous.log";
    // These names are read until their seven-day contents age out after the daily-log migration.
    private static long lastPruneAt = Long.MIN_VALUE;

    private DiagnosticsStore() { }

    static void record(Context context, String component, Throwable error) {
        try { append(context, DiagnosticsDailyLog.FAILURE, DiagnosticsDailyLog.FAILURE_DAY_LIMIT,
            component, DiagnosticsFormat.describe(error)); }
        catch (Throwable ignored) { /* Diagnostics must never replace the original failure. */ }
    }

    static void record(Context context, String component, String message) {
        try { append(context, DiagnosticsDailyLog.FAILURE, DiagnosticsDailyLog.FAILURE_DAY_LIMIT,
            component, DiagnosticsFormat.clean(message)); }
        catch (Throwable ignored) { /* Diagnostics must never replace the original failure. */ }
    }

    static void trace(Context context, String component, String message) {
        try { append(context, DiagnosticsDailyLog.OPERATION, DiagnosticsDailyLog.OPERATION_DAY_LIMIT,
            component, DiagnosticsFormat.clean(message)); }
        catch (Throwable ignored) { /* Tracing must never alter a download. */ }
    }

    static void prune(Context context) {
        try { synchronized (LOCK) { pruneLocked(context, System.currentTimeMillis(), true); } }
        catch (Throwable ignored) { /* Storage cleanup is best effort. */ }
    }

    private static void append(Context context, String prefix,
                               long maxFileBytes, String component, String detail) {
        try {
            long now = System.currentTimeMillis();
            String line = utc(now) + " | " + DiagnosticsFormat.clean(component) +
                " | " + detail + "\n";
            synchronized (LOCK) {
                pruneLocked(context, now, false);
                DiagnosticsDailyLog.append(context.getFilesDir(), prefix, now, line, maxFileBytes);
            }
        } catch (Throwable storageFailure) {
            Log.e("MediaDownloader", "Could not persist diagnostic event", storageFailure);
        }
    }

    static int eventCount(Context context) {
        synchronized (LOCK) {
            try { pruneLocked(context, System.currentTimeMillis(), true); }
            catch (Exception error) { Log.w("MediaDownloader", "Could not prune diagnostics", error); }
            return countFiles(context, DiagnosticsDailyLog.FAILURE, PREVIOUS, CURRENT);
        }
    }

    static void export(Context context, OutputStream output) throws Exception {
        List<DownloadStore.Entry> jobs;
        try { jobs = DownloadStore.list(context); }
        catch (RuntimeException error) {
            record(context, "diagnostics.history_snapshot", error);
            jobs = Collections.emptyList();
        }
        synchronized (LOCK) {
            pruneLocked(context, System.currentTimeMillis(), true);
            OutputStreamWriter writer = new OutputStreamWriter(output, StandardCharsets.UTF_8);
            writer.write("Media Downloader diagnostic report\n");
            writer.write("Generated (UTC): " + utc(System.currentTimeMillis()) + "\n");
            writer.write("Retention: current UTC day plus six prior days; daily limits: 512 KiB failures, 256 KiB operations\n");
            writer.write("Report may contain public post links with query strings removed. Share it only when you choose.\n");
            try {
                PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
                writer.write("App version: " + info.versionName + " (" + info.getLongVersionCode() + ")\n");
            } catch (Exception error) { writer.write("App version: unavailable\n"); }
            writer.write("Package: " + context.getPackageName() + "\n");
            writer.write("Android: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n");
            writer.write("Device: " + DiagnosticsFormat.clean(Build.MANUFACTURER) + " " + DiagnosticsFormat.clean(Build.MODEL) + "\n");
            writer.write("ABIs: " + (Build.SUPPORTED_ABIS == null ? "unavailable" : String.join(", ", Build.SUPPORTED_ABIS)) + "\n");
            writer.write("Build fingerprint: " + DiagnosticsFormat.clean(Build.FINGERPRINT) + "\n");
            writer.write("Security patch: " + Build.VERSION.SECURITY_PATCH + "\n");
            writer.write("Locale/time zone: " + Locale.getDefault().toLanguageTag() + " / " + TimeZone.getDefault().getID() + "\n");
            writer.write("TikTok version: " + sourceAppVersion(context, "com.zhiliaoapp.musically") + "\n");
            writer.write("Instagram version: " + sourceAppVersion(context, "com.instagram.android") + "\n");
            writer.write("Display: " + displayStatus(context) + "\n");
            writer.write("Network: " + networkStatus(context) + "\n");
            writer.write("Private storage available: " + storageStatus(context) + "\n");
            writer.write("Overlay permission: " + overlayStatus(context) + "\n");
            writer.write("Accessibility service: " + accessibilityStatus(context) + "\n");
            writer.write("Notifications: " + notificationStatus(context) + "\n");
            writer.write("Button settings: " + buttonStatus(context) + "\n");
            int failures = countFiles(context, DiagnosticsDailyLog.FAILURE, PREVIOUS, CURRENT);
            int traces = countFiles(context, DiagnosticsDailyLog.OPERATION, TRACE_PREVIOUS, TRACE_CURRENT);
            writer.write("Saved failure events: " + failures + "; operation events: " + traces + "\n\n");
            writeJobs(writer, jobs);
            writer.write("Failure events (legacy logs, then daily dates):\n");
            if (failures == 0) writer.write("No failures have been recorded.\n");
            copyFiles(context, writer, DiagnosticsDailyLog.FAILURE, PREVIOUS, CURRENT);
            writer.write("\nOperation trace (legacy logs, then daily dates):\n");
            if (traces == 0) writer.write("No operation events have been recorded.\n");
            copyFiles(context, writer, DiagnosticsDailyLog.OPERATION, TRACE_PREVIOUS, TRACE_CURRENT);
            writer.flush();
        }
    }

    private static void pruneLocked(Context context, long now, boolean force) throws Exception {
        if (!force && lastPruneAt != Long.MIN_VALUE && now >= lastPruneAt &&
            now < DiagnosticsRetention.nextCleanupMillis(lastPruneAt)) return;
        DiagnosticsFilePruner.prune(file(context, PREVIOUS), now);
        DiagnosticsFilePruner.prune(file(context, CURRENT), now);
        DiagnosticsFilePruner.prune(file(context, TRACE_PREVIOUS), now);
        DiagnosticsFilePruner.prune(file(context, TRACE_CURRENT), now);
        DiagnosticsDailyLog.prune(context.getFilesDir(), now);
        lastPruneAt = now;
    }

    private static int countFiles(Context context, String prefix, String legacyPrevious, String legacyCurrent) {
        int lines = count(file(context, legacyPrevious)) + count(file(context, legacyCurrent));
        for (File daily : DiagnosticsDailyLog.files(context.getFilesDir(), prefix, System.currentTimeMillis()))
            lines += count(daily);
        return lines;
    }

    private static void copyFiles(Context context, OutputStreamWriter writer, String prefix,
                                  String legacyPrevious, String legacyCurrent) throws Exception {
        copy(file(context, legacyPrevious), writer);
        copy(file(context, legacyCurrent), writer);
        for (File daily : DiagnosticsDailyLog.files(context.getFilesDir(), prefix, System.currentTimeMillis()))
            copy(daily, writer);
    }

    private static void writeJobs(OutputStreamWriter writer, List<DownloadStore.Entry> jobs) throws Exception {
        writer.write("Recent active or unsuccessful jobs (up to 20, newest first):\n");
        long cutoff = DiagnosticsRetention.cutoffMillis(System.currentTimeMillis());
        int shown = 0;
        for (DownloadStore.Entry job : jobs) {
            if (shown >= 20) break;
            if (!job.isActive() && !"failed".equals(job.state) && !"partial".equals(job.state) &&
                !"interrupted".equals(job.state)) continue;
            if (Math.max(job.createdAt, Math.max(job.updatedAt, job.finishedAt)) < cutoff) continue;
            shown++;
            String source = DiagnosticsFormat.publicPostUrl(job.sourceUrl);
            if (source.equals("unavailable")) source = DiagnosticsFormat.publicPostUrl(job.shareUrl);
            writer.write("Job " + job.id + ": " + DiagnosticsFormat.clean(job.mediaType) +
                " state=" + DiagnosticsFormat.clean(job.state) +
                " stage=" + DiagnosticsFormat.clean(job.stage) +
                " created=" + utc(job.createdAt) +
                " finished=" + (job.finishedAt == 0 ? "n/a" : utc(job.finishedAt)) + "\n");
            writer.write("  Public source: " + source + "\n");
            writer.write("  Stream option=" + job.streamOption + " listed size=" + job.width + "x" + job.height +
                " bytes=" + job.downloadedBytes + "/" + job.totalBytes +
                " video=" + DiagnosticsFormat.clean(job.videoMime) +
                " audio=" + DiagnosticsFormat.clean(job.audioMime) + "\n");
            if (!job.error.isEmpty()) writer.write("  Error: " + DiagnosticsFormat.clean(job.error) + "\n");
            if (!job.photos.isEmpty()) {
                int saved = 0, failed = 0;
                for (DownloadStore.Entry.Photo photo : job.photos) {
                    if (!photo.savedUri.isEmpty()) saved++;
                    if (!photo.error.isEmpty()) failed++;
                }
                writer.write("  Photos: " + saved + " saved, " + failed + " with errors, " + job.photos.size() + " total\n");
                int details = 0;
                for (DownloadStore.Entry.Photo photo : job.photos) {
                    if (photo.error.isEmpty() || details++ >= 10) continue;
                    writer.write("  Photo " + photo.index + " listed=" + photo.sourceWidth + "x" + photo.sourceHeight +
                        " actual=" + photo.width + "x" + photo.height +
                        " error=" + DiagnosticsFormat.clean(photo.error) + "\n");
                }
            }
        }
        if (shown == 0) writer.write("No recent active or unsuccessful jobs.\n");
        writer.write("\n");
    }

    private static String sourceAppVersion(Context context, String packageName) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(packageName, 0);
            return DiagnosticsFormat.clean(info.versionName) + " (" + info.getLongVersionCode() + ")";
        } catch (Exception error) { return "unavailable"; }
    }

    private static String displayStatus(Context context) {
        try {
            DisplayMetrics screen = context.getResources().getDisplayMetrics();
            return screen.widthPixels + "x" + screen.heightPixels + " pixels, " + screen.densityDpi + " dpi";
        } catch (RuntimeException error) { return "unavailable"; }
    }

    private static String networkStatus(Context context) {
        try {
            ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager == null || manager.getActiveNetwork() == null) return "disconnected";
            NetworkCapabilities capabilities = manager.getNetworkCapabilities(manager.getActiveNetwork());
            if (capabilities == null) return "unavailable";
            String transport = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ? "VPN" :
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "Wi-Fi" :
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? "cellular" :
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? "Ethernet" : "other";
            return transport + ", validated=" + capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) +
                ", metered=" + manager.isActiveNetworkMetered();
        } catch (RuntimeException error) { return "unavailable"; }
    }

    private static String storageStatus(Context context) {
        try { return new StatFs(context.getFilesDir().getAbsolutePath()).getAvailableBytes() + " bytes"; }
        catch (RuntimeException error) { return "unavailable"; }
    }

    private static String buttonStatus(Context context) {
        try {
            OverlayPreferences prefs = new SharedPreferencesOverlayPreferences(context);
            StringBuilder details = new StringBuilder();
            for (OverlayOption option : OverlayOption.values()) {
                if (details.length() > 0) details.append(", ");
                details.append(option.key).append('=');
                int value = prefs.get(option);
                details.append(option.color ? String.format(Locale.US, "#%08X", value) : value);
            }
            String custom = prefs.selectedCustomIconId();
            details.append(", custom_icon_selected=").append(!custom.isEmpty());
            if (!custom.isEmpty()) details.append(", custom_icon_exists=").append(new CustomIconStore(context).exists(custom));
            return details.toString();
        } catch (RuntimeException error) { return "unavailable"; }
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
