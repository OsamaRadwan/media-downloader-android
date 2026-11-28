package com.codex.mediadownloader;

public final class TikTokCreatorProfileTest {
    public static void main(String[] args) {
        String id = "7699455252495469845";
        check("lostcandy.cosfc".equals(StreamResolver.creatorHandleFromPostUrl(
            "https://www.tiktok.com/@lostcandy.cosfc/video/" + id, id)), "exact creator post link");
        check(StreamResolver.creatorHandleFromPostUrl(
            "https://www.tiktok.com/@lostcandy.cosfc/video/" + id, "7699455252495469844").isEmpty(),
            "reject a different post");
        check(StreamResolver.creatorHandleFromPostUrl(
            "https://example.com/@lostcandy.cosfc/video/" + id, id).isEmpty(), "reject another host");
        check(StreamResolver.creatorHandleFromPostUrl(
            "https://www.tiktok.com/@bad%2Fpath/video/" + id, id).isEmpty(), "reject an unsafe handle");
        check(StreamResolver.creatorHandleFromPostUrl(
            "https://www.tiktok.com/@_/video/" + id, id).isEmpty(), "reject anonymous placeholder");
        System.out.println("TikTokCreatorProfileTest passed");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
