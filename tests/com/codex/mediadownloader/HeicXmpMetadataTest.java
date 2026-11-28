package com.codex.mediadownloader;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

/** Checks the container update without depending on a private source photo fixture. */
public final class HeicXmpMetadataTest {
    private static final byte[] MEDIA = {11, 22, 33, 44};
    private static final String OLD_XMP = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF " +
        "xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">" +
        "<rdf:Description rdf:about=\"\"/></rdf:RDF></x:xmpmeta>";

    public static void main(String[] args) throws Exception {
        for (boolean extended : new boolean[]{false, true}) {
            File file = File.createTempFile("heic-xmp", ".heic");
            try {
                byte[] original = container(extended, true);
                Files.write(file.toPath(), original);
                check(OLD_XMP.equals(HeicXmpMetadata.read(file)), "Existing XMP item must be readable");
                String updated = PhotoSourceMetadata.xmp(OLD_XMP,
                    "https://www.rednote.com/discovery/item/6b040140000000001f0157c4",
                    "测试用户", PostText.from("Title", "Caption #测试", Arrays.asList("测试")));
                HeicXmpMetadata.write(file, updated);
                byte[] saved = Files.readAllBytes(file.toPath());
                int payload = payloadOffset(extended, true);
                check(updated.equals(HeicXmpMetadata.read(file)), "Updated XMP must round-trip");
                check(Arrays.equals(Arrays.copyOfRange(original, payload, original.length),
                    Arrays.copyOfRange(saved, payload, original.length)),
                    "Original media and metadata payload bytes must remain unchanged");
                check(saved.length == original.length + updated.length(),
                    "Update must append only the new XMP bytes");
            } finally { file.delete(); }
        }
        File unsupported = File.createTempFile("heic-no-reference", ".heic");
        try {
            byte[] original = container(false, false);
            Files.write(unsupported.toPath(), original);
            try {
                HeicXmpMetadata.write(unsupported, OLD_XMP);
                throw new AssertionError("Unlinked XMP must not be modified");
            } catch (Exception expected) {
                check(Arrays.equals(original, Files.readAllBytes(unsupported.toPath())),
                    "Unsupported HEIC must remain byte-identical");
            }
        } finally { unsupported.delete(); }
        System.out.println("HeicXmpMetadataTest passed");
    }

    private static byte[] container(boolean extended, boolean linked) throws Exception {
        byte[] ftyp = box("ftyp", concat("heic".getBytes(StandardCharsets.US_ASCII),
            new byte[]{0, 0, 0, 0}, "heic".getBytes(StandardCharsets.US_ASCII)));
        byte[] xmp = OLD_XMP.getBytes(StandardCharsets.UTF_8);
        byte[] meta = meta(0, xmp.length, linked);
        int offset = ftyp.length + meta.length + (extended ? 16 : 8) + MEDIA.length;
        meta = meta(offset, xmp.length, linked);
        byte[] payload = concat(MEDIA, xmp);
        byte[] mdat;
        if (extended) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(1);
            out.writeBytes("mdat");
            out.writeLong(16L + payload.length);
            out.write(payload);
            mdat = bytes.toByteArray();
        } else mdat = box("mdat", payload);
        return concat(ftyp, meta, mdat);
    }

    private static int payloadOffset(boolean extended, boolean linked) throws Exception {
        byte[] xmp = OLD_XMP.getBytes(StandardCharsets.UTF_8);
        return box("ftyp", concat("heic".getBytes(StandardCharsets.US_ASCII),
            new byte[]{0, 0, 0, 0}, "heic".getBytes(StandardCharsets.US_ASCII))).length +
            meta(0, xmp.length, linked).length + (extended ? 16 : 8);
    }

    private static byte[] meta(int offset, int length, boolean linked) throws Exception {
        byte[] pitm = box("pitm", data(out -> { out.writeInt(0); out.writeShort(1); }));
        byte[] infe = box("infe", data(out -> {
            out.writeInt(0x02000000);
            out.writeShort(2);
            out.writeShort(0);
            out.writeBytes("mime");
            out.writeBytes("XMP"); out.writeByte(0);
            out.writeBytes("application/rdf+xml"); out.writeByte(0);
        }));
        byte[] iinf = box("iinf", concat(data(out -> {
            out.writeInt(0); out.writeShort(1);
        }), infe));
        byte[] iref = box("iref", linked ? concat(new byte[4], box("cdsc", data(out -> {
            out.writeShort(2); out.writeShort(1); out.writeShort(1);
        }))) : new byte[4]);
        byte[] iloc = box("iloc", data(out -> {
            out.writeInt(0x01000000);
            out.writeByte(0x44); out.writeByte(0);
            out.writeShort(1); out.writeShort(2);
            out.writeShort(0); out.writeShort(0);
            out.writeShort(1); out.writeInt(offset); out.writeInt(length);
        }));
        return box("meta", concat(new byte[4], pitm, iinf, iref, iloc));
    }

    private interface Writer { void write(DataOutputStream out) throws Exception; }

    private static byte[] data(Writer writer) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        writer.write(new DataOutputStream(bytes));
        return bytes.toByteArray();
    }

    private static byte[] box(String type, byte[] payload) throws Exception {
        return data(out -> {
            out.writeInt(payload.length + 8);
            out.writeBytes(type);
            out.write(payload);
        });
    }

    private static byte[] concat(byte[]... parts) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (byte[] part : parts) bytes.write(part);
        return bytes.toByteArray();
    }

    private static void check(boolean valid, String message) {
        if (!valid) throw new AssertionError(message);
    }
}
