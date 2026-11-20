package com.codex.mediadownloader;

/** Each source view owns independent border placement while sharing the arrow style. */
enum OverlayPlacement {
    TIKTOK(OverlayOption.SIDE, OverlayOption.HEIGHT, OverlayOption.SIDE_GAP),
    INSTAGRAM_FEED(OverlayOption.INSTAGRAM_FEED_SIDE, OverlayOption.INSTAGRAM_FEED_HEIGHT,
        OverlayOption.INSTAGRAM_FEED_GAP),
    INSTAGRAM_REEL(OverlayOption.INSTAGRAM_REEL_SIDE, OverlayOption.INSTAGRAM_REEL_HEIGHT,
        OverlayOption.INSTAGRAM_REEL_GAP);

    final OverlayOption side, height, gap;
    OverlayPlacement(OverlayOption side, OverlayOption height, OverlayOption gap) {
        this.side = side; this.height = height; this.gap = gap;
    }
}
