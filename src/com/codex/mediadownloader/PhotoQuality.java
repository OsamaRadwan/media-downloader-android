package com.codex.mediadownloader;

/** Resolution policy for choosing among source-listed image files. */
final class PhotoQuality {
    private PhotoQuality() { }

    static boolean better(int width, int height, long bytes,
                          int selectedWidth, int selectedHeight, long selectedBytes) {
        long pixels = (long) width * height;
        long selectedPixels = (long) selectedWidth * selectedHeight;
        return pixels > selectedPixels || (pixels == selectedPixels && bytes > selectedBytes);
    }

    static boolean matchesListed(int width, int height, int listedWidth, int listedHeight) {
        return listedWidth > 0 && listedHeight > 0 &&
            width >= listedWidth && height >= listedHeight;
    }

    static boolean matchesAspect(int width, int height, int listedWidth, int listedHeight) {
        if (listedWidth < 1 || listedHeight < 1) return true;
        if (width < 1 || height < 1) return false;
        double listed = (double) listedWidth / listedHeight;
        return Math.abs((double) width / height / listed - 1) <= 0.03 ||
            Math.abs((double) height / width / listed - 1) <= 0.03;
    }
}
