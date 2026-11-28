package com.codex.mediadownloader;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Adds MP4 post metadata without changing the encoded media samples. */
final class Mp4SourceMetadata {
    private static final int MAX_MOOV_BYTES = 32 * 1024 * 1024;
    private static final String COMMENT = "\u00a9cmt";
    private static final String TITLE = "\u00a9nam";
    private static final String DESCRIPTION = "desc";
    private static final String KEYWORDS = "\u00a9key";

    private Mp4SourceMetadata() { }

    static void write(File file, String sourceUrl) throws IOException {
        write(file, sourceUrl, PostText.from("", "", null));
    }

    static void write(File file, String sourceUrl, PostText postText) throws IOException {
        sourceUrl = SourcePostUrl.forMetadata(sourceUrl);
        if (postText == null) throw new IOException("Post metadata missing");
        try (RandomAccessFile media = new RandomAccessFile(file, "rw")) {
            long originalLength = media.length();
            long position = 0;
            long moovPosition = -1;
            int moovSize = 0;
            int moovHeader = 8;
            long openEndedPosition = -1;
            long openEndedSize = 0;
            while (position < originalLength) {
                TopBox box = readTopBox(media, position, originalLength);
                if (box.openEnded) {
                    openEndedPosition = position;
                    openEndedSize = box.size;
                }
                if ("moov".equals(box.type)) {
                    if (moovPosition >= 0 || box.size - box.header + 8 > MAX_MOOV_BYTES)
                        throw new IOException("Unsupported MP4 movie box");
                    moovPosition = position;
                    moovSize = (int) box.size;
                    moovHeader = box.header;
                }
                position += box.size;
            }
            if (moovPosition < 0) throw new IOException("MP4 movie box missing");
            byte[] moov = new byte[moovSize - moovHeader + 8];
            media.seek(moovPosition);
            if (moovHeader == 16) {
                media.skipBytes(16);
                writeInt(moov, 0, moov.length);
                System.arraycopy(typeBytes("moov"), 0, moov, 4, 4);
                media.readFully(moov, 8, moov.length - 8);
            } else media.readFully(moov);
            byte[] updated = addTags(moov, sourceUrl, postText);
            if (openEndedPosition >= 0 && openEndedSize > 0xffffffffL)
                throw new IOException("Open-ended MP4 box exceeds 32-bit size");
            media.seek(originalLength);
            media.write(updated);
            if (openEndedPosition >= 0) {
                media.seek(openEndedPosition);
                media.writeInt((int) openEndedSize);
            }
            media.seek(moovPosition + 4);
            media.write(new byte[]{'f', 'r', 'e', 'e'});
            media.getFD().sync();
        }
        if (!contains(file, sourceUrl) ||
            !containsTag(file, TITLE, postText.title, false) ||
            !containsTag(file, DESCRIPTION, postText.description, false) ||
            !containsTag(file, KEYWORDS, postText.hashtags, false))
            throw new IOException("MP4 post metadata could not be verified");
    }

    static boolean contains(File file, String sourceUrl) throws IOException {
        return containsTag(file, COMMENT, sourceUrl, true);
    }

    static boolean containsTag(File file, String type, String value, boolean allowMoreText) throws IOException {
        if (value == null || value.isEmpty()) return true;
        try (RandomAccessFile media = new RandomAccessFile(file, "r")) {
            long position = 0;
            while (position + 8 <= media.length()) {
                TopBox box = readTopBox(media, position, media.length());
                if ("moov".equals(box.type)) {
                    if (box.size - box.header + 8 > MAX_MOOV_BYTES) return false;
                    byte[] moov = new byte[(int) (box.size - box.header + 8)];
                    media.seek(position);
                    if (box.header == 16) {
                        media.skipBytes(16);
                        writeInt(moov, 0, moov.length);
                        System.arraycopy(typeBytes("moov"), 0, moov, 4, 4);
                        media.readFully(moov, 8, moov.length - 8);
                    } else media.readFully(moov);
                    Box udta = find(moov, 8, moov.length, "udta");
                    if (udta == null) return false;
                    Box meta = find(moov, udta.offset + 8, udta.end(), "meta");
                    if (meta == null) return false;
                    Box ilst = find(moov, meta.offset + 12, meta.end(), "ilst");
                    if (ilst == null) return false;
                    Box item = find(moov, ilst.offset + 8, ilst.end(), type);
                    if (item == null) return false;
                    Box data = find(moov, item.offset + 8, item.end(), "data");
                    if (data == null || data.size < 16 || readInt(moov, data.offset + 8) != 1) return false;
                    String stored = new String(moov, data.offset + 16, data.size - 16, StandardCharsets.UTF_8);
                    return allowMoreText ? stored.contains(value) : stored.equals(value);
                }
                position += box.size;
            }
            return false;
        }
    }

    private static byte[] addTags(byte[] moov, String url, PostText postText) throws IOException {
        Box udta = find(moov, 8, moov.length, "udta");
        byte[] items = items(url, postText);
        if (udta == null) {
            byte[] meta = newMeta(items);
            return append(moov, box("udta", meta));
        }
        byte[] udtaBytes = Arrays.copyOfRange(moov, udta.offset, udta.end());
        Box meta = find(udtaBytes, 8, udtaBytes.length, "meta");
        byte[] changedUdta;
        if (meta == null) {
            changedUdta = append(udtaBytes, newMeta(items));
        } else {
            byte[] metaBytes = Arrays.copyOfRange(udtaBytes, meta.offset, meta.end());
            Box ilst = find(metaBytes, 12, metaBytes.length, "ilst");
            byte[] changedMeta;
            if (ilst == null) {
                changedMeta = append(metaBytes, box("ilst", items));
            } else {
                byte[] ilstBytes = Arrays.copyOfRange(metaBytes, ilst.offset, ilst.end());
                ilstBytes = putText(ilstBytes, COMMENT, url, false);
                ilstBytes = putText(ilstBytes, TITLE, postText.title, false);
                ilstBytes = putText(ilstBytes, DESCRIPTION, postText.description, false);
                ilstBytes = putText(ilstBytes, KEYWORDS, postText.hashtags, false);
                changedMeta = replace(metaBytes, ilst, ilstBytes);
            }
            changedUdta = replace(udtaBytes, meta, changedMeta);
        }
        return replace(moov, udta, changedUdta);
    }

    private static byte[] items(String url, PostText postText) throws IOException {
        ByteArrayOutputStream items = new ByteArrayOutputStream();
        for (byte[] item : new byte[][]{
            textItem(COMMENT, url), textItem(TITLE, postText.title),
            textItem(DESCRIPTION, postText.description), textItem(KEYWORDS, postText.hashtags)})
            items.write(item, 0, item.length);
        return items.toByteArray();
    }

    private static byte[] putText(byte[] ilst, String type, String value, boolean preservePrior) throws IOException {
        if (value == null || value.isEmpty()) return ilst;
        Box existing = find(ilst, 8, ilst.length, type);
        if (existing == null) return append(ilst, textItem(type, value));
        if (preservePrior) {
            Box data = find(ilst, existing.offset + 8, existing.end(), "data");
            if (data != null && data.size >= 16 && readInt(ilst, data.offset + 8) == 1) {
                String prior = new String(ilst, data.offset + 16, data.size - 16, StandardCharsets.UTF_8);
                if (!prior.contains(value)) value = prior + "\n" + value;
                else value = prior;
            }
        }
        return replace(ilst, existing, textItem(type, value));
    }

    private static byte[] textItem(String type, String value) throws IOException {
        if (value == null || value.isEmpty()) return new byte[0];
        return box(type, box("data", concat(new byte[]{0, 0, 0, 1, 0, 0, 0, 0},
            value.getBytes(StandardCharsets.UTF_8))));
    }

    private static byte[] newMeta(byte[] items) throws IOException {
        byte[] handler = box("hdlr", new byte[]{
            0, 0, 0, 0, 0, 0, 0, 0, 'm', 'd', 'i', 'r',
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
        return box("meta", concat(new byte[4], handler, box("ilst", items)));
    }

    private static byte[] append(byte[] parent, byte[] child) throws IOException {
        return replace(parent, new Box(parent.length, 0), child);
    }

    private static byte[] replace(byte[] parent, Box old, byte[] replacement) throws IOException {
        long length = (long) parent.length - old.size + replacement.length;
        if (length > Integer.MAX_VALUE || old.offset < 8 || old.end() > parent.length)
            throw new IOException("MP4 metadata box too large");
        byte[] result = new byte[(int) length];
        System.arraycopy(parent, 0, result, 0, old.offset);
        System.arraycopy(replacement, 0, result, old.offset, replacement.length);
        System.arraycopy(parent, old.end(), result, old.offset + replacement.length, parent.length - old.end());
        writeInt(result, 0, result.length);
        return result;
    }

    private static byte[] box(String type, byte[] payload) throws IOException {
        if (payload.length > Integer.MAX_VALUE - 8) throw new IOException("MP4 metadata too large");
        byte[] result = new byte[payload.length + 8];
        writeInt(result, 0, result.length);
        System.arraycopy(typeBytes(type), 0, result, 4, 4);
        System.arraycopy(payload, 0, result, 8, payload.length);
        return result;
    }

    private static byte[] typeBytes(String type) throws IOException {
        byte[] bytes = type.getBytes(StandardCharsets.ISO_8859_1);
        if (bytes.length != 4) throw new IOException("Invalid MP4 box type");
        return bytes;
    }

    private static TopBox readTopBox(RandomAccessFile media, long position, long length)
        throws IOException {
        if (length - position < 8) throw new IOException("Incomplete MP4 box");
        media.seek(position);
        long size = Integer.toUnsignedLong(media.readInt());
        byte[] typeBytes = new byte[4];
        media.readFully(typeBytes);
        int header = 8;
        boolean openEnded = size == 0;
        if (size == 1) {
            if (length - position < 16) throw new IOException("Incomplete extended MP4 box");
            size = media.readLong();
            header = 16;
        } else if (openEnded) size = length - position;
        if (size < header || size > length - position)
            throw new IOException("Unsupported MP4 box size");
        return new TopBox(new String(typeBytes, StandardCharsets.ISO_8859_1), size,
            header, openEnded);
    }

    private static byte[] concat(byte[]... pieces) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] piece : pieces) out.write(piece, 0, piece.length);
        return out.toByteArray();
    }

    private static Box find(byte[] source, int start, int end, String type) throws IOException {
        byte[] wanted = typeBytes(type);
        for (int position = start; position + 8 <= end;) {
            long size = Integer.toUnsignedLong(readInt(source, position));
            if (size < 8 || size > end - position) throw new IOException("Invalid MP4 metadata box");
            if (source[position + 4] == wanted[0] && source[position + 5] == wanted[1] &&
                source[position + 6] == wanted[2] && source[position + 7] == wanted[3])
                return new Box(position, (int) size);
            position += (int) size;
        }
        return null;
    }

    private static int readInt(byte[] source, int offset) {
        return (source[offset] & 0xff) << 24 | (source[offset + 1] & 0xff) << 16 |
            (source[offset + 2] & 0xff) << 8 | source[offset + 3] & 0xff;
    }

    private static void writeInt(byte[] target, int offset, int value) {
        target[offset] = (byte) (value >>> 24);
        target[offset + 1] = (byte) (value >>> 16);
        target[offset + 2] = (byte) (value >>> 8);
        target[offset + 3] = (byte) value;
    }

    private static final class Box {
        final int offset;
        final int size;
        Box(int offset, int size) { this.offset = offset; this.size = size; }
        int end() { return offset + size; }
    }

    private static final class TopBox {
        final String type;
        final long size;
        final int header;
        final boolean openEnded;
        TopBox(String type, long size, int header, boolean openEnded) {
            this.type = type;
            this.size = size;
            this.header = header;
            this.openEnded = openEnded;
        }
    }
}
