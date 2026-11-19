package com.codex.mediadownloader;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

/** Keeps the current UTC date and the six prior dates. Day eight expires together. */
final class DiagnosticsRetention {
    private DiagnosticsRetention() { }

    static boolean keep(String line, long nowMillis) {
        if (line == null || line.length() < 24) return false;
        try {
            LocalDate day = Instant.parse(line.substring(0, 24)).atOffset(ZoneOffset.UTC).toLocalDate();
            LocalDate today = Instant.ofEpochMilli(nowMillis).atOffset(ZoneOffset.UTC).toLocalDate();
            return !day.isBefore(today.minusDays(6)) && !day.isAfter(today);
        } catch (DateTimeParseException error) { return false; }
    }

    static long cutoffMillis(long nowMillis) {
        return Instant.ofEpochMilli(nowMillis).atOffset(ZoneOffset.UTC).toLocalDate()
            .minusDays(6).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
    }

    static long nextCleanupMillis(long nowMillis) {
        return Instant.ofEpochMilli(nowMillis).atOffset(ZoneOffset.UTC).toLocalDate()
            .plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
    }
}
