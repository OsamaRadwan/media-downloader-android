package com.codex.mediadownloader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class DownloadHistoryQueryTest {
    public static void main(String[] args) {
        List<DownloadItem> items = new ArrayList<>();
        for (int id = 65; id >= 1; id--) {
            boolean instagram = id == 47;
            boolean rednote = id == 3;
            boolean mixed = id == 46;
            String source = instagram || mixed ? "https://www.instagram.com/reel/Abcdef12345/" :
                rednote ? "https://www.rednote.com/explore/0123456789abcdef01234567" :
                "https://www.tiktok.com/@creator/video/7706415465383365909";
            List<DownloadItem.PhotoItem> photos = mixed ? Collections.singletonList(
                new DownloadItem.PhotoItem(2, 1080, 1920, 1080, 1920, 4096,
                    "mixed_video_002.mp4", "content://media/video/46", "", "video/mp4", "", true,
                    "video", "video/hevc", "audio/mp4a-latm", 7000)) :
                rednote ? Collections.singletonList(
                new DownloadItem.PhotoItem(1, 4284, 5712, 4284, 5712, 900000,
                    "full_photo_3.heic", "content://media/photo/3", "", "image/heic", "", true)) :
                Collections.emptyList();
            items.add(new DownloadItem(id, id, id, 0, 0, 100, 100, 0, 0, 1080, 1920, 0,
                "complete", "Complete", instagram ? "NASA" : mixed ? "MixedCreator" : rednote ? "Travel" : "Creator",
                "account" + id, "post" + id, source, "clip" + id + ".mp4", "", "content://media/" + id,
                "", "video/avc", "audio/mp4a-latm", rednote || mixed ? "album" : "video", source,
                instagram ? "Moon landing" : "", instagram ? "Space exploration" : "",
                instagram ? "#Moon" : "", photos, true));
        }
        DownloadRepository repository = new DownloadRepository() {
            @Override public List<DownloadItem> list() { return items; }
            @Override public void importExistingAsync() { }
        };
        DownloadsController controller = new DownloadsController(repository);
        DownloadsState first = controller.load("", 0, 0);
        check(first.items.size() == 15 && first.matchedCount == 65 && first.totalCount == 65,
            "First page must be bounded while totals cover the history");
        check(first.items.get(0).id == 65 && first.items.get(14).id == 51,
            "Newest-first order must be preserved");
        DownloadsState second = controller.load("", 1, 0);
        check(second.items.size() == 15 && second.items.get(0).id == 50 && second.pageIndex == 1,
            "Second page must contain the next bounded group");
        DownloadsState focused = controller.load("", 0, 3);
        check(focused.items.size() == 5 && focused.items.get(2).id == 3 && focused.pageIndex == 4,
            "Notification focus must reach a later page");
        DownloadsState matching = controller.load("moon NASA", 0, 0);
        check(matching.items.size() == 1 && matching.items.get(0).id == 47,
            "Search terms must match source account and metadata across the entire history");
        check(controller.load("CLIP3.MP4", 0, 0).items.get(0).id == 3,
            "Filename search must be case-insensitive");
        check(controller.load("rednote travel", 0, 0).items.get(0).id == 3,
            "RedNote account and source search must work");
        check(controller.load("full_photo_3.heic", 0, 0).items.get(0).id == 3,
            "Album photo filenames must be searchable");
        check(controller.load("mixed_video_002.mp4", 0, 0).items.get(0).id == 46,
            "Mixed album video filenames must be searchable");
        check(controller.load("hevc MixedCreator", 0, 0).items.get(0).id == 46,
            "Album video codec and account terms must combine in search");
        check("video".equals(controller.load("mixed_video_002.mp4", 0, 0).items.get(0).photos.get(0).kind),
            "Mixed album video type survives the Downloads snapshot");
        check(controller.load("no-such-post", 0, 0).matchedCount == 0,
            "Search must return an empty result without changing history totals");
        System.out.println("Download history query tests passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
