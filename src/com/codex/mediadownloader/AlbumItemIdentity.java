package com.codex.mediadownloader;

/** Checks a resolved album item against the row retained by an earlier attempt. */
final class AlbumItemIdentity {
    private AlbumItemIdentity() { }

    static void validate(StreamResolver.AlbumItem item, int index, String kind,
                         String sourceId) throws Exception {
        String resolvedKind = item.video == null ? "photo" : "video";
        if (item.index != index || !resolvedKind.equals(kind) ||
            (sourceId != null && !sourceId.isEmpty() && !sourceId.equals(item.sourceId)))
            throw new Exception("Album items changed since the earlier download; retry as a new job");
    }
}
