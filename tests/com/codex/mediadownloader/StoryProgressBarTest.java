package com.codex.mediadownloader;

import java.util.List;

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
        System.out.println("StoryProgressBarTest passed");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
