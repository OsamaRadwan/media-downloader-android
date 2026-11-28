package com.codex.mediadownloader;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Rejects a response whose declared bytes do not form one complete media file. */
final class MediaResponseLength {
    private static final Pattern RANGE = Pattern.compile("bytes\\s+(\\d+)-(\\d+)/(\\d+)",
        Pattern.CASE_INSENSITIVE);

    private MediaResponseLength() { }

    static void verify(int status, long contentLength, String contentRange, long written)
        throws IOException {
        if (written < 0 || (contentLength >= 0 && written != contentLength))
            throw new IOException("Media response ended before its declared byte length");
        String declaredRange = contentRange == null ? "" : contentRange.trim();
        if (status == 200 && declaredRange.isEmpty()) return;
        if (status != 200 && status != 206)
            throw new IOException("Media response was not a complete file");
        Matcher range = RANGE.matcher(declaredRange);
        if (!range.matches())
            throw new IOException("Partial media response has no valid Content-Range");
        try {
            long first = Long.parseLong(range.group(1));
            long last = Long.parseLong(range.group(2));
            long total = Long.parseLong(range.group(3));
            if (first != 0 || last < first || total < 1 || last != total - 1 || written != total)
                throw new IOException("Partial media response did not cover the complete file");
        } catch (NumberFormatException error) {
            throw new IOException("Partial media response has an invalid Content-Range", error);
        }
    }
}
