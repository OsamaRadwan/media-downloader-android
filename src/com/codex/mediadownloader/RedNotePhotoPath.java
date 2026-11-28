package com.codex.mediadownloader;

/** Accepts only observed public RedNote photo asset IDs before building CDN URLs. */
final class RedNotePhotoPath {
    private static final String HOST = "https://sns-img-hw.xhscdn.com/";

    private RedNotePhotoPath() { }

    static boolean valid(String fileId) {
        return fileId != null && (fileId.matches("[A-Za-z0-9]{20,80}") ||
            fileId.matches("(?:notes_pre_post|note_pre_post_uhdr)/[A-Za-z0-9]{40}"));
    }

    static String originalUrl(String fileId) {
        return valid(fileId) ? HOST + fileId : "";
    }
}
