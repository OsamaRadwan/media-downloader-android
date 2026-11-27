package com.codex.mediadownloader;

public final class InstagramAlbumPageTest {
    public static void main(String[] args) throws Exception {
        InstagramAlbumPage.requireComplete(20, -1, false); // Legacy embed without a total.
        InstagramAlbumPage.requireComplete(4, 4, false);
        rejects(3, 4, false, "reported fourth item missing");
        rejects(3, 3, true, "pagination still has another page");
        rejects(0, -1, false, "empty carousel");
        rejects(101, -1, false, "oversized carousel");
        System.out.println("Instagram album page tests passed");
    }

    private static void rejects(int received, int reported, boolean more, String caseName) {
        try { InstagramAlbumPage.requireComplete(received, reported, more); }
        catch (Exception expected) { return; }
        throw new AssertionError(caseName);
    }
}
