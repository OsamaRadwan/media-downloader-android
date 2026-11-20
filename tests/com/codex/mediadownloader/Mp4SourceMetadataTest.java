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
            check(Mp4SourceMetadata.containsTag(sample, "\u00a9key", post.hashtags, false), "MP4 keyword tag");
            try (RandomAccessFile media = new RandomAccessFile(sample, "r")) {
                media.seek(12 + 4);
                check(media.readInt() == 0x66726565, "Original movie box becomes free space");
                media.seek(originalMdatOffset + 8);
                check(media.readInt() == 0x01020304, "Encoded media bytes remain at their original offset");
            }
        } finally { sample.delete(); }
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

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
