package com.codex.mediadownloader;

public final class DownloadRetryTest {
    public static void main(String[] args) {
        String url = "https://www.tiktok.com/@account/video/1234567890123456789";
        check(DownloadRetry.allowed("failed", "video", 0, 0, false, url, "", ""), "failed video");
        check(DownloadRetry.allowed("failed", "video", 0, 0, false, "", url, ""), "source URL fallback");
        check(DownloadRetry.allowed("failed", "album", 0, 0, false, url, "", ""),
            "album failed before photo list");
        check(DownloadRetry.allowed("partial", "album", 2, 3, false, url, "", ""),
            "missing album photo");
        check(!DownloadRetry.allowed("partial", "album", 3, 3, false, url, "", ""),
            "complete photo list");
        check(!DownloadRetry.allowed("failed", "video", 0, 0, false, "", "", ""),
            "missing retry link");
        check(!DownloadRetry.allowed("queued", "video", 0, 0, false, url, "", ""), "active job");
        check(!DownloadRetry.allowed("complete", "video", 0, 0, true, url, "", ""),
            "completed video");
        check(DownloadRetry.allowed("complete", "video", 0, 0, false, url, "", ""),
            "missing completed video");
        check(!DownloadRetry.allowed("complete", "video", 0, 0, true, url, "", ""),
            "video still on phone");
        check(DownloadRetry.allowed("complete", "album", 2, 3, false, url, "", ""),
            "missing completed album photo");
        check(DownloadRetry.allowed("complete", "album", 0, 0, false, url, "", ""),
            "empty completed album record");
        check(!DownloadRetry.allowed("complete", "album", 3, 3, false, url, "", ""),
            "album still on phone");
        check(!DownloadRetry.allowed("failed", "video", 0, 0, true, url, "", ""),
            "failed record with valid file");
        check(DownloadRetry.url("", "", "7712678052865510676")
            .contains("7712678052865510676"), "old TikTok file link fallback");
        String redNoteShort = "https://xhslink.cn/o/AbC123456";
        String redNotePost = "https://www.rednote.com/discovery/item/6b08eded00000000260129da";
        check(DownloadRetry.url(redNoteShort, redNotePost).equals(redNoteShort),
            "RedNote retries use the working short link");
        check(DownloadRetry.url("https://xhslink.cn.evil.example/o/AbC123456", redNotePost)
            .equals(redNotePost), "untrusted short-link host rejected");
        check(DownloadRetry.url("https://www.instagram.com/p/Abc123/extra", redNotePost)
            .equals(redNotePost), "other source links retain the canonical preference");
        check(DownloadRetry.retryUrl(redNotePost, redNotePost,
            "6b08eded00000000260129da", redNoteShort).equals(redNoteShort),
            "fresh RedNote short link repairs an older retry record");
        check(DownloadRetry.retryUrl("", "https://www.instagram.com/p/Abc123/",
            "4022430930199578186", redNoteShort).equals("https://www.instagram.com/p/Abc123/"),
            "fresh RedNote link cannot replace another platform's record");
        check(DownloadRetry.blockedByOther(true, true, true), "active matching transfer still blocks retry");
        check(DownloadRetry.blockedByOther(false, true, false),
            "complete matching post blocks a fresh duplicate");
        check(!DownloadRetry.blockedByOther(false, true, true),
            "explicit retry may restore its own recorded missing file");
        System.out.println("Download retry policy passed");
    }

    private static void check(boolean value, String caseName) {
        if (!value) throw new AssertionError(caseName);
    }
}
