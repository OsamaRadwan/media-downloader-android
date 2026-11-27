package com.codex.mediadownloader;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.Intent;
import android.net.Uri;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Adapts the installed SharedPreferences format without changing or migrating it. */
final class PreferencesDownloadRepository implements DownloadRepository {
    private final Context context;
    private final SharedPreferences history;
    private final SharedPreferences.OnSharedPreferenceChangeListener changes;
    private final Object indexLock = new Object();
    private final Map<Integer, IndexedEntry> indexed = new HashMap<>();
    private final Set<Integer> changedIds = new HashSet<>();
    private boolean indexedOnce;
    private static final Map<String, FileState> fileStates = new ConcurrentHashMap<>();
    private static final ExecutorService fileChecks = Executors.newSingleThreadExecutor();
    private static final long FILE_CHECK_INTERVAL_MS = 20_000;

    private static final class IndexedEntry {
        final DownloadStore.Entry entry;
        final String searchText;
        IndexedEntry(DownloadStore.Entry entry, String searchText) {
            this.entry = entry;
            this.searchText = searchText;
        }
    }

    private static final class FileState {
        final String name;
        final long bytes;
        volatile String resolvedUri = "";
        volatile long checkedAt = -FILE_CHECK_INTERVAL_MS;
        volatile boolean checking;
        FileState(String name, long bytes, String uri) {
            this.name = name; this.bytes = bytes;
            this.resolvedUri = uri == null ? "" : uri;
        }
    }

    PreferencesDownloadRepository(Context context) {
        this.context = context.getApplicationContext();
        history = this.context.getSharedPreferences("download_history", Context.MODE_PRIVATE);
        changes = (prefs, key) -> {
            if (key == null || !key.startsWith("job_")) return;
            try {
                int id = Integer.parseInt(key.substring(4));
                synchronized (indexLock) { changedIds.add(id); }
            } catch (NumberFormatException ignored) { }
        };
        history.registerOnSharedPreferenceChangeListener(changes);
    }

    static void clearFileChecks() { fileStates.clear(); }

    @Override public List<DownloadItem> list() {
        List<DownloadItem> items = new ArrayList<>();
        for (IndexedEntry indexedEntry : snapshot()) items.add(toItem(indexedEntry.entry, true));
        return items;
    }

    @Override public DownloadsState page(String query, int requestedPage, int focusId) {
        List<IndexedEntry> all = snapshot();
        String[] terms = DownloadHistoryQuery.terms(query);
        List<IndexedEntry> matched = new ArrayList<>();
        for (IndexedEntry indexedEntry : all)
            if (DownloadHistoryQuery.matchesText(indexedEntry.searchText, terms)) matched.add(indexedEntry);
        int page = Math.max(0, Math.min(requestedPage,
            Math.max(0, (matched.size() - 1) / DownloadsController.PAGE_SIZE)));
        if (focusId > 0) for (int i = 0; i < matched.size(); i++)
            if (matched.get(i).entry.id == focusId) { page = i / DownloadsController.PAGE_SIZE; break; }
        int start = page * DownloadsController.PAGE_SIZE;
        List<DownloadItem> visible = new ArrayList<>();
        for (int i = start; i < Math.min(start + DownloadsController.PAGE_SIZE, matched.size()); i++)
            visible.add(toItem(matched.get(i).entry, true));
        int active = 0, complete = 0, failed = 0, missing = 0;
        for (IndexedEntry indexedEntry : all) {
            DownloadStore.Entry entry = indexedEntry.entry;
            if (isActive(entry.state)) active++;
            else if (knownMissing(entry)) missing++;
            else if ("complete".equals(entry.state)) complete++;
            else failed++;
        }
        return new DownloadsState(visible, active, complete, failed, missing,
            all.size(), matched.size(), page);
    }

    private List<IndexedEntry> snapshot() {
        if (!indexedOnce) {
            List<DownloadStore.Entry> initial = DownloadStore.list(context);
            synchronized (indexLock) {
                for (DownloadStore.Entry entry : initial)
                    indexed.put(entry.id, new IndexedEntry(entry,
                        DownloadHistoryQuery.searchable(toItem(entry, false))));
                indexedOnce = true;
            }
        }
        Set<Integer> changed;
        synchronized (indexLock) {
            changed = new HashSet<>(changedIds);
            changedIds.clear();
        }
        for (int id : changed) {
            DownloadStore.Entry entry = DownloadStore.find(context, id);
            synchronized (indexLock) {
                if (entry == null) indexed.remove(id);
                else indexed.put(id, new IndexedEntry(entry,
                    DownloadHistoryQuery.searchable(toItem(entry, false))));
            }
        }
        List<IndexedEntry> result;
        synchronized (indexLock) { result = new ArrayList<>(indexed.values()); }
        Collections.sort(result, (a, b) -> Long.compare(b.entry.createdAt, a.entry.createdAt));
        return result;
    }

    private DownloadItem toItem(DownloadStore.Entry entry, boolean verifyFiles) {
        List<DownloadItem.PhotoItem> photos = new ArrayList<>();
        for (DownloadStore.Entry.Photo photo : entry.photos) {
            String resolved = verifyFiles ? resolvedUri(photo.savedUri, photo.fileName, photo.bytes) : photo.savedUri;
            boolean present = resolved != null && !resolved.isEmpty();
            if (verifyFiles && present && photo.thumbnailPath.isEmpty())
                DownloadThumbnail.ensureAlbumPhotoAsync(context, entry.id, photo.index, resolved);
            photos.add(new DownloadItem.PhotoItem(photo.index, photo.width, photo.height,
                photo.sourceWidth, photo.sourceHeight, photo.bytes, photo.fileName,
                present ? resolved : photo.savedUri, photo.error, photo.mime, photo.thumbnailPath,
                present, photo.kind, photo.videoMime, photo.audioMime, photo.durationMs));
        }
        String resolved = verifyFiles ? resolvedUri(entry.savedUri, entry.fileName,
            entry.downloadedBytes > 0 ? entry.downloadedBytes : entry.totalBytes) : entry.savedUri;
        boolean present = resolved != null && !resolved.isEmpty();
        return new DownloadItem(entry.id, entry.createdAt, entry.updatedAt, entry.finishedAt,
            entry.publishTimeSeconds, entry.downloadedBytes, entry.totalBytes, entry.speedBytesPerSecond,
            entry.durationMs, entry.width, entry.height, entry.streamOption, entry.state, entry.stage,
            entry.profileName, entry.accountId, entry.videoId, entry.shareUrl, entry.fileName, entry.thumbnailPath,
            present ? resolved : entry.savedUri, entry.error, entry.videoMime, entry.audioMime, entry.mediaType,
            entry.sourceUrl, entry.postTitle, entry.caption, entry.hashtags, photos, present);
    }

    private boolean knownMissing(DownloadStore.Entry entry) {
        if (!"complete".equals(entry.state)) return false;
        if ("album".equals(entry.mediaType)) {
            if (entry.photos.isEmpty()) return true;
            for (DownloadStore.Entry.Photo photo : entry.photos)
                if (checkedMissing(photo.savedUri, photo.fileName, photo.bytes)) return true;
            return false;
        }
        return checkedMissing(entry.savedUri, entry.fileName,
            entry.downloadedBytes > 0 ? entry.downloadedBytes : entry.totalBytes);
    }

    private static boolean isActive(String state) {
        return !"complete".equals(state) && !"failed".equals(state) &&
            !"partial".equals(state) && !"interrupted".equals(state);
    }

    private static boolean checkedMissing(String uri, String name, long bytes) {
        if (name == null || name.isEmpty() || bytes <= 0) return true;
        FileState state = fileStates.get((uri == null ? "" : uri) + "|" + name);
        return state != null && state.checkedAt >= 0 && state.resolvedUri.isEmpty();
    }

    @Override public void importExistingAsync() {
        DownloadStore.importExistingAsync(context);
    }

    @Override public boolean relink(int jobId, int photoIndex, Uri selected) {
        DownloadStore.Entry entry = DownloadStore.find(context, jobId);
        if (entry == null || selected == null) return false;
        if (photoIndex > 0) {
            if (!"album".equals(entry.mediaType)) return false;
            DownloadStore.Entry.Photo photo = null;
            for (DownloadStore.Entry.Photo candidate : entry.photos)
                if (candidate.index == photoIndex) { photo = candidate; break; }
            if (photo == null) return false;
            long recoveredBytes = photo.bytes == 0 && "image/jpeg".equals(photo.mime) ?
                SavedMedia.recoverSelectedJpegSize(context, selected, photo.fileName,
                    photo.width, photo.height, entry.sourceUrl) : 0;
            if (recoveredBytes == 0 && !SavedMedia.matchesSelected(context, selected,
                photo.fileName, photo.bytes)) return false;
            try { context.getContentResolver().takePersistableUriPermission(selected,
                Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch (Exception denied) { return false; }
            if (recoveredBytes > 0) photo.bytes = recoveredBytes;
            photo.savedUri = selected.toString();
            photo.error = "";
            if (!entry.isActive() && SavedMedia.availablePhotos(context, entry) == entry.photos.size()) {
                long total = 0;
                for (DownloadStore.Entry.Photo saved : entry.photos) total += saved.bytes;
                entry.downloadedBytes = total;
                entry.totalBytes = total;
                entry.state = "complete";
                entry.stage = "Complete";
                entry.error = "";
                if (entry.finishedAt == 0) entry.finishedAt = System.currentTimeMillis();
            }
        } else {
            if ("album".equals(entry.mediaType)) return false;
            long bytes = entry.downloadedBytes > 0 ? entry.downloadedBytes : entry.totalBytes;
            if (!SavedMedia.matchesSelected(context, selected, entry.fileName, bytes)) return false;
            try { context.getContentResolver().takePersistableUriPermission(selected,
                Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch (Exception denied) { return false; }
            entry.savedUri = selected.toString();
        }
        DownloadStore.save(context, entry, true);
        clearFileChecks();
        return true;
    }

    private String resolvedUri(String uri, String name, long bytes) {
        if (name == null || name.isEmpty() || bytes <= 0) return "";
        String key = uri == null ? "" : uri;
        FileState state = fileStates.get(key + "|" + name);
        if (state == null || !state.name.equals(name) || state.bytes != bytes) {
            state = new FileState(name, bytes, uri);
            fileStates.put(key + "|" + name, state);
        }
        final FileState check = state;
        long now = android.os.SystemClock.elapsedRealtime();
        if (!check.checking && now - check.checkedAt >= FILE_CHECK_INTERVAL_MS) {
            check.checking = true;
            fileChecks.execute(() -> {
                check.resolvedUri = SavedMedia.resolve(context, uri, name, bytes);
                check.checkedAt = android.os.SystemClock.elapsedRealtime();
                check.checking = false;
            });
        }
        return check.resolvedUri;
    }
}
