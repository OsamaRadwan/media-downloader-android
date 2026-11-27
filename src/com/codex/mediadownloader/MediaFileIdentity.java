package com.codex.mediadownloader;

/** A saved file matches its download record only when both name and byte size agree. */
final class MediaFileIdentity {
    private MediaFileIdentity() { }

    static boolean matches(String expectedName, long expectedBytes, String actualName, long actualBytes) {
        return expectedName != null && !expectedName.isEmpty() && expectedBytes > 0 &&
            expectedName.equals(actualName) && expectedBytes == actualBytes;
    }
}
