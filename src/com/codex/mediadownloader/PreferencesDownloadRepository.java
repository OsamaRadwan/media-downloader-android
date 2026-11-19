package com.codex.mediadownloader;

import android.content.Context;
import java.util.ArrayList;
import java.util.List;

/** Adapts the installed SharedPreferences format without changing or migrating it. */
final class PreferencesDownloadRepository implements DownloadRepository {
    private final Context context;

    PreferencesDownloadRepository(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override public List<DownloadItem> list() {
        List<DownloadItem> items = new ArrayList<>();
        for (DownloadStore.Entry entry : DownloadStore.list(context)) {
            List<DownloadItem.PhotoItem> photos = new ArrayList<>();
            for (DownloadStore.Entry.Photo photo : entry.photos) {
                if (!photo.savedUri.isEmpty() && photo.thumbnailPath.isEmpty())
                    DownloadThumbnail.ensureAlbumPhotoAsync(context, entry.id, photo.index, photo.savedUri);
                photos.add(new DownloadItem.PhotoItem(photo.index, photo.width, photo.height,
                photo.sourceWidth, photo.sourceHeight, photo.bytes,
                photo.fileName, photo.savedUri, photo.error, photo.mime, photo.thumbnailPath));
            }
            items.add(new DownloadItem(entry.id, entry.createdAt, entry.updatedAt, entry.finishedAt,
                entry.publishTimeSeconds, entry.downloadedBytes, entry.totalBytes, entry.speedBytesPerSecond,
                entry.durationMs, entry.width, entry.height, entry.streamOption, entry.state, entry.stage,
                entry.profileName, entry.accountId, entry.videoId, entry.shareUrl, entry.fileName, entry.thumbnailPath,
                entry.savedUri, entry.error, entry.videoMime, entry.audioMime, entry.mediaType,
                entry.sourceUrl, entry.postTitle, entry.caption, entry.hashtags, photos));
        }
        return items;
    }

    @Override public void importExistingAsync() {
        DownloadStore.importExistingAsync(context);
    }
}
