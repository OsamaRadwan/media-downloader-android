package com.codex.mediadownloader;

import java.util.List;
import java.util.Arrays;

public final class StoryProgressBarTest {
    public static void main(String[] args) {
        int[] row = new int[1080];
        int[] above = new int[row.length];
        int[] below = new int[row.length];
        for (int i = 0; i < row.length; i++) {
            above[i] = below[i] = row[i] = 0xff223344;
        }
        for (int i = 0; i < 7; i++) {
            int start = 30 + i * 147;
            for (int x = start; x < start + 137; x++) row[x] =
                i < 3 ? 0xffbbbbbb : 0xff707070;
        }
        for (int x = 577; x <= 584; x++) row[x] = 0xff223344;
        List<Integer> centers = StoryProgressBar.centers(above, row, below);
        check(centers.size() == 7, "seven segments");
        check(centers.get(0) == 98, "first segment center");
        check(centers.get(6) == 980, "last segment center");
        check(StoryProgressBar.evenlySpaced(
            Arrays.asList(86, 238, 389, 541, 692, 844, 995), row.length),
            "uniform seven-segment strip");
        check(StoryProgressBar.evenlySpaced(
            Arrays.asList(147, 407, 671, 932), row.length),
            "four-segment device Story strip");
        check(StoryProgressBar.evenlySpaced(Arrays.asList(278, 800), row.length),
            "two-segment device Story strip");
        check(!StoryProgressBar.evenlySpaced(
            Arrays.asList(31, 102, 159, 221, 296, 322, 350, 377, 542, 672, 742, 1044),
            row.length), "video texture is not a Story strip");
        check(!StoryProgressBar.evenlySpaced(
            Arrays.asList(130, 364, 585, 762, 951), row.length),
            "Story frame boundary is not the segment strip");
        System.out.println("StoryProgressBarTest passed");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
