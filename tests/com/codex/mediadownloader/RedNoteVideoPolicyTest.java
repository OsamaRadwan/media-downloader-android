package com.codex.mediadownloader;

public final class RedNoteVideoPolicyTest {
    public static void main(String[] args) {
        check(RedNoteVideoPolicy.originalUrl("spectrum/1040g35830vr3bg1860005o6qr60o57fr83a7isg")
            .equals("https://sns-video-bd.xhscdn.com/spectrum/1040g35830vr3bg1860005o6qr60o57fr83a7isg"),
            "Original asset uses the creator's video key");
        for (String key : new String[]{"../secret", "/spectrum/key", "spectrum//key", "spectrum/key?token=x"})
            check(RedNoteVideoPolicy.originalUrl(key).isEmpty(), "Invalid original key rejected");
        String clean = "https://sns-video-hw.xhscdn.com/stream/110/258/example_258.mp4";
        String stamped = "https://sns-video-hw.xhscdn.com/stream/110/259/example_259.mp4";
        String stampedMobile = "https://sns-v27.rednotecdn.com/stream/1/110/259/example_259.mp4";
        String cleanMobile = "https://sns-v27.rednotecdn.com/stream/1/110/258/example_258.mp4";
        check(RedNoteVideoPolicy.cleanStream(258, clean), "Clean stream type allowed");
        check(RedNoteVideoPolicy.cleanStream(-1, clean), "Clean path type allowed");
        check(RedNoteVideoPolicy.cleanStream(-1, cleanMobile), "Clean mobile path type allowed");
        check(!RedNoteVideoPolicy.cleanStream(259, stamped), "Stamped stream type rejected");
        check(!RedNoteVideoPolicy.cleanStream(-1, stampedMobile), "Actual mobile stream path rejected");
        check(!RedNoteVideoPolicy.cleanStream(258, stamped), "Stamped path overrides conflicting declaration");
        check(!RedNoteVideoPolicy.cleanStream(-1, "https://sns-video-hw.xhscdn.com/unknown.mp4"),
            "Unknown playback stream not silently accepted");
        check(!RedNoteVideoPolicy.cleanScreencast(stamped), "Stamped screencast path rejected");
        check(RedNoteVideoPolicy.cleanScreencast(clean), "Clean screencast allowed");
        check(!RedNoteVideoPolicy.cleanScreencast("https://sns-video-hw.xhscdn.com/unknown.mp4"),
            "Screencast with unknown provenance rejected");
        System.out.println("RedNoteVideoPolicyTest passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
