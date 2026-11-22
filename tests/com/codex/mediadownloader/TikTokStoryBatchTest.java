package com.codex.mediadownloader;

import java.util.List;
import java.util.Arrays;

public final class TikTokStoryBatchTest {
    public static void main(String[] args) {
        TikTokStoryBatch batch = new TikTokStoryBatch("Creator", Arrays.asList(100, 300, 500));
        check(batch.isSameTitle("Creator"), "same account title");
        check(!batch.isSameTitle("Other"), "different account title");
        check(!batch.hasStories(), "initially empty");
        batch.captured("https://vm.tiktok.com/first");
        batch.captured("https://vm.tiktok.com/second");
        batch.captured("https://vm.tiktok.com/third");
        List<String> links = batch.links();
        check(batch.hasStories(), "captured Stories");
        check(batch.segmentCount() == 3, "visible segment count");
        check(links.size() == 3, "three Stories");
        check(links.get(0).endsWith("first") && links.get(2).endsWith("third"), "screen order");
        check(batch.links().size() == 3, "returned list is independent");
        System.out.println("TikTokStoryBatchTest passed");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
