package com.codex.mediadownloader;

public final class DiagnosticsFormatTest {
    public static void main(String[] args) {
        String input = "HTTP 403 https://cdn.example/photo?x-signature=secret123 " +
            "/storage/emulated/0/Download/private.jpg account 7706415465383365909 token=abc123\nnext line";
        String clean = DiagnosticsFormat.clean(input);
        check(clean.contains("HTTP 403"), "Useful server status must remain");
        check(clean.contains("[url]") && clean.contains("[path]") && clean.contains("[id]"),
            "Source URLs, paths, and long IDs must be redacted");
        check(clean.contains("token=[redacted]"), "Token values must be redacted");
        check(!clean.contains("secret123") && !clean.contains("private.jpg") && !clean.contains("7706415465383365909"),
            "Sensitive input must not survive formatting");
        check(!clean.contains("\n"), "One failure must occupy one log line");
        check(DiagnosticsFormat.clean("Saved at content://media/external/downloads/12345").contains("[url]"),
            "Content-provider addresses must be redacted");
        Exception failure = new Exception("Missing class at C:\\Users\\Name\\file.txt");
        String described = DiagnosticsFormat.describe(failure);
        check(described.contains("java.lang.Exception") && described.contains("[path]"),
            "Exception type and redacted reason must remain");
        check(DiagnosticsFormat.publicPostUrl("https://vt.tiktok.com/ZShort123/?share_token=private")
            .equals("https://vt.tiktok.com/ZShort123/"), "Public post links must omit tracking queries");
        check(DiagnosticsFormat.publicPostUrl("https://www.instagram.com/reel/Df0DoVpmTBI/?igsh=private")
            .equals("https://www.instagram.com/reel/Df0DoVpmTBI/"), "Instagram links must omit tracking queries");
        check(DiagnosticsFormat.publicPostUrl("https://www.instagram.com/stories/nasa/4034039760237270159/?utm_source=private")
            .equals("https://www.instagram.com/stories/nasa/4034039760237270159/"),
            "Story links must retain the item ID and omit tracking queries");
        check(DiagnosticsFormat.publicPostUrl("https://www.rednote.com/discovery/item/6ae306f60000000007038cbf?xsec_token=private")
            .equals("https://www.rednote.com/discovery/item/6ae306f60000000007038cbf"),
            "RedNote diagnostics must omit share tokens");
        check(DiagnosticsFormat.publicPostUrl("https://xhslink.cn/o/6lqrKemnx7F?track=private")
            .equals("https://xhslink.cn/o/6lqrKemnx7F"),
            "RedNote short links retain the post route without tracking");
        check(DiagnosticsFormat.publicPostUrl("https://evil.example/video/123").equals("unavailable"),
            "Untrusted source links must not enter diagnostic reports");
        System.out.println("DiagnosticsFormatTest passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
