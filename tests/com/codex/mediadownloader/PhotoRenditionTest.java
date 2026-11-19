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
        check(StreamResolver.mergePhotoCandidates(baseline, wide) == 0,
            "Repeated signatures must not create duplicate renditions");
        List<StreamResolver.PhotoCandidate> unrelated = Arrays.asList(
            new StreamResolver.PhotoCandidate(1, 4000, 4000, Arrays.asList(
                "https://p16-sign.tiktokcdn.com/tos-useast5/other-photo~tplv-large.jpeg")));
        check(StreamResolver.mergePhotoCandidates(baseline, unrelated) == 0,
            "A different image asset must not be substituted at the same album index");
        check(baseline.get(0).urls.size() == 4 && baseline.get(0).width == 2160,
            "An unrelated image must leave the chosen candidates unchanged");
        System.out.println("PhotoRenditionTest passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
