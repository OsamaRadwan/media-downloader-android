package com.codex.mediadownloader;

import java.net.CookieManager;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

public final class AlbumMediaPlanTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        StreamResolver.PhotoCandidate first = new StreamResolver.PhotoCandidate(1, 1200, 1600,
            Collections.singletonList("https://example.com/first.jpg"));
        StreamResolver.Candidate middle = new StreamResolver.Candidate(
            "https://example.com/middle.mp4", 1080, 1920, 0, "album-2");
        StreamResolver.PhotoCandidate last = new StreamResolver.PhotoCandidate(3, 1600, 1200,
            Collections.singletonList("https://example.com/last.jpg"));
        StreamResolver.Result mixed = new StreamResolver.Result("creator", "42", "123", "", "",
            PostText.from("", "", Collections.emptyList()), 1,
            new ArrayList<>(), Arrays.asList(first, last), new CookieManager(), "instagram",
            Arrays.asList(StreamResolver.AlbumItem.photo(first, "101"),
                StreamResolver.AlbumItem.video(2, Collections.singletonList(middle), "102"),
                StreamResolver.AlbumItem.photo(last, "103")));
        check(mixed.isAlbum() && mixed.hasAlbumVideos(), "mixed sidecar is an album with video");
        check(mixed.albumItems.size() == 3 && mixed.albumItems.get(1).video.get(0) == middle &&
            mixed.albumItems.get(2).photo == last &&
            "102".equals(mixed.albumItems.get(1).sourceId),
            "source order, item types, and child identities survive parsing");
        try {
            AlbumItemIdentity.validate(mixed.albumItems.get(0), 1, "photo", "101");
            AlbumItemIdentity.validate(mixed.albumItems.get(0), 1, "photo", "");
        } catch (Exception error) { throw new AssertionError("matching and legacy rows should retry", error); }
        boolean swapped = false;
        try { AlbumItemIdentity.validate(mixed.albumItems.get(0), 1, "photo", "103"); }
        catch (Exception expected) { swapped = true; }
        check(swapped, "a same-type child swapped into an earlier position is rejected");
        boolean missing = false;
        try { AlbumItemIdentity.validate(StreamResolver.AlbumItem.photo(first), 1, "photo", "101"); }
        catch (Exception expected) { missing = true; }
        check(missing, "a known child ID cannot disappear on retry");
        StreamResolver.Result photos = new StreamResolver.Result("creator", "42", "123", "", "",
            PostText.from("", "", Collections.emptyList()), 1,
            new ArrayList<>(), Collections.singletonList(first), new CookieManager(), "instagram");
        check(photos.isAlbum() && !photos.hasAlbumVideos() && photos.albumItems.size() == 1,
            "existing photo albums retain their item model");
        StreamResolver.PhotoCandidate identified = new StreamResolver.PhotoCandidate(1, 1200, 1600,
            Collections.singletonList("https://example.com/first.jpg"), "tt:known-asset");
        StreamResolver.Result identifiedAlbum = new StreamResolver.Result("creator", "42", "123", "", "",
            PostText.from("", "", Collections.emptyList()), 1,
            new ArrayList<>(), Collections.singletonList(identified), new CookieManager(), "tiktok");
        check("tt:known-asset".equals(identifiedAlbum.albumItems.get(0).sourceId),
            "photo-only albums pass their stable source ID to the shared retry plan");
        boolean rejected = false;
        try {
            new StreamResolver.Result("creator", "42", "123", "", "",
                PostText.from("", "", Collections.emptyList()), 1,
                new ArrayList<>(), Collections.singletonList(first), new CookieManager(), "instagram",
                Collections.singletonList(StreamResolver.AlbumItem.photo(last)));
        } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "out-of-order items are rejected");
        System.out.println("AlbumMediaPlanTest passed");
    }
}
