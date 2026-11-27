package com.codex.mediadownloader;

import java.util.ArrayList;
import java.util.List;

/** Finds rendered Story-segment centers in a screenshot row. */
final class StoryProgressBar {
    static List<Integer> centers(int[] above, int[] row, int[] below) {
        ArrayList<Integer> centers = new ArrayList<>();
        if (above == null || row == null || below == null || row.length < 100 ||
            above.length != row.length || below.length != row.length) return centers;
        ArrayList<int[]> runs = new ArrayList<>();
        int start = -1;
        int first = row.length * 2 / 100;
        int last = row.length * 98 / 100;
        int firstRun = -1;
        int lastRun = -1;
        for (int x = first; x <= last + 1; x++) {
            boolean bar = x <= last && isBar(above[x], row[x], below[x]);
            if (bar && start < 0) start = x;
            if (!bar && start >= 0) {
                if (x - start >= Math.max(8, row.length / 120)) {
                    runs.add(new int[] {start, x - 1});
                    if (firstRun < 0) firstRun = start;
                    lastRun = x - 1;
                }
                start = -1;
            }
        }
        int typical = 0;
        for (int[] run : runs) typical = Math.max(typical, run[1] - run[0] + 1);
        for (int i = 1; i < runs.size(); i++) {
            int[] prior = runs.get(i - 1);
            int[] current = runs.get(i);
            int priorWidth = prior[1] - prior[0] + 1;
            int currentWidth = current[1] - current[0] + 1;
            if (Math.min(priorWidth, currentWidth) < typical / 2 &&
                current[1] - prior[0] + 1 <= typical + 5) {
                prior[1] = current[1];
                runs.remove(i--);
            }
        }
        for (int[] run : runs) centers.add((run[0] + run[1]) / 2);
        if (centers.isEmpty() || centers.size() > 100 || firstRun > row.length / 12 ||
            lastRun < row.length * 11 / 12) centers.clear();
        return centers;
    }

    /** A Story strip divides almost the full screen into equal-width segments. */
    static boolean evenlySpaced(List<Integer> centers, int width) {
        if (centers == null || centers.isEmpty() || centers.size() > 100 || width < 100)
            return false;
        double pitch = (double) width / centers.size();
        for (int i = 0; i < centers.size(); i++) {
            double expected = pitch * (i + 0.5);
            if (Math.abs(centers.get(i) - expected) > pitch * 0.24) return false;
        }
        return true;
    }

    private static boolean isBar(int above, int pixel, int below) {
        return brightness(pixel) - (brightness(above) + brightness(below)) / 2 >= 8;
    }

    private static int brightness(int pixel) {
        return (((pixel >> 16) & 255) + ((pixel >> 8) & 255) + (pixel & 255)) / 3;
    }
}
