package com.codex.mediadownloader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

public final class DiagnosticsDailyLogTest {
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("diagnostic-days-");
        long first = Instant.parse("2026-11-14T12:00:00.000Z").toEpochMilli();
        try {
            for (int day = 0; day < 7; day++) {
                long now = first + day * 86_400_000L;
                DiagnosticsDailyLog.append(directory.toFile(), DiagnosticsDailyLog.FAILURE, now,
                    "2026-11-" + (14 + day) + "T12:00:00.000Z | failure\n", 1024);
            }
            check(DiagnosticsDailyLog.files(directory.toFile(), DiagnosticsDailyLog.FAILURE, first + 6 * 86_400_000L).size() == 7,
                "Each day's events must survive activity on later days");
            long eighth = first + 7 * 86_400_000L;
            DiagnosticsDailyLog.prune(directory.toFile(), eighth);
            check(DiagnosticsDailyLog.files(directory.toFile(), DiagnosticsDailyLog.FAILURE, eighth).size() == 6,
                "The first dated file must be deleted on day eight");
            check(!Files.exists(DiagnosticsDailyLog.file(directory.toFile(), DiagnosticsDailyLog.FAILURE, first).toPath()),
                "Only the expired day should be deleted");
            DiagnosticsDailyLog.append(directory.toFile(), DiagnosticsDailyLog.OPERATION, eighth,
                "2026-11-21T12:00:00.000Z | trace\n", 1);
            Path marker = directory.resolve("operation-events.2026-11-21.log.full");
            check(Files.exists(marker) && new String(Files.readAllBytes(marker), StandardCharsets.UTF_8)
                .contains("diagnostics.capacity"), "A full day must be reported, not silently omitted");
            DiagnosticsDailyLog.prune(directory.toFile(), eighth + 7 * 86_400_000L);
            check(!Files.exists(marker), "Capacity markers must expire with their day");
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.list(directory)) {
                paths.forEach(path -> { try { Files.deleteIfExists(path); } catch (Exception error) { throw new RuntimeException(error); } });
            }
            Files.deleteIfExists(directory);
        }
        System.out.println("DiagnosticsDailyLogTest passed");
    }

    private static void check(boolean result, String message) {
        if (!result) throw new AssertionError(message);
    }
}
