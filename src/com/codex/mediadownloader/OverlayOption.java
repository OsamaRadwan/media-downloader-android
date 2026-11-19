package com.codex.mediadownloader;

import android.graphics.Color;

/** Typed, bounded appearance options. */
enum OverlayOption {
    SIDE("side", 1, 0, 1),
    HEIGHT("height_percent", 50, 10, 90),
    SIDE_GAP("side_gap_dp", 0, 0, 120),
    SIZE("size_dp", 48, 40, 96),
    PADDING("padding_dp", 8, 0, 24),
    ICON_STYLE("icon_style", 0, 0, 4),
    ARROW_COLOR("arrow_color", Color.WHITE, true),
    ARROW_OPACITY("arrow_opacity", 100, 20, 100),
    BACKGROUND_STYLE("background_style", 0, 0, 2),
    BACKGROUND_COLOR("background_color", 0xff0bb99a, true),
    BACKGROUND_OPACITY("background_opacity", 85, 0, 100);

    final String key;
    final int fallback, min, max;
    final boolean color;

    OverlayOption(String key, int fallback, int min, int max) {
        this.key = key; this.fallback = fallback; this.min = min; this.max = max; this.color = false;
    }

    OverlayOption(String key, int fallback, boolean color) {
        this.key = key; this.fallback = fallback; this.min = 0; this.max = 0; this.color = color;
    }

    int validate(int value) {
        return color ? 0xff000000 | (value & 0x00ffffff) : Math.max(min, Math.min(max, value));
    }
}
