package com.codex.mediadownloader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

public final class DiagnosticsFilePrunerTest {
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("diagnostic-retention-");
        Path log = directory.resolve("events.log");
        long dayEight = Instant.parse("2026-11-21T00:00:00.000Z").toEpochMilli();
        try {
            Files.write(log, ("2026-11-14T23:59:59.000Z | expired\n" +
                "2026-11-15T00:00:00.000Z | retained\n").getBytes(StandardCharsets.UTF_8));
            DiagnosticsFilePruner.prune(log.toFile(), dayEight);
            String kept = new String(Files.readAllBytes(log), StandardCharsets.UTF_8);
            check(!kept.contains("expired") && kept.contains("retained"),
                "Day eight must remove its oldest day's lines while preserving newer lines");
            check(!Files.exists(directory.resolve("events.log.tmp")),
                "Successful pruning must remove its temporary file");
            DiagnosticsFilePruner.prune(log.toFile(),
                Instant.parse("2026-11-22T00:00:00.000Z").toEpochMilli());
            check(!Files.exists(log), "An entirely expired log file must be deleted");
        } finally {
            Files.deleteIfExists(log);
            Files.deleteIfExists(directory.resolve("events.log.tmp"));
            Files.deleteIfExists(directory);
        }
        System.out.println("DiagnosticsFilePrunerTest passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
