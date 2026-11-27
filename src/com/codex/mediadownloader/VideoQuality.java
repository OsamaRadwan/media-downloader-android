package com.codex.mediadownloader;

/** Compares verified MP4 files using their decoded dimensions, not source labels. */
final class VideoQuality {
    private VideoQuality() { }

    static boolean better(String platform, String variant, int width, int height, long bytes,
                          String selectedVariant, int selectedWidth, int selectedHeight,
                          long selectedBytes) {
        if (selectedWidth < 1 || selectedHeight < 1) return true;
        if ("rednote".equals(platform)) {
            boolean original = "origin".equals(variant);
            boolean selectedOriginal = "origin".equals(selectedVariant);
            if (original != selectedOriginal) return original;
        }
        if ("tiktok".equals(platform)) {
            boolean wide = width > height;
            boolean selectedWide = selectedWidth > selectedHeight;
            if (wide != selectedWide) return wide;
        }
        long pixels = (long) width * height;
        long selectedPixels = (long) selectedWidth * selectedHeight;
        return pixels > selectedPixels || pixels == selectedPixels && bytes > selectedBytes;
    }
}
