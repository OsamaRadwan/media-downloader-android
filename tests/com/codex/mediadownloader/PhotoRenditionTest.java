package com.codex.mediadownloader;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class PhotoRenditionTest {
    public static void main(String[] args) {
        String asset = "https://p16-sign.tiktokcdn.com/tos-useast5/photo123";
        List<StreamResolver.PhotoCandidate> baseline = new ArrayList<>();
        baseline.add(new StreamResolver.PhotoCandidate(1, 1080, 2140, Arrays.asList(
            asset + "~tplv-small.jpeg?x-signature=a&x-expires=1",
            asset + "~tplv-small.jpeg?x-signature=b&x-expires=1")));
        List<StreamResolver.PhotoCandidate> wide = Arrays.asList(
            new StreamResolver.PhotoCandidate(1, 2160, 4280, Arrays.asList(
                asset + "~tplv-large.jpeg?x-signature=c&x-expires=2",
                asset + "~tplv-large.jpeg?x-signature=d&x-expires=2")));
        check(StreamResolver.mergePhotoCandidates(baseline, wide) == 1,
            "A larger signed template must be counted once");
        check(baseline.get(0).urls.size() == 4 && baseline.get(0).width == 2160,
            "Both large mirrors and their listed dimensions must remain available");
        check(PhotoQuality.better(1500, 1500, 500000, 1080, 1350, 400000) &&
            !PhotoQuality.matchesAspect(1500, 1500, 1080, 1350) &&
            !PhotoQuality.matchesAspect(1080, 1440, 1080, 1350),
            "Square and 3:4 crops must not replace a listed 4:5 portrait photo");
        check(PhotoQuality.matchesAspect(1000, 1249, 1000, 1250) &&
            PhotoQuality.matchesAspect(1000, 1249, 1080, 1350) &&
            PhotoQuality.matchesAspect(4284, 5712, 1080, 1440) &&
            PhotoQuality.matchesAspect(1350, 1080, 1080, 1350) &&
            PhotoQuality.matchesAspect(1500, 1500, 0, 0),
            "Equivalent shapes, stored rotation, and missing listed dimensions remain eligible");
        String imagePath = "/tos-useast5-i-photomode-tx/f7cf550d212e450e8db0190589ba292e";
        String firstId = StreamResolver.photoSourceId(Arrays.asList(
            "https://p19-sign.tiktokcdn-us.com" + imagePath + "~tplv-photomode-image.jpeg?x-signature=secret-a",
            "https://p16-sign.tiktokcdn-us.com" + imagePath + "~tplv-photomode-image.jpeg?x-signature=secret-b"));
        String refreshedId = StreamResolver.photoSourceId(Arrays.asList(
            "https://p77-sign.tiktokcdn-us.com" + imagePath + "~tplv-photomode-image.jpeg?x-signature=secret-c"));
        check(firstId.startsWith("tt:") && firstId.equals(refreshedId) &&
            !firstId.contains("secret") && !firstId.contains("tos-useast"),
            "A photo ID must ignore mirror hosts and signed query values");
        check(!firstId.equals(StreamResolver.photoSourceId(Arrays.asList(
            "https://p16-sign.tiktokcdn-us.com/tos-useast5-i-photomode-tx/another-photo-asset-1234567890~tplv-photomode-image.jpeg"))),
            "Different photo assets must have different IDs");
        check(StreamResolver.photoSourceId(Arrays.asList(
            "https://p16-sign.tiktokcdn-us.com" + imagePath + "~tplv-photomode-image.jpeg",
            "https://p16-sign.tiktokcdn-us.com/tos-useast5-i-photomode-tx/another-photo-asset-1234567890~tplv-photomode-image.jpeg")).isEmpty(),
            "Conflicting asset paths must not create a durable ID");
        check(StreamResolver.mergePhotoCandidates(baseline, wide) == 0,
            "Repeated signatures must not create duplicate renditions");
        List<StreamResolver.PhotoCandidate> unrelated = Arrays.asList(
            new StreamResolver.PhotoCandidate(1, 4000, 4000, Arrays.asList(
                "https://p16-sign.tiktokcdn.com/tos-useast5/other-photo~tplv-large.jpeg")));
        check(StreamResolver.mergePhotoCandidates(baseline, unrelated) == 0,
            "A different image asset must not be substituted at the same album index");
        check(baseline.get(0).urls.size() == 4 && baseline.get(0).width == 2160,
            "An unrelated image must leave the chosen candidates unchanged");
        List<StreamResolver.PhotoCandidate> identified = new ArrayList<>();
        identified.add(new StreamResolver.PhotoCandidate(1, 1080, 1080,
            Arrays.asList("https://p16-sign.tiktokcdn-us.com" + imagePath + "~tplv-small.jpeg"), firstId));
        List<StreamResolver.PhotoCandidate> larger = Arrays.asList(new StreamResolver.PhotoCandidate(1,
            2160, 2160, Arrays.asList("https://p77-sign.tiktokcdn-us.com" + imagePath +
                "~tplv-large.jpeg"), refreshedId));
        check(StreamResolver.mergePhotoCandidates(identified, larger) == 1 &&
            firstId.equals(identified.get(0).sourceId),
            "Rendition merging must retain the photo source ID");
        System.out.println("PhotoRenditionTest passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
