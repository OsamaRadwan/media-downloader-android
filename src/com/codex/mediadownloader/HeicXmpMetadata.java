package com.codex.mediadownloader;

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Set;

/** Updates or adds a primary-image XMP item without re-encoding HEIC image data. */
final class HeicXmpMetadata {
    private static final int MAX_XMP_BYTES = 1_000_000;
    private static final int MAX_META_BYTES = 4_000_000;

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

    /** Adds an item only to the simple single-meta, final-mdat layout we can rewrite safely. */
    static void create(File file, String packet) throws Exception {
        if (!PhotoSourceMetadata.ascii(packet)) throw new Exception("HEIC XMP is not ASCII-safe");
        byte[] xmp = packet.getBytes(StandardCharsets.US_ASCII);
        if (xmp.length < 1 || xmp.length > MAX_XMP_BYTES)
            throw new Exception("Unsupported HEIC XMP length");
        File temporary = File.createTempFile("heic-xmp-", ".tmp", file.getParentFile());
        boolean replaced = false;
        try {
            try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
                long length = input.length();
                Box ftyp = box(input, 0, length);
                Box meta = box(input, ftyp.end(), length);
                Box mdat = box(input, meta.end(), length);
                if (ftyp.type != fourcc("ftyp") || meta.type != fourcc("meta") ||
                    mdat.type != fourcc("mdat") || mdat.end() != length ||
                    ftyp.header != 8 || meta.header != 8 ||
                    meta.size > MAX_META_BYTES || mdat.extendsToEnd)
                    throw new Exception("Unsupported HEIC container layout for new XMP");
                Box pitm = null, iinf = null, iref = null, iloc = null;
                for (long at = meta.payload() + 4; at < meta.end();) {
                    Box child = box(input, at, meta.end());
                    if (child.type == fourcc("pitm")) pitm = unique(pitm, child);
                    if (child.type == fourcc("iinf")) iinf = unique(iinf, child);
                    if (child.type == fourcc("iref")) iref = unique(iref, child);
                    if (child.type == fourcc("iloc")) iloc = unique(iloc, child);
                    at = child.end();
                }
                if (pitm == null || iinf == null || iref == null || iloc == null ||
                    iinf.header != 8 || iref.header != 8 || iloc.header != 8)
                    throw new Exception("HEIC image metadata is incomplete");
                long primary = primary(input, pitm);
                ItemIds ids = itemIds(input, iinf);
                if (ids.hasXmp || !ids.ids.contains(primary) || ids.maximum >= 65535)
                    throw new Exception("HEIC cannot accept a new XMP item ID");
                long newId = ids.maximum + 1;
                byte[] newIinf = addItemInfo(input, iinf, newId);
                byte[] newIref = addReference(input, iref, newId, primary);
                LocationEdit edit = addLocation(input, iloc, mdat, primary, newId, xmp.length);
                byte[] newMeta = rebuildMeta(input, meta, iinf, newIinf, iref, newIref,
                    iloc, edit.bytes);
                long delta = newMeta.length - meta.size;
                long xmpOffset = length + delta;
                for (OffsetField field : edit.offsets)
                    putUnsigned(edit.bytes, field.position, edit.offsetSize, field.value + delta);
                putUnsigned(edit.bytes, edit.newOffsetField, edit.offsetSize, xmpOffset);
                newMeta = rebuildMeta(input, meta, iinf, newIinf, iref, newIref,
                    iloc, edit.bytes);
                long newMdatSize = mdat.size + xmp.length;
                if (newMdatSize < mdat.size || mdat.header == 8 && !fits(newMdatSize, 4))
                    throw new Exception("HEIC media box exceeds its field size");
                try (FileOutputStream output = new FileOutputStream(temporary)) {
                    copy(input, output, 0, ftyp.size);
                    output.write(newMeta);
                    input.seek(mdat.start);
                    byte[] header = new byte[mdat.header];
                    input.readFully(header);
                    putUnsigned(header, mdat.header == 16 ? 8 : 0,
                        mdat.header == 16 ? 8 : 4, newMdatSize);
                    output.write(header);
                    copy(input, output, mdat.payload(), mdat.size - mdat.header);
                    output.write(xmp);
                    output.getFD().sync();
                }
            }
            try {
                if (!packet.equals(read(temporary)))
                    throw new java.io.IOException("New HEIC XMP did not round-trip");
            } catch (java.io.IOException failure) {
                throw failure;
            } catch (Exception invalid) {
                throw new java.io.IOException("New HEIC XMP could not be verified", invalid);
            }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            replaced = true;
        } finally {
            if (!replaced) temporary.delete();
        }
    }

    private static ItemIds itemIds(RandomAccessFile file, Box box) throws Exception {
        file.seek(box.payload());
        int version = file.readUnsignedByte();
        file.skipBytes(3);
        if (version != 0 && version != 1) throw new Exception("Unsupported HEIC item-info version");
        long count = readUnsigned(file, version == 0 ? 2 : 4, box.end());
        if (count > 10000) throw new Exception("Too many HEIC items");
        ItemIds result = new ItemIds();
        int seen = 0;
        for (long at = file.getFilePointer(); at < box.end();) {
            Box entry = box(file, at, box.end());
            if (entry.type != fourcc("infe")) throw new Exception("Unsupported HEIC item-info entry");
            seen++;
            file.seek(entry.payload());
            int entryVersion = file.readUnsignedByte();
            file.skipBytes(3);
            if (entryVersion != 2 && entryVersion != 3)
                throw new Exception("Unsupported HEIC item-info entry version");
            long id = readUnsigned(file, entryVersion == 2 ? 2 : 4, entry.end());
            if (!result.ids.add(id)) throw new Exception("Duplicate HEIC item ID");
            result.maximum = Math.max(result.maximum, id);
            readUnsigned(file, 2, entry.end());
            if (readUnsigned(file, 4, entry.end()) == fourcc("mime")) {
                cString(file, entry.end());
                if ("application/rdf+xml".equals(cString(file, entry.end()))) result.hasXmp = true;
            }
            at = entry.end();
        }
        if (seen != count) throw new Exception("HEIC item-info count mismatch");
        return result;
    }

    private static byte[] addItemInfo(RandomAccessFile file, Box box, long id) throws Exception {
        byte[] original = bytes(file, box);
        int version = original[8] & 255;
        int countSize = version == 0 ? 2 : 4;
        long count = unsigned(original, 12, countSize);
        if (!fits(count + 1, countSize)) throw new Exception("HEIC item-info count overflow");
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(payload);
        out.writeInt(0x02000000);
        out.writeShort((int) id);
        out.writeShort(0);
        out.writeBytes("mime");
        out.writeBytes("XMP"); out.writeByte(0);
        out.writeBytes("application/rdf+xml"); out.writeByte(0);
        byte[] added = boxBytes("infe", payload.toByteArray());
        byte[] result = append(original, added);
        putUnsigned(result, 0, 4, result.length);
        putUnsigned(result, 12, countSize, count + 1);
        return result;
    }

    private static byte[] addReference(RandomAccessFile file, Box box, long id,
                                       long primary) throws Exception {
        byte[] original = bytes(file, box);
        int version = original[8] & 255;
        if (version != 0 && version != 1) throw new Exception("Unsupported HEIC reference version");
        int width = version == 0 ? 2 : 4;
        if (!fits(id, width) || !fits(primary, width))
            throw new Exception("HEIC reference ID overflow");
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(payload);
        writeUnsigned(out, width, id);
        out.writeShort(1);
        writeUnsigned(out, width, primary);
        byte[] result = append(original, boxBytes("cdsc", payload.toByteArray()));
        putUnsigned(result, 0, 4, result.length);
        return result;
    }

    private static LocationEdit addLocation(RandomAccessFile file, Box box, Box mdat,
                                            long primary, long id, int xmpLength) throws Exception {
        byte[] original = bytes(file, box);
        int version = original[8] & 255;
        if (version > 2) throw new Exception("Unsupported HEIC location version");
        int offsetSize = (original[12] & 255) >> 4;
        int lengthSize = original[12] & 15;
        int baseSize = (original[13] & 255) >> 4;
        int indexSize = version == 0 ? 0 : original[13] & 15;
        if (offsetSize < 1 || offsetSize > 8 || lengthSize < 1 || lengthSize > 8 ||
            baseSize != 0 || indexSize != 0 || !fits(xmpLength, lengthSize))
            throw new Exception("Unsupported HEIC location field widths");
        int countSize = version == 2 ? 4 : 2;
        long count = unsigned(original, 14, countSize);
        if (count > 10000 || !fits(count + 1, countSize))
            throw new Exception("HEIC location count overflow");
        int position = 14 + countSize;
        java.util.ArrayList<OffsetField> offsets = new java.util.ArrayList<>();
        boolean primaryLocated = false;
        for (long i = 0; i < count; i++) {
            int idSize = version == 2 ? 4 : 2;
            long itemId = unsigned(original, position, idSize); position += idSize;
            int method = version == 0 ? 0 : (int) unsigned(original, position, 2) & 15;
            if (version != 0) position += 2;
            long reference = unsigned(original, position, 2); position += 2;
            long extents = unsigned(original, position, 2); position += 2;
            if (reference != 0 || extents > 10000 || method > 1)
                throw new Exception("Unsupported HEIC item location");
            if (itemId == id) throw new Exception("Duplicate HEIC item location");
            if (itemId == primary) primaryLocated = true;
            for (long e = 0; e < extents; e++) {
                long offset = unsigned(original, position, offsetSize);
                if (method == 0) {
                    if (offset < mdat.payload() || offset > mdat.end())
                        throw new Exception("HEIC item is outside the media box");
                    offsets.add(new OffsetField(position, offset));
                }
                position += offsetSize;
                long length = unsigned(original, position, lengthSize); position += lengthSize;
                if (method == 0 && length > mdat.end() - offset)
                    throw new Exception("HEIC item extends past the media box");
            }
        }
        if (position != original.length || !primaryLocated)
            throw new Exception("HEIC locations do not match their count");
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(payload);
        writeUnsigned(out, version == 2 ? 4 : 2, id);
        if (version != 0) out.writeShort(0); // file construction method
        out.writeShort(0); // data reference index
        out.writeShort(1); // extent count
        int newOffsetField = original.length + payload.size();
        writeUnsigned(out, offsetSize, 0);
        writeUnsigned(out, lengthSize, xmpLength);
        byte[] result = append(original, payload.toByteArray());
        putUnsigned(result, 0, 4, result.length);
        putUnsigned(result, 14, countSize, count + 1);
        return new LocationEdit(result, offsets, newOffsetField, offsetSize);
    }

    private static byte[] rebuildMeta(RandomAccessFile file, Box meta, Box iinf, byte[] info,
                                      Box iref, byte[] references, Box iloc,
                                      byte[] locations) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] prefix = new byte[12];
        file.seek(meta.start); file.readFully(prefix);
        output.write(prefix);
        for (long at = meta.payload() + 4; at < meta.end();) {
            Box child = box(file, at, meta.end());
            output.write(at == iinf.start ? info : at == iref.start ? references :
                at == iloc.start ? locations : bytes(file, child));
            at = child.end();
        }
        byte[] result = output.toByteArray();
        putUnsigned(result, 0, 4, result.length);
        return result;
    }

    private static byte[] bytes(RandomAccessFile file, Box box) throws Exception {
        if (box.size > MAX_META_BYTES) throw new Exception("HEIC metadata box is too large");
        byte[] result = new byte[(int) box.size];
        file.seek(box.start); file.readFully(result);
        return result;
    }

    private static byte[] boxBytes(String type, byte[] payload) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(payload.length + 8);
        out.writeBytes(type);
        out.write(payload);
        return bytes.toByteArray();
    }

    private static byte[] append(byte[] first, byte[] second) {
        byte[] result = java.util.Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    private static long unsigned(byte[] data, int position, int width) throws Exception {
        if (position < 0 || width < 1 || width > 8 || position + width > data.length)
            throw new Exception("Truncated HEIC item field");
        long value = 0;
        for (int i = 0; i < width; i++) value = (value << 8) | (data[position + i] & 255);
        if (value < 0) throw new Exception("HEIC item field exceeds signed range");
        return value;
    }

    private static void putUnsigned(byte[] data, int position, int width, long value)
            throws Exception {
        if (!fits(value, width) || position < 0 || position + width > data.length)
            throw new Exception("HEIC field overflow");
        for (int i = width - 1; i >= 0; i--) {
            data[position + i] = (byte) value;
            value >>>= 8;
        }
    }

    private static void writeUnsigned(DataOutputStream output, int width, long value)
            throws Exception {
        if (!fits(value, width)) throw new Exception("HEIC field overflow");
        for (int i = width - 1; i >= 0; i--) output.write((int) (value >>> (8 * i)) & 255);
    }

    private static void copy(RandomAccessFile input, FileOutputStream output, long at,
                             long length) throws Exception {
        input.seek(at);
        byte[] buffer = new byte[64 * 1024];
        while (length > 0) {
            int count = (int) Math.min(length, buffer.length);
            input.readFully(buffer, 0, count);
            output.write(buffer, 0, count);
            length -= count;
        }
    }

    private static final class ItemIds {
        final Set<Long> ids = new HashSet<>();
        long maximum;
        boolean hasXmp;
    }

    private static final class OffsetField {
        final int position;
        final long value;
        OffsetField(int position, long value) { this.position = position; this.value = value; }
    }

    private static final class LocationEdit {
        final byte[] bytes;
        final java.util.ArrayList<OffsetField> offsets;
        final int newOffsetField, offsetSize;
        LocationEdit(byte[] bytes, java.util.ArrayList<OffsetField> offsets,
                     int newOffsetField, int offsetSize) {
            this.bytes = bytes; this.offsets = offsets;
            this.newOffsetField = newOffsetField; this.offsetSize = offsetSize;
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
