package com.codex.mediadownloader;

public final class SharedPostLinkTest {
    public static void main(String[] args) {
        check("https://vm.tiktok.com/ZM123abc/".equals(SharedPostLink.fromText(
            "Watch this https://vm.tiktok.com/ZM123abc/ copied from TikTok")), "TikTok share");
        check("https://www.instagram.com/reel/ABC123xyz/?igsh=example".equals(SharedPostLink.fromText(
            "https://www.instagram.com/reel/ABC123xyz/?igsh=example")), "Instagram Reel share");
        check("https://xhslink.cn/o/ABC123def456".equals(SharedPostLink.fromText(
            "RedNote: https://xhslink.cn/o/ABC123def456.")), "RedNote share");
        check(SharedPostLink.fromText("https://tiktok.com.evil.example/post") == null,
            "lookalike host rejected");
        check(SharedPostLink.fromText("http://www.tiktok.com/@a/video/123") == null,
            "plain HTTP rejected");
        check(SharedPostLink.fromText("https://vm.tiktok.com/a https://vm.tiktok.com/b") == null,
            "ambiguous share rejected");
        System.out.println("SharedPostLinkTest passed");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
