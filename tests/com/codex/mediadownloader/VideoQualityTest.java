package com.codex.mediadownloader;

public final class VideoQualityTest {
    public static void main(String[] args) {
        check(VideoQuality.better("instagram", "low-listed", 1080, 1920, 7_000_000,
            "high-listed", 720, 1280, 10_000_000),
            "A lower source label must not hide the larger decoded Instagram file");
        check(!VideoQuality.better("instagram", "high-listed", 720, 1280, 10_000_000,
            "low-listed", 1080, 1920, 7_000_000),
            "A higher source label must not replace the larger decoded file");
        check(VideoQuality.better("tiktok", "wide", 1280, 720, 4_000_000,
            "portrait", 1080, 1920, 8_000_000),
            "A decoded wide TikTok view has priority over a portrait rendition");
        check(!VideoQuality.better("tiktok", "wide-listed-but-portrait", 1080, 1920, 8_000_000,
            "wide", 1280, 720, 4_000_000),
            "The actual TikTok orientation controls wide-view priority");
        check(VideoQuality.better("rednote", "origin", 1080, 1920, 4_000_000,
            "clean", 2160, 3840, 8_000_000),
            "RedNote's verified original asset retains priority");
        check(!VideoQuality.better("rednote", "clean", 2160, 3840, 8_000_000,
            "origin", 1080, 1920, 4_000_000),
            "A clean rendition must not displace the original asset");
        check(VideoQuality.better("instagram", "same-size", 720, 1280, 7_000_000,
            "same-size", 720, 1280, 6_000_000),
            "A larger complete file wins when decoded dimensions tie");
        System.out.println("VideoQualityTest passed");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
