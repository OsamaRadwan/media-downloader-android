package com.codex.mediadownloader;

import java.io.IOException;

public final class MediaResponseLengthTest {
    public static void main(String[] args) throws Exception {
        MediaResponseLength.verify(200, 1000, null, 1000);
        MediaResponseLength.verify(200, -1, null, 1000);
        MediaResponseLength.verify(206, 1000, "bytes 0-999/1000", 1000);
        MediaResponseLength.verify(206, -1, "bytes 0-999/1000", 1000);
        MediaResponseLength.verify(206, 92656662, "bytes 0-92656661/92656662", 92656662);
        reject(() -> MediaResponseLength.verify(200, 1000, null, 999), "truncated full response");
        reject(() -> MediaResponseLength.verify(206, 500, "bytes 0-499/1000", 500),
            "a valid range that covers only half the media");
        reject(() -> MediaResponseLength.verify(206, 1000, "bytes 1-1000/1001", 1000),
            "a response that omits the first byte");
        reject(() -> MediaResponseLength.verify(206, 1000, null, 1000),
            "a range without proof of complete coverage");
        reject(() -> MediaResponseLength.verify(206, 1000, "bytes 0-999/1000", 999),
            "a truncated range body");
        reject(() -> MediaResponseLength.verify(206, 1000, "bytes */1000", 1000),
            "an unsatisfied range");
        System.out.println("MediaResponseLengthTest passed");
    }

    private interface Checked { void run() throws Exception; }
    private static void reject(Checked action, String caseName) throws Exception {
        try { action.run(); }
        catch (IOException expected) { return; }
        throw new AssertionError("Accepted " + caseName);
    }
}
