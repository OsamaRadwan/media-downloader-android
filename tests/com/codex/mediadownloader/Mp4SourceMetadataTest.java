package com.codex.mediadownloader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

public final class Mp4SourceMetadataTest {
    private static final String URL = "https://www.tiktok.com/@creator/video/7708810139658128647";
    private static final String INSTAGRAM_URL = "https://www.instagram.com/reel/Df0DoVpmTBI/";
    private static final String STORY_URL = "https://www.instagram.com/stories/nasa/4034039760237270159/";
    private static final String REDNOTE_URL = "https://www.rednote.com/discovery/item/6ae306f60000000007038cbf";
    private static final String SECRET = "PRIVATE_TOKEN_SHOULD_NOT_APPEAR";

    public static void main(String[] args) throws Exception {
        PostText post = PostText.from("", "Sunset ride #travel #مساء #travel", Arrays.asList("fyp", "travel"));
        check(post.title.equals("Sunset ride"), "Title comes from caption text");
        check(post.description.equals("Sunset ride #travel #مساء #travel"), "Description preserves caption");
        check(post.hashtags.equals("#travel, #مساء, #fyp"), "Keywords include deduplicated visible and listed hashtags");
        File sample = File.createTempFile("mp4-source-tag", ".mp4");
        try {
            try (FileOutputStream out = new FileOutputStream(sample)) {
                out.write(box("ftyp", new byte[]{'i', 's', 'o', 'm'}));
                out.write(box("moov", box("mvhd", new byte[4])));
                out.write(box("mdat", new byte[]{1, 2, 3, 4}));
            }
            long originalMdatOffset = 12 + 20;
            Mp4SourceMetadata.write(sample, URL, post);
            check(Mp4SourceMetadata.contains(sample, URL), "MP4 must contain source URL");
            check(Mp4SourceMetadata.containsTag(sample, "\u00a9nam", post.title, false), "MP4 title tag");
            check(Mp4SourceMetadata.containsTag(sample, "desc", post.description, false), "MP4 description tag");
            Mp4SourceMetadata.write(sample, INSTAGRAM_URL, post);
            check(Mp4SourceMetadata.contains(sample, INSTAGRAM_URL), "Instagram MP4 must contain source URL");
            Mp4SourceMetadata.write(sample, STORY_URL, post);
            check(Mp4SourceMetadata.contains(sample, STORY_URL), "Story MP4 must contain its own source URL");
            Mp4SourceMetadata.write(sample, REDNOTE_URL, post);
            check(Mp4SourceMetadata.contains(sample, REDNOTE_URL), "RedNote MP4 must contain its source URL");
            String[] cleanUrls = {URL, INSTAGRAM_URL, STORY_URL, REDNOTE_URL,
                "https://www.tiktok.com/@creator/photo/7708810139658128647",
                "https://www.instagram.com/p/Df0DoVpmTBI/"};
            for (String cleanUrl : cleanUrls) {
                String sharedUrl = cleanUrl + "?igsh=" + SECRET + "#" + SECRET;
                check(SourcePostUrl.forMetadata(sharedUrl).equals(cleanUrl),
                    "Public permalink must drop every share parameter and fragment");
                Mp4SourceMetadata.write(sample, sharedUrl, post);
                check(Mp4SourceMetadata.containsTag(sample, "\u00a9cmt", cleanUrl, false),
                    "MP4 Comment must contain only the clean permalink");
                check(!new String(Files.readAllBytes(sample.toPath()), "ISO-8859-1").contains(SECRET),
                    "MP4 must not include a share token in any bytes");
            }
            String anonymous = "https://www.tiktok.com/@_/video/7708810139658128647";
            check(SourcePostUrl.forResolved("tiktok", "creator", "7708810139658128647", anonymous)
                .equals(URL), "TikTok metadata uses the resolved creator permalink");
            check(SourcePostUrl.forResolved("tiktok", "creator", "7708810139658128647",
                anonymous + "?share_token=" + SECRET).equals(URL),
                "Resolved TikTok metadata omits share parameters");
            check(SourcePostUrl.forResolved("tiktok", "creator", "7708810139658128647",
                "https://www.tiktok.com/@_/photo/7708810139658128647")
                .equals("https://www.tiktok.com/@creator/photo/7708810139658128647"),
                "TikTok photo metadata uses the resolved creator permalink");
            check(SourcePostUrl.forResolved("instagram", "nasa", "ignored", INSTAGRAM_URL)
                .equals(INSTAGRAM_URL), "Other platforms retain their public post link");
            try {
                SourcePostUrl.forResolved("tiktok", "creator", "7708810139658128647",
                    "https://www.tiktok.com/@_/video/7708810139658128648");
                throw new AssertionError("A different TikTok post ID was accepted");
            } catch (java.io.IOException expected) { }
            try {
                SourcePostUrl.forResolved("tiktok", "other/path", "7708810139658128647", anonymous);
                throw new AssertionError("An unsafe TikTok creator handle was accepted");
            } catch (java.io.IOException expected) { }
            check(SourcePostUrl.forMetadata("https://xiaohongshu.com/explore/6AE306F60000000007038CBF?x=" + SECRET)
                .equals(REDNOTE_URL), "RedNote alternate public path must normalize");
            String[] rejected = {"https://instagram.com.evil.example/p/Df0DoVpmTBI/?x=" + SECRET,
                "https://user:password@www.instagram.com/p/Df0DoVpmTBI/",
                "http://www.instagram.com/p/Df0DoVpmTBI/",
                "https://www.instagram.com:443/p/Df0DoVpmTBI/",
                "https://www.instagram.com/p/%44f0DoVpmTBI/"};
            for (String badUrl : rejected) {
                try {
                    SourcePostUrl.forMetadata(badUrl);
                    throw new AssertionError("Unsafe post link was accepted");
                } catch (java.io.IOException expected) {
                    check(!expected.getMessage().contains(SECRET), "Rejected URL must not reach diagnostics");
                }
            }
            check(Mp4SourceMetadata.containsTag(sample, "\u00a9key", post.hashtags, false), "MP4 keyword tag");
            try (RandomAccessFile media = new RandomAccessFile(sample, "r")) {
                media.seek(12 + 4);
                check(media.readInt() == 0x66726565, "Original movie box becomes free space");
                media.seek(originalMdatOffset + 8);
                check(media.readInt() == 0x01020304, "Encoded media bytes remain at their original offset");
            }
        } finally { sample.delete(); }
        for (int format = 0; format < 3; format++) {
            File alternate = File.createTempFile("mp4-large-box", ".mp4");
            try {
                byte[] payload = new byte[]{11, 22, 33, 44};
                byte[] movie = box("moov", box("mvhd", new byte[4]));
                if (format == 2) movie = extendedBox("moov", box("mvhd", new byte[4]));
                byte[] media = format == 0 ? extendedBox("mdat", payload) :
                    format == 1 ? openEndedBox("mdat", payload) : box("mdat", payload);
                long mediaOffset = 12 + movie.length + media.length - payload.length;
                try (FileOutputStream out = new FileOutputStream(alternate)) {
                    out.write(box("ftyp", new byte[]{'i', 's', 'o', 'm'}));
                    out.write(movie);
                    out.write(media);
                }
                Mp4SourceMetadata.write(alternate, REDNOTE_URL, post);
                check(Mp4SourceMetadata.contains(alternate, REDNOTE_URL),
                    "Source link survives alternate MP4 box size " + format);
                try (RandomAccessFile checkFile = new RandomAccessFile(alternate, "r")) {
                    checkFile.seek(mediaOffset);
                    byte[] actual = new byte[payload.length];
                    checkFile.readFully(actual);
                    check(Arrays.equals(actual, payload), "Media offset survives box size " + format);
                }
            } finally { alternate.delete(); }
        }
        if (args.length == 2) {
            File source = new File(args[0]);
            File output = new File(args[1]);
            Files.copy(source.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING);
            Mp4SourceMetadata.write(output, URL, post);
            check(Mp4SourceMetadata.contains(output, URL), "Real MP4 must contain source URL");
            System.out.println("Tagged real MP4: " + output.getAbsolutePath());
        }
        System.out.println("Mp4SourceMetadataTest passed");
    }

    private static byte[] box(String type, byte[] data) {
        int size = data.length + 8;
        byte[] result = new byte[size];
        result[0] = (byte) (size >>> 24);
        result[1] = (byte) (size >>> 16);
        result[2] = (byte) (size >>> 8);
        result[3] = (byte) size;
        for (int i = 0; i < 4; i++) result[4 + i] = (byte) type.charAt(i);
        System.arraycopy(data, 0, result, 8, data.length);
        return result;
    }

    private static byte[] extendedBox(String type, byte[] data) {
        byte[] result = new byte[data.length + 16];
        result[3] = 1;
        for (int i = 0; i < 4; i++) result[4 + i] = (byte) type.charAt(i);
        long size = result.length;
        for (int i = 15; i >= 8; i--) { result[i] = (byte) size; size >>>= 8; }
        System.arraycopy(data, 0, result, 16, data.length);
        return result;
    }

    private static byte[] openEndedBox(String type, byte[] data) {
        byte[] result = box(type, data);
        Arrays.fill(result, 0, 4, (byte) 0);
        return result;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
