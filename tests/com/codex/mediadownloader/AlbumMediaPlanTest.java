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
            Arrays.asList(StreamResolver.AlbumItem.photo(first),
                StreamResolver.AlbumItem.video(2, Collections.singletonList(middle)),
                StreamResolver.AlbumItem.photo(last)));
        check(mixed.isAlbum() && mixed.hasAlbumVideos(), "mixed sidecar is an album with video");
        check(mixed.albumItems.size() == 3 && mixed.albumItems.get(1).video.get(0) == middle &&
            mixed.albumItems.get(2).photo == last, "source order and item types survive parsing");
        StreamResolver.Result photos = new StreamResolver.Result("creator", "42", "123", "", "",
            PostText.from("", "", Collections.emptyList()), 1,
            new ArrayList<>(), Collections.singletonList(first), new CookieManager(), "instagram");
        check(photos.isAlbum() && !photos.hasAlbumVideos() && photos.albumItems.size() == 1,
            "existing photo albums retain their item model");
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
