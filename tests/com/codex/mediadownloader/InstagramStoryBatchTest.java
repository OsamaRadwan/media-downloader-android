package com.codex.mediadownloader;

import java.io.IOException;
import java.util.List;

public final class InstagramStoryBatchTest {
    public static void main(String[] args) throws Exception {
        InstagramStoryBatch batch = new InstagramStoryBatch("Creator.Name", 2);
        check(batch.isSameAccount("creator.name"), "account match ignores case");
        check(!batch.isSameAccount("other"), "other account rejected");
        String first = "https://www.instagram.com/stories/Creator.Name/4039520695896226266/?igsh=private_token";
        batch.captured(first);
        batch.captured(first);
        check(batch.collectedCount() == 1 && !batch.isComplete(), "duplicate ID is not another Story");
        batch.captured("https://instagram.com/stories/creator.name/4039520695896226267/");
        check(batch.isComplete(), "two distinct Story IDs complete the visible pair");
        List<String> links = batch.links();
        check(links.size() == 2 && !links.get(0).contains("igsh") &&
            links.get(0).equals("https://www.instagram.com/stories/Creator.Name/4039520695896226266/"),
            "canonical links discard share tokens");
        InstagramStoryBatch middle = new InstagramStoryBatch("Creator.Name", 4);
        for (int item : new int[]{3, 4, 4, 3, 2}) {
            middle.captured("https://www.instagram.com/stories/Creator.Name/403952069589622626" + item + "/");
        }
        check(!middle.isComplete() && middle.collectedCount() == 3,
            "middle start stays incomplete until the first Story is seen");
        middle.captured("https://www.instagram.com/stories/Creator.Name/4039520695896226261/");
        check(middle.isComplete() && middle.links().size() == 4,
            "forward and backward scan collects all four distinct Stories");
        reject(() -> new InstagramStoryBatch("Creator", 0), "zero segments");
        reject(() -> new InstagramStoryBatch("Creator", 101), "excess segments");
        rejectIo(() -> new InstagramStoryBatch("Creator", 1).captured(
            "https://www.instagram.com/reel/Dd4vv7AxlzN/"), "linked Reel rejected");
        rejectIo(() -> new InstagramStoryBatch("Creator", 1).captured(
            "https://www.instagram.com/stories/other/4039520695896226266/"), "other account rejected");
        System.out.println("InstagramStoryBatchTest passed");
    }

    private interface Throwing { void run() throws Exception; }
    private static void reject(Throwing action, String message) throws Exception {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError(message);
    }
    private static void rejectIo(Throwing action, String message) throws Exception {
        try { action.run(); } catch (IOException expected) { return; }
        throw new AssertionError(message);
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
