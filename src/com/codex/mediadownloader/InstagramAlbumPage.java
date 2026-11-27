package com.codex.mediadownloader;

/** Rejects a public carousel response when it explicitly reports omitted items. */
final class InstagramAlbumPage {
    private InstagramAlbumPage() { }

    static void requireComplete(int received, int reported, boolean hasNextPage) throws Exception {
        if (received < 1 || received > 100)
            throw new Exception("Instagram album has an invalid number of items");
        if (hasNextPage || reported >= 0 && reported != received)
            throw new Exception("Instagram exposed only part of this album; retry when all items are public");
    }
}
