package com.codex.mediadownloader;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Dated app-private logs keep one day's entries independent of other days. */
final class DiagnosticsDailyLog {
    static final String FAILURE = "failure-events.";
    static final String OPERATION = "operation-events.";
    static final long FAILURE_DAY_LIMIT = 512 * 1024;
    static final long OPERATION_DAY_LIMIT = 256 * 1024;

    private DiagnosticsDailyLog() { }

    static File file(File directory, String prefix, long now) {
        LocalDate day = Instant.ofEpochMilli(now).atOffset(ZoneOffset.UTC).toLocalDate();
        return new File(directory, prefix + day + ".log");
    }

    static List<File> files(File directory, String prefix, long now) {
        List<File> result = new ArrayList<>();
        File[] entries = directory.listFiles();
        if (entries == null) return result;
        for (File entry : entries) {
            if (entry.isFile() && date(entry.getName(), prefix) != null) result.add(entry);
        }
        result.sort(Comparator.comparing(File::getName));
        return result;
    }

    static void prune(File directory, long now) throws Exception {
        File[] entries = directory.listFiles();
        if (entries == null) return;
        LocalDate today = Instant.ofEpochMilli(now).atOffset(ZoneOffset.UTC).toLocalDate();
        LocalDate oldest = today.minusDays(6);
        for (File entry : entries) {
            if (!entry.isFile()) continue;
            LocalDate day = date(entry.getName(), FAILURE);
            if (day == null) day = date(entry.getName(), OPERATION);
            if (day == null) continue;
            if (day.isBefore(oldest) || day.isAfter(today)) {
                if (!entry.delete()) throw new Exception("Could not remove expired daily diagnostic log");
            } else DiagnosticsFilePruner.prune(entry, now);
        }
    }

    static void append(File directory, String prefix, long now, String line, long maxBytes) throws Exception {
        File target = file(directory, prefix, now);
        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
        if (target.length() + bytes.length > maxBytes) {
            // Keep the date partition and its earlier evidence; mark one truncation event.
            File marker = new File(directory, target.getName() + ".full");
            if (!marker.exists()) {
                try (FileOutputStream output = new FileOutputStream(marker)) {
                    output.write((line.substring(0, Math.min(24, line.length())) +
                        " | diagnostics.capacity | Daily log reached its size limit; later events were omitted\n")
                        .getBytes(StandardCharsets.UTF_8));
                    output.getFD().sync();
                }
            }
            return;
        }
        try (FileOutputStream output = new FileOutputStream(target, true)) {
            output.write(bytes);
            output.getFD().sync();
        }
    }

    private static LocalDate date(String name, String prefix) {
        String suffix = name.endsWith(".log.full") ? ".log.full" : ".log";
        if (!name.startsWith(prefix) || !name.endsWith(suffix) ||
            name.length() != prefix.length() + 10 + suffix.length()) return null;
        try { return LocalDate.parse(name.substring(prefix.length(), prefix.length() + 10)); }
        catch (RuntimeException error) { return null; }
    }
}
