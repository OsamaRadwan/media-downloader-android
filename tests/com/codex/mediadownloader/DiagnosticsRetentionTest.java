package com.codex.mediadownloader;

import java.time.Instant;

public final class DiagnosticsRetentionTest {
    public static void main(String[] args) {
        long now = Instant.parse("2026-11-20T12:00:00.000Z").toEpochMilli();
        check(DiagnosticsRetention.keep("2026-11-20T11:59:59.000Z | failure", now),
            "Recent events must remain");
        check(DiagnosticsRetention.keep("2026-11-14T00:00:00.000Z | failure", now),
            "The seventh UTC calendar day must remain");
        check(!DiagnosticsRetention.keep("2026-11-13T23:59:59.999Z | failure", now),
            "The eighth UTC calendar day must expire together");
        check(!DiagnosticsRetention.keep("malformed event", now),
            "Malformed events must not occupy the ring indefinitely");
        check(!DiagnosticsRetention.keep("2026-11-22T12:00:01.000Z | future", now),
            "Clock-skewed future events must not persist indefinitely");
        long nextDay = Instant.parse("2026-11-21T00:00:00.000Z").toEpochMilli();
        check(!DiagnosticsRetention.keep("2026-11-14T23:59:59.999Z | failure", nextDay),
            "The oldest day's entries must disappear at the next UTC midnight");
        check(DiagnosticsRetention.nextCleanupMillis(now) == nextDay,
            "Cleanup must be scheduled for the next UTC day");
        System.out.println("DiagnosticsRetentionTest passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
