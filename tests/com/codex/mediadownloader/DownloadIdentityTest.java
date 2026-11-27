package com.codex.mediadownloader;

public final class DownloadIdentityTest {
    public static void main(String[] args) {
        String tiktok = "https://www.tiktok.com/@first/video/7712678052865510676?tracking=one";
        String renamed = "https://www.tiktok.com/@second/photo/7712678052865510676?tracking=two";
        check(DownloadIdentity.fromUrl(tiktok).equals(DownloadIdentity.fromUrl(renamed)), "TikTok identity");
        check(DownloadIdentity.matches(DownloadIdentity.fromUrl(tiktok), "", renamed, "", ""),
            "TikTok same post");
        check(DownloadIdentity.matches(DownloadIdentity.fromUrl(tiktok), "", "", "",
            "7712678052865510676"), "imported TikTok file identity");
        check(DownloadIdentity.fromUrl("https://www.instagram.com/reel/ABCdef123/?igsh=private")
            .equals(DownloadIdentity.fromUrl("https://instagram.com/p/ABCdef123/")), "Instagram shortcode");
        check(DownloadIdentity.fromUrl("https://www.rednote.com/explore/0123456789abcdef01234567?x=1")
            .equals(DownloadIdentity.fromUrl(
                "https://www.xiaohongshu.com/discovery/item/0123456789abcdef01234567")), "RedNote note");
        check(DownloadIdentity.fromUrl("https://vt.tiktok.com/ABC12/?q=1")
            .equals(DownloadIdentity.fromUrl("https://vt.tiktok.com/ABC12/?q=2")), "short link query");
        check(DownloadIdentity.matches("", "instagram:id:12345",
            "https://www.instagram.com/reel/ABCdef123/", "", "12345"), "resolved Instagram ID");
        check(!DownloadIdentity.matches(DownloadIdentity.fromUrl(tiktok), "", "", "", ""),
            "unknown post");
        check(!DownloadIdentity.fromUrl(tiktok).equals(DownloadIdentity.fromUrl(
            "https://www.instagram.com/p/ABCdef123/")), "platform separation");
        System.out.println("Download identity tests passed");
    }

    private static void check(boolean value, String caseName) {
        if (!value) throw new AssertionError(caseName);
    }
}
