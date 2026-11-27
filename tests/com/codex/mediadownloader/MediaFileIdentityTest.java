package com.codex.mediadownloader;

public final class MediaFileIdentityTest {
    public static void main(String[] args) {
        String name = "creator_profile_123_VideoID_456_2026-11-27_12-25.mp4";
        check(MediaFileIdentity.matches(name, 5628893, name, 5628893), "exact saved file");
        check(!MediaFileIdentity.matches(name, 5628893, name + ".bak", 5628893), "renamed file");
        check(!MediaFileIdentity.matches(name, 5628893, name, 5628892), "changed byte size");
        check(!MediaFileIdentity.matches(name, 5628893, name, 0), "empty file");
        check(!MediaFileIdentity.matches("", 5628893, name, 5628893), "unknown expected name");
        check(!MediaFileIdentity.matches(name, 0, name, 5628893), "unknown expected size");
        System.out.println("Media file identity tests passed");
    }

    private static void check(boolean value, String caseName) {
        if (!value) throw new AssertionError(caseName);
    }
}
