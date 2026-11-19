package com.codex.mediadownloader;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** Rewrites one small log file, retaining only the seven current UTC dates. */
final class DiagnosticsFilePruner {
    private DiagnosticsFilePruner() { }

    static void prune(File source, long now) throws Exception {
        File temporary = new File(source.getParentFile(), source.getName() + ".tmp");
        if (!source.isFile()) {
            if (temporary.exists()) temporary.delete();
            return;
        }
        if (temporary.exists() && !temporary.delete()) throw new Exception("Could not clear temporary diagnostic log");
        boolean changed = false;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(source), StandardCharsets.UTF_8));
             FileOutputStream output = new FileOutputStream(temporary)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!DiagnosticsRetention.keep(line, now)) { changed = true; continue; }
                output.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            }
            output.getFD().sync();
        }
        if (changed) {
            if (temporary.length() == 0) {
                if (!source.delete()) throw new Exception("Could not remove expired diagnostic log");
                temporary.delete();
            } else Files.move(temporary.toPath(), source.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } else temporary.delete();
    }
}
