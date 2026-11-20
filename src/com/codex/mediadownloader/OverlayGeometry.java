package com.codex.mediadownloader;

/** Pixel coordinates for a button beside a letterboxed landscape video. */
final class OverlayGeometry {
    private OverlayGeometry() { }

    static int horizontal(int screenWidth, int videoLeft, int videoRight,
                          int buttonWidth, int gap, boolean right) {
        int start;
        if (right) {
            int outerSpace = screenWidth - videoRight;
            start = outerSpace >= buttonWidth + gap
                ? videoRight + gap : videoRight - buttonWidth - gap;
        } else {
            start = videoLeft >= buttonWidth + gap
                ? videoLeft - buttonWidth - gap : videoLeft + gap;
        }
        return clamp(start, 0, screenWidth - buttonWidth);
    }

    static int vertical(int screenHeight, int videoTop, int videoBottom,
                        int buttonHeight, int percent) {
        int center = videoTop + (videoBottom - videoTop) * percent / 100;
        int start = center - buttonHeight / 2;
        int videoMaximum = Math.max(videoTop, videoBottom - buttonHeight);
        return clamp(clamp(start, videoTop, videoMaximum), 0, screenHeight - buttonHeight);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(Math.max(minimum, maximum), value));
    }
}
