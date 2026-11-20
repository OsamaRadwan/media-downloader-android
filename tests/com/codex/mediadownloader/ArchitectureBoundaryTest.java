package com.codex.mediadownloader;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class ArchitectureBoundaryTest {
    public static void main(String[] args) {
        DownloadItem queued = item(1, "queued");
        DownloadItem complete = item(2, "complete");
        DownloadItem failed = item(3, "failed");
        List<DownloadItem> records = new ArrayList<>(Arrays.asList(queued, complete, failed));
        DownloadRepository fake = new DownloadRepository() {
            @Override public List<DownloadItem> list() { return records; }
            @Override public void importExistingAsync() { }
        };
        DownloadsState state = new DownloadsController(fake).load();
        check(state.activeCount == 1 && state.completeCount == 1 && state.failedCount == 1,
            "Each status must have the correct count");
        records.clear();
        check(state.items.size() == 3, "UI snapshot must not change with repository list");
        try {
            state.items.clear();
            throw new AssertionError("UI snapshot must be immutable");
        } catch (UnsupportedOperationException expected) { }
        check(OverlayOption.SIZE.validate(5) == 40, "Size lower bound");
        check(OverlayOption.SIZE.validate(200) == 96, "Size upper bound");
        check(OverlayOption.ICON_STYLE.validate(-1) == 0, "Unknown icon falls back to solid");
        check(OverlayOption.ICON_STYLE.validate(4) == 4, "Custom icon style is available");
        check(OverlayOption.ICON_STYLE.validate(99) == 4, "Icon style is bounded");
        check(OverlayOption.ARROW_COLOR.validate(0x00112233) == 0xff112233, "Opaque stored color");
        check(item(4, "complete", "7708810139658128647", "").tiktokUrl().equals(
            "https://www.tiktok.com/@creator/video/7708810139658128647"), "Creator and video ID open the matching post");
        check(item(5, "failed", "", "https://vt.tiktok.com/example").tiktokUrl().equals(
            "https://vt.tiktok.com/example"), "Shared TikTok link remains available for failed jobs");
        check(item(6, "failed", "", "https://tiktok.com.evil.example/video/123").tiktokUrl().isEmpty(),
            "Untrusted links must not be opened from download history");
        DownloadItem rednote = item(8, "failed", "", "https://xhslink.cn/o/6lqrKemnx7F");
        check(rednote.fromRedNote() && rednote.postUrl().equals("https://xhslink.cn/o/6lqrKemnx7F"),
            "Failed RedNote jobs retain their validated source link");
        check(!item(9, "failed", "", "https://xhslink.cn.evil.example/o/6lqrKemnx7F").fromRedNote(),
            "A lookalike RedNote host is rejected");
        DownloadItem album = new DownloadItem(7, 1, 1, 0, 0, 0, -1, 0, 0, 0, 0, 0,
            "partial", "partial", "creator", "", "7706415465383365909", "", "", "", "", "", "", "",
            "album", "", "", "", "", java.util.Collections.emptyList());
        check(!album.isActive(), "A partial album is terminal until retried");
        check(album.tiktokUrl().equals("https://www.tiktok.com/@creator/photo/7706415465383365909"),
            "Album link opens the matching photo post");
        check(PhotoQuality.better(2160, 3840, 100000, 1080, 1920, 200000),
            "A higher resolution wins even when its file is smaller");
        check(PhotoQuality.better(1080, 1920, 200000, 1080, 1920, 100000),
            "For equal dimensions the larger source file wins");
        check(!PhotoQuality.better(720, 1280, 900000, 1080, 1920, 100000),
            "A larger byte count cannot outweigh lower resolution");
        check(PhotoQuality.matchesListed(1080, 2140, 1080, 2140),
            "The decoded image must match TikTok's listed dimensions");
        System.out.println("ArchitectureBoundaryTest passed");
    }

    private static DownloadItem item(int id, String state) {
        return item(id, state, "", "");
    }

    private static DownloadItem item(int id, String state, String videoId, String shareUrl) {
        return new DownloadItem(id, 1, 1, 0, 0, 0, -1, 0, 0, 0, 0, 0,
            state, state, "creator", "", videoId, shareUrl, "", "", "", "", "", "");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
