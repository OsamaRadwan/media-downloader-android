package com.codex.mediadownloader;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/** Updates an existing primary-image XMP item without re-encoding HEIC image data. */
final class HeicXmpMetadata {
    private static final int MAX_XMP_BYTES = 1_000_000;

    private HeicXmpMetadata() { }

    static String read(File file) throws Exception {
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            Layout layout = inspect(input);
            if (layout.xmpLength < 1 || layout.xmpLength > MAX_XMP_BYTES)
                throw new Exception("Unsupported HEIC XMP length");
            byte[] bytes = new byte[(int) layout.xmpLength];
            input.seek(layout.xmpOffset);
            input.readFully(bytes);
            int length = bytes.length;
            while (length > 0 && bytes[length - 1] == 0) length--;
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, 0, length)).toString();
        }
    }

    static void write(File file, String packet) throws Exception {
        if (!PhotoSourceMetadata.ascii(packet)) throw new Exception("HEIC XMP is not ASCII-safe");
        byte[] bytes = packet.getBytes(StandardCharsets.US_ASCII);
        if (bytes.length < 1 || bytes.length > MAX_XMP_BYTES)
            throw new Exception("Unsupported HEIC XMP length");
        try (RandomAccessFile output = new RandomAccessFile(file, "rw")) {
            Layout layout = inspect(output);
            long appendAt = output.length();
            long newMdatSize = layout.mdat.size + bytes.length;
            if (!fits(appendAt, layout.offsetSize) || !fits(bytes.length, layout.lengthSize) ||
                layout.mdat.header == 8 && !fits(newMdatSize, 4) || newMdatSize < layout.mdat.size)
                throw new Exception("HEIC metadata exceeds its container field size");
            output.seek(appendAt);
            output.write(bytes);
            if (!layout.mdat.extendsToEnd) {
                output.seek(layout.mdat.start + (layout.mdat.header == 16 ? 8 : 0));
                writeUnsigned(output, layout.mdat.header == 16 ? 8 : 4, newMdatSize);
            }
            output.seek(layout.offsetField);
            writeUnsigned(output, layout.offsetSize, appendAt);
            output.seek(layout.lengthField);
            writeUnsigned(output, layout.lengthSize, bytes.length);
            output.getFD().sync();
        }
    }

    private static Layout inspect(RandomAccessFile file) throws Exception {
        long fileSize = file.length();
        Box meta = null, mdat = null;
        boolean ftyp = false;
        for (long at = 0; at < fileSize;) {
            Box box = box(file, at, fileSize);
            if (box.type == fourcc("ftyp")) ftyp = true;
            if (box.type == fourcc("meta")) {
                if (meta != null) throw new Exception("Multiple HEIC metadata boxes");
                meta = box;
            }
            if (box.type == fourcc("mdat")) {
                if (mdat != null) throw new Exception("Multiple HEIC media boxes");
                mdat = box;
            }
            at = box.end();
        }
        if (!ftyp || meta == null || mdat == null || mdat.end() != fileSize)
            throw new Exception("Unsupported HEIC container layout");
        Box pitm = null, iinf = null, iref = null, iloc = null;
        long end = meta.end();
        for (long at = meta.payload() + 4; at < end;) {
            Box child = box(file, at, end);
            if (child.type == fourcc("pitm")) pitm = unique(pitm, child);
            if (child.type == fourcc("iinf")) iinf = unique(iinf, child);
            if (child.type == fourcc("iref")) iref = unique(iref, child);
            if (child.type == fourcc("iloc")) iloc = unique(iloc, child);
            at = child.end();
        }
        if (pitm == null || iinf == null || iref == null || iloc == null)
            throw new Exception("HEIC image metadata is incomplete");
        long primary = primary(file, pitm);
        Set<Long> xmpItems = xmpItems(file, iinf);
        long xmpId = primaryXmp(file, iref, primary, xmpItems);
        return location(file, iloc, mdat, xmpId);
    }

    private static Box unique(Box previous, Box candidate) throws Exception {
        if (previous != null) throw new Exception("Duplicate HEIC metadata box");
        return candidate;
    }

    private static long primary(RandomAccessFile file, Box box) throws Exception {
        file.seek(box.payload());
        int version = file.readUnsignedByte();
        file.skipBytes(3);
        if (version != 0 && version != 1) throw new Exception("Unsupported HEIC primary item version");
        return readUnsigned(file, version == 0 ? 2 : 4, box.end());
    }

    private static Set<Long> xmpItems(RandomAccessFile file, Box box) throws Exception {
        file.seek(box.payload());
        int version = file.readUnsignedByte();
        file.skipBytes(3);
        if (version != 0 && version != 1) throw new Exception("Unsupported HEIC item-info version");
        long count = readUnsigned(file, version == 0 ? 2 : 4, box.end());
        Set<Long> xmp = new HashSet<>();
        int seen = 0;
        for (long at = file.getFilePointer(); at < box.end();) {
            Box entry = box(file, at, box.end());
            if (entry.type == fourcc("infe")) {
                seen++;
                file.seek(entry.payload());
                int entryVersion = file.readUnsignedByte();
                file.skipBytes(3);
                if (entryVersion == 2 || entryVersion == 3) {
                    long id = readUnsigned(file, entryVersion == 2 ? 2 : 4, entry.end());
                    readUnsigned(file, 2, entry.end()); // protection index
                    long type = readUnsigned(file, 4, entry.end());
                    if (type == fourcc("mime")) {
                        cString(file, entry.end()); // item name
                        if ("application/rdf+xml".equals(cString(file, entry.end()))) xmp.add(id);
                    }
                }
            }
            at = entry.end();
        }
        if (seen != count || xmp.isEmpty()) throw new Exception("HEIC has no supported XMP item");
        return xmp;
    }

    private static long primaryXmp(RandomAccessFile file, Box box, long primary,
                                   Set<Long> xmpItems) throws Exception {
        file.seek(box.payload());
        int version = file.readUnsignedByte();
        file.skipBytes(3);
        if (version != 0 && version != 1) throw new Exception("Unsupported HEIC reference version");
        int idSize = version == 0 ? 2 : 4;
        long found = -1;
        for (long at = file.getFilePointer(); at < box.end();) {
            Box reference = box(file, at, box.end());
            if (reference.type == fourcc("cdsc")) {
                file.seek(reference.payload());
                long source = readUnsigned(file, idSize, reference.end());
                long count = readUnsigned(file, 2, reference.end());
                for (long i = 0; i < count; i++) {
                    long target = readUnsigned(file, idSize, reference.end());
                    if (target == primary && xmpItems.contains(source)) {
                        if (found != -1) throw new Exception("Multiple XMP items describe the HEIC image");
                        found = source;
                    }
                }
            }
            at = reference.end();
        }
        if (found == -1) throw new Exception("HEIC has no primary-image XMP item");
        return found;
    }

    private static Layout location(RandomAccessFile file, Box box, Box mdat, long xmpId)
            throws Exception {
        file.seek(box.payload());
        int version = file.readUnsignedByte();
        file.skipBytes(3);
        if (version > 2) throw new Exception("Unsupported HEIC location version");
        int pair = file.readUnsignedByte();
        int offsetSize = pair >> 4, lengthSize = pair & 15;
        pair = file.readUnsignedByte();
        int baseSize = pair >> 4, indexSize = version == 0 ? 0 : pair & 15;
        if (offsetSize < 1 || offsetSize > 8 || lengthSize < 1 || lengthSize > 8 ||
            baseSize > 8 || indexSize > 8)
            throw new Exception("Unsupported HEIC item-location field width");
        long count = readUnsigned(file, version == 2 ? 4 : 2, box.end());
        Layout found = null;
        for (long i = 0; i < count; i++) {
            long id = readUnsigned(file, version == 2 ? 4 : 2, box.end());
            long method = version == 0 ? 0 : readUnsigned(file, 2, box.end()) & 15;
            readUnsigned(file, 2, box.end()); // data reference index
            long base = readUnsigned(file, baseSize, box.end());
            long extentCount = readUnsigned(file, 2, box.end());
            if (extentCount > 10000) throw new Exception("Too many HEIC item extents");
            for (long extent = 0; extent < extentCount; extent++) {
                readUnsigned(file, indexSize, box.end());
                long offsetField = file.getFilePointer();
                long offset = readUnsigned(file, offsetSize, box.end());
                long lengthField = file.getFilePointer();
                long length = readUnsigned(file, lengthSize, box.end());
                if (id == xmpId) {
                    if (found != null || method != 0 || base != 0 || extentCount != 1 ||
                        offset < mdat.payload() || length < 1 ||
                        offset > mdat.end() || length > mdat.end() - offset)
                        throw new Exception("Unsupported HEIC XMP extent");
                    found = new Layout(mdat, offsetField, lengthField, offsetSize, lengthSize,
                        offset, length);
                }
            }
        }
        if (file.getFilePointer() != box.end() || found == null)
            throw new Exception("HEIC XMP item has no file location");
        return found;
    }

    private static String cString(RandomAccessFile file, long end) throws Exception {
        StringBuilder value = new StringBuilder();
        while (file.getFilePointer() < end && value.length() < 1024) {
            int next = file.readUnsignedByte();
            if (next == 0) return value.toString();
            if (next > 127) throw new Exception("Non-ASCII HEIC item label");
            value.append((char) next);
        }
        throw new Exception("Invalid HEIC item label");
    }

    private static Box box(RandomAccessFile file, long start, long limit) throws Exception {
        if (start < 0 || limit - start < 8) throw new Exception("Truncated HEIC box");
        file.seek(start);
        long size = Integer.toUnsignedLong(file.readInt());
        int type = file.readInt();
        int header = 8;
        boolean extendsToEnd = size == 0;
        if (size == 1) {
            if (limit - start < 16) throw new Exception("Truncated HEIC extended box");
            size = file.readLong();
            header = 16;
        } else if (size == 0) size = limit - start;
        if (size < header || size > limit - start) throw new Exception("Invalid HEIC box size");
        return new Box(start, size, type, header, extendsToEnd);
    }

    private static long readUnsigned(RandomAccessFile file, int size, long end) throws Exception {
        if (size == 0) return 0;
        if (size < 0 || size > 8 || end - file.getFilePointer() < size)
            throw new Exception("Truncated HEIC item field");
        long value = 0;
        for (int i = 0; i < size; i++) value = (value << 8) | file.readUnsignedByte();
        if (value < 0) throw new Exception("HEIC item field exceeds signed range");
        return value;
    }

    private static void writeUnsigned(RandomAccessFile file, int size, long value) throws Exception {
        if (!fits(value, size)) throw new Exception("HEIC field overflow");
        for (int shift = (size - 1) * 8; shift >= 0; shift -= 8)
            file.write((int) (value >>> shift) & 255);
    }

    private static boolean fits(long value, int size) {
        return value >= 0 && size >= 1 && size <= 8 &&
            (size == 8 || (value >>> (size * 8)) == 0);
    }

    private static int fourcc(String name) {
        return (name.charAt(0) << 24) | (name.charAt(1) << 16) |
            (name.charAt(2) << 8) | name.charAt(3);
    }

    private static final class Box {
        final long start, size;
        final int type, header;
        final boolean extendsToEnd;
        Box(long start, long size, int type, int header, boolean extendsToEnd) {
            this.start = start; this.size = size; this.type = type;
            this.header = header; this.extendsToEnd = extendsToEnd;
        }
        long payload() { return start + header; }
        long end() { return start + size; }
    }

    private static final class Layout {
        final Box mdat;
        final long offsetField, lengthField, xmpOffset, xmpLength;
        final int offsetSize, lengthSize;
        Layout(Box mdat, long offsetField, long lengthField, int offsetSize, int lengthSize,
               long xmpOffset, long xmpLength) {
            this.mdat = mdat; this.offsetField = offsetField; this.lengthField = lengthField;
            this.offsetSize = offsetSize; this.lengthSize = lengthSize;
            this.xmpOffset = xmpOffset; this.xmpLength = xmpLength;
        }
    }
}
