package com.codex.mediadownloader;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class DownloadsPage {
    static final int RESTORED_FILE_REQUEST = 88;
    private static final ExecutorService historyWorker = Executors.newSingleThreadExecutor();
    private static final int PAGE = AppPalette.PAGE;
    private static final int CARD = AppPalette.CARD;
    private static final int TEXT = AppPalette.TEXT;
    private static final int MUTED = AppPalette.MUTED;
    private static final int ACCENT = AppPalette.ACCENT;
    private static final int ERROR = AppPalette.ERROR;
    private final Activity activity;
    private final DownloadsController controller;
    private final ScrollView scroll;
    private final LinearLayout list;
    private final TextView summary;
    private final TextView empty;
    private final TextView resultCount;
    private final LinearLayout topPaging;
    private final LinearLayout bottomPaging;
    private final Button topPreviousPage;
    private final Button topNextPage;
    private final Button previousPage;
    private final Button nextPage;
    private final EditText search;
    private final Map<Integer, Row> rows = new HashMap<>();
    private int pageIndex;
    private int focusId;
    private boolean focused;
    private boolean loading;
    private boolean refreshPending;
    private int pendingFileJobId;
    private int pendingPhotoIndex;

    DownloadsPage(Activity activity, DownloadsController controller, int focusId) {
        this.activity = activity;
        this.controller = controller;
        this.focusId = focusId;
        scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(PAGE);
        LinearLayout page = new LinearLayout(activity);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(20), dp(20), dp(30));
        scroll.addView(page);
        text(page, "Downloads", 27, TEXT, true, 0);
        text(page, "Downloads continue in the background. Tap a notification to return to its details.", 14, MUTED, false, 7);
        summary = text(page, "Loading downloads…", 14, ACCENT, true, 18);
        LinearLayout searchRow = new LinearLayout(activity);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(-1, -2);
        searchParams.topMargin = dp(16);
        page.addView(searchRow, searchParams);
        search = new EditText(activity);
        search.setSingleLine(true);
        search.setHint("Search downloads and metadata");
        search.setTextColor(TEXT);
        search.setHintTextColor(MUTED);
        search.setTextSize(15);
        search.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        FrameLayout searchBox = new FrameLayout(activity);
        searchRow.addView(searchBox, new LinearLayout.LayoutParams(0, dp(48), 1));
        search.setPadding(dp(10), 0, dp(100), 0);
        searchBox.addView(search, new FrameLayout.LayoutParams(-1, -1));
        ImageButton paste = new ImageButton(activity);
        paste.setImageResource(activity.getResources().getIdentifier("ic_paste", "drawable",
            activity.getPackageName()));
        paste.setColorFilter(ACCENT);
        paste.setBackgroundColor(Color.TRANSPARENT);
        paste.setPadding(dp(12), dp(12), dp(12), dp(12));
        paste.setContentDescription("Paste clipboard into search");
        FrameLayout.LayoutParams pasteParams = new FrameLayout.LayoutParams(dp(48), dp(48),
            Gravity.END | Gravity.CENTER_VERTICAL);
        pasteParams.rightMargin = dp(48);
        searchBox.addView(paste, pasteParams);
        paste.setOnClickListener(v -> pasteSearch());
        ImageButton clear = new ImageButton(activity);
        clear.setImageResource(activity.getResources().getIdentifier("ic_close", "drawable",
            activity.getPackageName()));
        clear.setColorFilter(MUTED);
        clear.setBackgroundColor(Color.TRANSPARENT);
        clear.setPadding(dp(12), dp(12), dp(12), dp(12));
        clear.setContentDescription("Clear search");
        FrameLayout.LayoutParams clearParams = new FrameLayout.LayoutParams(dp(48), dp(48),
            Gravity.END | Gravity.CENTER_VERTICAL);
        searchBox.addView(clear, clearParams);
        clear.setEnabled(false);
        clear.setAlpha(0.45f);
        clear.setOnClickListener(v -> search.setText(""));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                clear.setEnabled(value.length() > 0);
                clear.setAlpha(value.length() == 0 ? 0.45f : 1f);
                pageIndex = 0;
                DownloadsPage.this.focusId = 0;
                focused = false;
                scroll.scrollTo(0, 0);
                refresh();
            }
            @Override public void afterTextChanged(Editable value) { }
        });
        resultCount = text(page, "", 13, MUTED, false, 8);
        topPaging = pagingRow(page);
        topPreviousPage = pageButton(topPaging, "Newer");
        topNextPage = pageButton(topPaging, "Older");
        topPreviousPage.setOnClickListener(v -> changePage(pageIndex - 1));
        topNextPage.setOnClickListener(v -> changePage(pageIndex + 1));
        topPaging.setVisibility(View.GONE);
        empty = text(page, "No downloads yet. Open a TikTok or Instagram post and tap the floating arrow.", 15, MUTED, false, 24);
        list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        page.addView(list);
        bottomPaging = pagingRow(page);
        previousPage = pageButton(bottomPaging, "Newer");
        nextPage = pageButton(bottomPaging, "Older");
        previousPage.setOnClickListener(v -> changePage(pageIndex - 1));
        nextPage.setOnClickListener(v -> changePage(pageIndex + 1));
        bottomPaging.setVisibility(View.GONE);
    }

    View view() { return scroll; }

    void focus(int id) {
        focusId = id;
        focused = false;
        for (Row row : rows.values()) row.root.setBackground(cardBackground(row.id == focusId));
    }

    void refresh() {
        if (loading) { refreshPending = true; return; }
        loading = true;
        String query = search.getText().toString();
        int requestedPage = pageIndex;
        int requestedFocus = focusId;
        historyWorker.execute(() -> {
            DownloadsState state;
            try { state = controller.load(query, requestedPage, requestedFocus); }
            catch (Exception error) {
                DiagnosticsStore.record(activity, "downloads.history", error);
                activity.runOnUiThread(() -> {
                    loading = false;
                    summary.setText("Could not load downloads");
                });
                return;
            }
            activity.runOnUiThread(() -> {
                loading = false;
                if (!query.equals(search.getText().toString()) || requestedPage != pageIndex ||
                    requestedFocus != focusId) {
                    refreshPending = false;
                    refresh();
                    return;
                }
                showState(state);
                if (refreshPending) {
                    refreshPending = false;
                    refresh();
                }
            });
        });
    }

    private void showState(DownloadsState state) {
        List<DownloadItem> entries = state.items;
        pageIndex = state.pageIndex;
        summary.setText(state.activeCount + " active  •  " + state.completeCount +
            " complete  •  " + state.failedCount + " failed" +
            (state.missingCount == 0 ? "" : "  •  " + state.missingCount + " missing"));
        resultCount.setText(state.matchedCount == 0 ? "0 results" :
            "Showing " + (pageIndex * DownloadsController.PAGE_SIZE + 1) + "–" +
            (pageIndex * DownloadsController.PAGE_SIZE + entries.size()) + " of " + state.matchedCount);
        empty.setText(state.totalCount == 0 ? "No downloads yet. Open a supported post and tap the floating arrow." :
            "No downloads match this search.");
        empty.setVisibility(state.matchedCount == 0 ? View.VISIBLE : View.GONE);
        boolean hasPages = state.matchedCount > DownloadsController.PAGE_SIZE;
        topPaging.setVisibility(hasPages ? View.VISIBLE : View.GONE);
        bottomPaging.setVisibility(hasPages ? View.VISIBLE : View.GONE);
        int newer = pageIndex > 0 ? View.VISIBLE : View.INVISIBLE;
        int older = (pageIndex + 1) * DownloadsController.PAGE_SIZE < state.matchedCount ?
            View.VISIBLE : View.INVISIBLE;
        topPreviousPage.setVisibility(newer);
        previousPage.setVisibility(newer);
        topNextPage.setVisibility(older);
        nextPage.setVisibility(older);
        Set<Integer> visible = new HashSet<>();
        for (int i = 0; i < entries.size(); i++) {
            DownloadItem entry = entries.get(i);
            visible.add(entry.id);
            Row row = rows.get(entry.id);
            if (row == null) {
                row = new Row(entry.id);
                rows.put(entry.id, row);
                list.addView(row.root, i, row.params);
            } else if (list.getChildAt(i) != row.root) {
                list.removeView(row.root);
                list.addView(row.root, i, row.params);
            }
            row.update(entry);
            if (focusId == entry.id && !focused) {
                Row target = row;
                scroll.post(() -> scroll.smoothScrollTo(0, list.getTop() + target.root.getTop()));
                focused = true;
            }
        }
        for (java.util.Iterator<Map.Entry<Integer, Row>> it = rows.entrySet().iterator(); it.hasNext();) {
            Map.Entry<Integer, Row> entry = it.next();
            if (!visible.contains(entry.getKey())) {
                list.removeView(entry.getValue().root);
                it.remove();
            }
        }
    }

    private LinearLayout pagingRow(LinearLayout page) {
        LinearLayout paging = new LinearLayout(activity);
        paging.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(44));
        params.topMargin = dp(18);
        page.addView(paging, params);
        return paging;
    }

    private Button pageButton(LinearLayout parent, String title) {
        Button button = new Button(activity);
        button.setText(title);
        button.setAllCaps(false);
        button.setTextColor(PAGE);
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, 1);
        params.leftMargin = dp(4);
        params.rightMargin = dp(4);
        parent.addView(button, params);
        return button;
    }

    private void changePage(int requestedPage) {
        pageIndex = Math.max(0, requestedPage);
        focusId = 0;
        focused = false;
        refresh();
        scroll.post(() -> scroll.scrollTo(0, 0));
    }

    private void pasteSearch() {
        ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = clipboard == null ? null : clipboard.getPrimaryClip();
        CharSequence value = clip == null || clip.getItemCount() == 0 ? null :
            clip.getItemAt(0).coerceToText(activity);
        if (value == null || value.length() == 0) {
            Toast.makeText(activity, "Clipboard has no text", Toast.LENGTH_SHORT).show();
            return;
        }
        search.setText(value);
        search.setSelection(search.length());
    }

    void onFilePicked(int resultCode, Intent data) {
        int jobId = pendingFileJobId;
        int photoIndex = pendingPhotoIndex;
        pendingFileJobId = 0;
        pendingPhotoIndex = 0;
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null || jobId <= 0) return;
        Uri selected = data.getData();
        historyWorker.execute(() -> {
            boolean linked = controller.relink(jobId, photoIndex, selected);
            activity.runOnUiThread(() -> {
                Toast.makeText(activity, linked ? "Saved file linked" :
                    "File name or size does not match, or access was denied", Toast.LENGTH_LONG).show();
                refresh();
            });
        });
    }

    private void pickRestoredFile(int jobId, int photoIndex, String mime) {
        pendingFileJobId = jobId;
        pendingPhotoIndex = photoIndex;
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try { activity.startActivityForResult(pick, RESTORED_FILE_REQUEST); }
        catch (Exception error) {
            pendingFileJobId = 0;
            Toast.makeText(activity, "Could not open the file picker", Toast.LENGTH_LONG).show();
        }
    }

    private final class Row {
        final int id;
        final LinearLayout root;
        final LinearLayout.LayoutParams params;
        final ImageView thumbnail;
        final TextView title;
        final TextView state;
        final TextView file;
        final TextView size;
        final TextView speed;
        final TextView toggle;
        final TextView details;
        final TextView error;
        final TextView tiktokLink;
        final LinearLayout albumPhotos;
        final ProgressBar progress;
        final Button open;
        final Button retryButton;
        final Button findFileButton;
        String shownThumbnail = "";
        int shownPhotoFingerprint;
        int shownAvailabilityFingerprint;
        long shownUpdatedAt;
        boolean expanded;

        Row(int id) {
            this.id = id;
            expanded = id == focusId;
            root = new LinearLayout(activity);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(dp(14), dp(14), dp(14), dp(14));
            root.setBackground(cardBackground(id == focusId));
            params = new LinearLayout.LayoutParams(-1, -2);
            params.topMargin = dp(12);

            LinearLayout top = new LinearLayout(activity);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);
            root.addView(top);
            thumbnail = new ImageView(activity);
            thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumbnail.setBackgroundColor(AppPalette.CARD_TOP);
            thumbnail.setImageResource(android.R.drawable.ic_menu_gallery);
            thumbnail.setColorFilter(MUTED);
            top.addView(thumbnail, new LinearLayout.LayoutParams(dp(82), dp(82)));
            LinearLayout labels = new LinearLayout(activity);
            labels.setOrientation(LinearLayout.VERTICAL);
            labels.setPadding(dp(12), 0, 0, 0);
            top.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
            title = text(labels, "", 18, TEXT, true, 0);
            state = text(labels, "", 14, ACCENT, true, 4);
            file = text(labels, "", 12, MUTED, false, 5);
            file.setMaxLines(3);
            file.setEllipsize(TextUtils.TruncateAt.END);

            progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
            progress.setMax(1000);
            progress.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
            progress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(ACCENT));
            progress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(AppPalette.BORDER));
            LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(-1, dp(8));
            progressParams.topMargin = dp(14);
            root.addView(progress, progressParams);
            size = text(root, "", 14, TEXT, false, 10);
            speed = text(root, "", 14, MUTED, false, 4);
            open = new Button(activity);
            open.setText("Open video");
            open.setAllCaps(false);
            open.setTextSize(14);
            open.setTextColor(PAGE);
            open.setMinWidth(0);
            open.setMinHeight(0);
            open.setPadding(dp(12), 0, dp(12), 0);
            open.setBackgroundTintList(android.content.res.ColorStateList.valueOf(ACCENT));
            LinearLayout.LayoutParams openParams = new LinearLayout.LayoutParams(-2, dp(40));
            openParams.gravity = Gravity.END;
            openParams.topMargin = dp(8);
            root.addView(open, openParams);
            retryButton = new Button(activity);
            retryButton.setText("Retry download");
            retryButton.setAllCaps(false);
            retryButton.setTextSize(14);
            retryButton.setTextColor(PAGE);
            retryButton.setMinHeight(0);
            retryButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(ACCENT));
            LinearLayout.LayoutParams retryParams = new LinearLayout.LayoutParams(-2, dp(40));
            retryParams.gravity = Gravity.END;
            retryParams.topMargin = dp(8);
            root.addView(retryButton, retryParams);
            findFileButton = new Button(activity);
            findFileButton.setText("Find saved video");
            findFileButton.setAllCaps(false);
            findFileButton.setTextSize(14);
            findFileButton.setTextColor(PAGE);
            findFileButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(ACCENT));
            LinearLayout.LayoutParams findParams = new LinearLayout.LayoutParams(-2, dp(40));
            findParams.gravity = Gravity.END;
            findParams.topMargin = dp(8);
            root.addView(findFileButton, findParams);
            toggle = text(root, "", 13, ACCENT, true, 10);
            details = text(root, "", 13, MUTED, false, 12);
            error = text(root, "", 13, ERROR, false, 10);
            albumPhotos = new LinearLayout(activity);
            albumPhotos.setOrientation(LinearLayout.VERTICAL);
            root.addView(albumPhotos);
            tiktokLink = text(root, "Open in TikTok ↗", 14, ACCENT, true, 10);
            tiktokLink.setPaintFlags(tiktokLink.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
            tiktokLink.setMinHeight(dp(48));
            tiktokLink.setGravity(Gravity.CENTER_VERTICAL);
            tiktokLink.setContentDescription("Open this post in TikTok");
            toggle.setOnClickListener(v -> {
                expanded = !expanded;
                toggle.setText(expanded ? "Hide details ▲" : "Show details ▼");
                details.setVisibility(expanded ? View.VISIBLE : View.GONE);
                albumPhotos.setVisibility(expanded ? View.VISIBLE : View.GONE);
            });
        }

        void update(DownloadItem entry) {
            int availabilityFingerprint = entry.savedFilePresent ? 1 : 0;
            for (DownloadItem.PhotoItem photo : entry.photos)
                availabilityFingerprint = 31 * availabilityFingerprint + (photo.filePresent ? 1 : 0);
            if (shownUpdatedAt == entry.updatedAt &&
                shownAvailabilityFingerprint == availabilityFingerprint && !entry.isActive()) return;
            shownUpdatedAt = entry.updatedAt;
            shownAvailabilityFingerprint = availabilityFingerprint;
            boolean album = "album".equals(entry.mediaType);
            boolean hasAlbumVideo = false;
            for (DownloadItem.PhotoItem item : entry.photos)
                if ("video".equals(item.kind)) { hasAlbumVideo = true; break; }
            final boolean mixedAlbum = hasAlbumVideo;
            boolean story = entry.sourceUrl.contains("/stories/") || entry.shareUrl.contains("/stories/");
            title.setText(entry.profileName.isEmpty() ?
                (entry.videoId.isEmpty() ? story ? "Story download" : "Download" :
                    (story ? "Story " : album ? "Album " : "Video ") + entry.videoId) :
                "@" + entry.profileName + (story ? album ? " • photo Story" : " • video Story" :
                    album ? hasAlbumVideo ? " • media album" : entry.photos.isEmpty() ? " • album" :
                        entry.photos.size() == 1 ? " • photo" : " • photo album" : ""));
            file.setText(entry.fileName.isEmpty() ? story ? "Reading Story details…" : "Reading post details…" : entry.fileName);
            int availablePhotos = 0;
            for (DownloadItem.PhotoItem photo : entry.photos) if (photo.filePresent) availablePhotos++;
            boolean missingMedia = entry.missingFile();
            state.setText(missingMedia ? album ? mixedAlbum ? "Album items missing or changed" :
                "Photos missing or changed" : "Video missing or changed" :
                entry.stage.isEmpty() ? entry.state : entry.stage);
            state.setTextColor(missingMedia || "failed".equals(entry.state) ||
                "partial".equals(entry.state) ? ERROR : ACCENT);
            if (album && !entry.photos.isEmpty()) {
                progress.setVisibility(View.VISIBLE);
                progress.setIndeterminate(false);
                progress.setProgress(availablePhotos * 1000 / entry.photos.size());
            } else if (entry.isActive()) {
                progress.setVisibility(View.VISIBLE);
                progress.setIndeterminate(entry.totalBytes <= 0);
                if (entry.totalBytes > 0) progress.setProgress((int) Math.min(1000,
                    entry.downloadedBytes * 1000 / entry.totalBytes));
            } else if ("complete".equals(entry.state) && !missingMedia) {
                progress.setVisibility(View.VISIBLE);
                progress.setIndeterminate(false);
                progress.setProgress(1000);
            } else {
                progress.setVisibility(View.GONE);
            }
            String amount;
            if (album && entry.photos.isEmpty()) amount = "No album items saved";
            else if (album) amount = (entry.photos.size() == 1 && availablePhotos == 1 ?
                "1 " + (mixedAlbum ? "item" : "photo") + " on phone" :
                availablePhotos + " of " + entry.photos.size() +
                    (mixedAlbum ? " items" : " photos") + " on phone") +
                " • " + bytes(entry.downloadedBytes);
            else {
                amount = missingMedia ? "Previously downloaded: " + bytes(entry.downloadedBytes) :
                    "Downloaded: " + bytes(entry.downloadedBytes) + " / " +
                    (entry.totalBytes > 0 ? bytes(entry.totalBytes) : "size unknown");
                if (!missingMedia && entry.totalBytes > 0)
                    amount += " (" + Math.min(100, entry.downloadedBytes * 100 / entry.totalBytes) + "%)";
            }
            size.setText(amount);
            if ("downloading".equals(entry.state)) {
                long currentSpeed = System.currentTimeMillis() - entry.updatedAt < 3000 ? entry.speedBytesPerSecond : 0;
                String speedLabel = "Speed: " + bytes(currentSpeed) + "/s";
                if (entry.totalBytes > 0 && currentSpeed > 0 && entry.downloadedBytes < entry.totalBytes) {
                    long remaining = (entry.totalBytes - entry.downloadedBytes) / currentSpeed;
                    speedLabel += "  •  About " + duration(remaining) + " left";
                }
                speed.setText(speedLabel);
                speed.setVisibility(View.VISIBLE);
            } else {
                speed.setVisibility(View.GONE);
            }
            StringBuilder metadata = new StringBuilder();
            if (!entry.videoId.isEmpty()) metadata.append(story ? "Story ID: " : album || entry.fromInstagram() || entry.fromRedNote() ? "Post ID: " : "Video ID: ").append(entry.videoId).append('\n');
            if (!entry.accountId.isEmpty()) metadata.append("Account ID: ").append(entry.accountId).append('\n');
            if (entry.publishTimeSeconds > 0) metadata.append("Published: ").append(date(entry.publishTimeSeconds * 1000L)).append('\n');
            if (entry.width > 0 && entry.height > 0) metadata.append("Resolution: ").append(entry.width).append(" × ").append(entry.height).append('\n');
            if (entry.durationMs > 0) metadata.append("Duration: ").append(duration(entry.durationMs / 1000L)).append('\n');
            if (!entry.videoMime.isEmpty()) metadata.append("Video: ").append(codec(entry.videoMime)).append('\n');
            if (!entry.audioMime.isEmpty()) metadata.append("Audio: ").append(codec(entry.audioMime)).append('\n');
            if (!entry.postTitle.isEmpty()) metadata.append("Title: ").append(entry.postTitle).append('\n');
            if (!entry.caption.isEmpty()) metadata.append("Caption: ").append(entry.caption).append('\n');
            if (!entry.hashtags.isEmpty()) metadata.append("Hashtags: ").append(entry.hashtags).append('\n');
            metadata.append("Started: ").append(date(entry.createdAt));
            if (entry.finishedAt > 0) metadata.append("\nFinished: ").append(date(entry.finishedAt));
            if ("complete".equals(entry.state) && !missingMedia) metadata.append("\nSaved in: Downloads");
            details.setText(metadata.toString());
            String displayError = missingMedia ? "Saved file name or size differs, or the file is missing" : entry.error;
            error.setVisibility(displayError.isEmpty() ? View.GONE : View.VISIBLE);
            error.setText(displayError.isEmpty() ? "" : missingMedia ? displayError : "Error: " + displayError);
            String postUrl = entry.postUrl();
            String appName = entry.fromInstagram() ? "Instagram" : entry.fromRedNote() ? "RedNote" : "TikTok";
            tiktokLink.setText("Open in " + appName + " ↗");
            tiktokLink.setContentDescription("Open this post in " + appName);
            tiktokLink.setVisibility(postUrl.isEmpty() ? View.GONE : View.VISIBLE);
            tiktokLink.setOnClickListener(v -> openPost(postUrl, appName));
            if (entry.isActive() && !expanded) expanded = true;
            toggle.setText(expanded ? "Hide details ▲" : "Show details ▼");
            details.setVisibility(expanded ? View.VISIBLE : View.GONE);
            DownloadItem.PhotoItem firstAlbumVideo = null;
            if (album) for (DownloadItem.PhotoItem item : entry.photos)
                if (item.filePresent && "video".equals(item.kind)) { firstAlbumVideo = item; break; }
            final DownloadItem.PhotoItem availableVideo = firstAlbumVideo;
            boolean canOpen = (!album && "complete".equals(entry.state) && entry.savedFilePresent) ||
                availableVideo != null;
            open.setVisibility(canOpen ? View.VISIBLE : View.GONE);
            open.setText(availableVideo == null ? "Open video" : "Open video " + availableVideo.index);
            open.setOnClickListener(v -> openVideo(availableVideo == null ? entry.savedUri : availableVideo.savedUri));
            DownloadItem.PhotoItem firstPhoto = null;
            if (album && !entry.photos.isEmpty() && entry.photos.get(0).filePresent)
                firstPhoto = entry.photos.get(0);
            final DownloadItem.PhotoItem availablePreview = firstPhoto;
            thumbnail.setClickable(canOpen || availablePreview != null);
            thumbnail.setContentDescription(availablePreview != null ?
                "Open downloaded " + availablePreview.kind :
                canOpen ? "Play downloaded video" : null);
            thumbnail.setOnClickListener(canOpen ? v -> openVideo(entry.savedUri) :
                availablePreview != null ? v -> {
                    if ("video".equals(availablePreview.kind)) openVideo(availablePreview.savedUri);
                    else openPhoto(availablePreview.savedUri, availablePreview.mime);
                } : null);
            boolean canRetry = DownloadRetry.allowed(entry.state, entry.mediaType, availablePhotos,
                entry.photos.size(), entry.savedFilePresent, entry.shareUrl, entry.sourceUrl, entry.videoId);
            retryButton.setVisibility(canRetry ? View.VISIBLE : View.GONE);
            retryButton.setText(missingMedia ? album ?
                mixedAlbum ? "Redownload missing items" : "Redownload missing photos" : "Redownload video" :
                album && !entry.photos.isEmpty() ?
                    mixedAlbum ? "Retry missing items" : "Retry missing photos" : "Retry download");
            retryButton.setEnabled(true);
            retryButton.setOnClickListener(v -> {
                Intent retry = new Intent(activity, DownloadService.class).putExtra("retryJobId", entry.id);
                retryButton.setEnabled(false);
                shownUpdatedAt = -1;
                try { activity.startForegroundService(retry); }
                catch (Exception startError) {
                    retryButton.setEnabled(true);
                    Toast.makeText(activity, "Could not start retry", Toast.LENGTH_LONG).show();
                }
            });
            findFileButton.setVisibility(missingMedia && !album ? View.VISIBLE : View.GONE);
            findFileButton.setOnClickListener(v -> pickRestoredFile(entry.id, 0, "video/*"));
            int photoFingerprint = 1;
            for (DownloadItem.PhotoItem photo : entry.photos)
                photoFingerprint = 31 * photoFingerprint +
                    (photo.savedUri + photo.error + photo.thumbnailPath + photo.filePresent +
                        photo.kind + photo.videoMime + photo.audioMime + photo.durationMs).hashCode();
            if (shownPhotoFingerprint != photoFingerprint) {
                shownPhotoFingerprint = photoFingerprint;
                albumPhotos.removeAllViews();
            if (album) for (DownloadItem.PhotoItem photo : entry.photos) {
                LinearLayout photoRow = new LinearLayout(activity);
                photoRow.setOrientation(LinearLayout.VERTICAL);
                photoRow.setPadding(0, dp(8), 0, dp(8));
                albumPhotos.addView(photoRow);
                if (!photo.thumbnailPath.isEmpty()) {
                    Bitmap preview = BitmapFactory.decodeFile(photo.thumbnailPath);
                    if (preview != null) {
                        ImageView photoPreview = new ImageView(activity);
                        photoPreview.setScaleType(ImageView.ScaleType.CENTER_CROP);
                        photoPreview.setImageBitmap(preview);
                        photoRow.addView(photoPreview, new LinearLayout.LayoutParams(dp(64), dp(64)));
                        photoPreview.setContentDescription("Open " + photo.kind + " " + photo.index);
                        if (photo.filePresent)
                            photoPreview.setOnClickListener(v -> {
                                if ("video".equals(photo.kind)) openVideo(photo.savedUri);
                                else openPhoto(photo.savedUri, photo.mime);
                            });
                    }
                }
                String label = ("video".equals(photo.kind) ? "Video " : "Photo ") +
                    photo.index + " / " + entry.photos.size() + " • " +
                    (photo.filePresent ? bytes(photo.bytes) + " • " + photo.width + " × " + photo.height :
                        !photo.savedUri.isEmpty() ? "missing or changed on phone" :
                        photo.error.isEmpty() ? "waiting" : "failed: " + photo.error);
                text(photoRow, label, 13, photo.filePresent || photo.error.isEmpty() && photo.savedUri.isEmpty() ?
                    TEXT : ERROR, true, 0);
                if (photo.filePresent && "photo".equals(photo.kind) &&
                    photo.sourceWidth > 0 && photo.sourceHeight > 0) {
                    boolean matches = PhotoQuality.matchesListed(photo.width, photo.height,
                        photo.sourceWidth, photo.sourceHeight);
                    text(photoRow, matches ? "Matches " + appName + "'s listed resolution" :
                        "Below " + appName + "'s listed " + photo.sourceWidth + " × " + photo.sourceHeight,
                        12, matches ? ACCENT : ERROR, false, 2);
                }
                if (photo.filePresent && "video".equals(photo.kind))
                    text(photoRow, "Video: " + codec(photo.videoMime) + " • Audio: " +
                        codec(photo.audioMime) + (photo.durationMs > 0 ?
                            " • " + duration(photo.durationMs / 1000L) : ""),
                        12, ACCENT, false, 2);
                if (!photo.fileName.isEmpty()) text(photoRow, photo.fileName, 12, MUTED, false, 2);
                if (photo.filePresent) {
                    Button openPhoto = new Button(activity);
                    openPhoto.setText("video".equals(photo.kind) ? "Open video" : "Open photo");
                    openPhoto.setAllCaps(false);
                    openPhoto.setTextSize(12);
                    openPhoto.setMinHeight(0);
                    LinearLayout.LayoutParams photoButton = new LinearLayout.LayoutParams(-2, dp(36));
                    photoButton.topMargin = dp(3);
                    photoRow.addView(openPhoto, photoButton);
                    openPhoto.setOnClickListener(v -> {
                        if ("video".equals(photo.kind)) openVideo(photo.savedUri);
                        else openPhoto(photo.savedUri, photo.mime);
                    });
                } else if (!photo.fileName.isEmpty() && (photo.bytes > 0 ||
                    "image/jpeg".equals(photo.mime) && photo.width > 0 && photo.height > 0)) {
                    Button findPhoto = new Button(activity);
                    findPhoto.setText("Find saved " + photo.kind);
                    findPhoto.setAllCaps(false);
                    findPhoto.setTextSize(12);
                    findPhoto.setMinHeight(0);
                    LinearLayout.LayoutParams photoButton = new LinearLayout.LayoutParams(-2, dp(36));
                    photoButton.topMargin = dp(3);
                    photoRow.addView(findPhoto, photoButton);
                    findPhoto.setOnClickListener(v -> pickRestoredFile(entry.id, photo.index,
                        "video".equals(photo.kind) ? "video/*" : "image/*"));
                }
            }
            }
            albumPhotos.setVisibility(album && expanded ? View.VISIBLE : View.GONE);
            String visibleThumbnail = album && !entry.photos.isEmpty() &&
                !entry.photos.get(0).filePresent ? "" : entry.thumbnailPath;
            if (!visibleThumbnail.equals(shownThumbnail)) {
                shownThumbnail = visibleThumbnail;
                Bitmap image = visibleThumbnail.isEmpty() ? null : BitmapFactory.decodeFile(visibleThumbnail);
                if (image == null) {
                    thumbnail.setImageResource(android.R.drawable.ic_menu_gallery);
                    thumbnail.setColorFilter(MUTED);
                } else {
                    thumbnail.clearColorFilter();
                    thumbnail.setImageBitmap(image);
                }
            }
        }
    }

    private void openVideo(String savedUri) {
        try {
            Uri uri = Uri.parse(savedUri);
            Intent open = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            open.setClipData(ClipData.newRawUri("Downloaded video", uri));
            activity.startActivity(open);
        } catch (Exception error) {
            Toast.makeText(activity, "Could not open this video", Toast.LENGTH_LONG).show();
        }
    }

    private void openPhoto(String savedUri, String mime) {
        try {
            Uri uri = Uri.parse(savedUri);
            Intent open = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mime.isEmpty() ? "image/*" : mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            open.setClipData(ClipData.newRawUri("Downloaded photo", uri));
            activity.startActivity(open);
        } catch (Exception error) {
            Toast.makeText(activity, "Could not open this photo", Toast.LENGTH_LONG).show();
        }
    }

    private void openPost(String url, String appName) {
        Intent open = new Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            String packageName = "Instagram".equals(appName) ? "com.instagram.android" :
                "RedNote".equals(appName) ? "com.xingin.xhs" : "com.zhiliaoapp.musically";
            activity.startActivity(new Intent(open).setPackage(packageName));
        } catch (ActivityNotFoundException missingApp) {
            try { activity.startActivity(open); }
            catch (ActivityNotFoundException missingHandler) {
                Toast.makeText(activity, "Could not open this post", Toast.LENGTH_LONG).show();
            }
        }
    }

    private int dp(int value) { return OverlaySettings.dp(activity, value); }

    private TextView text(LinearLayout parent, String value, int size, int color, boolean bold, int topMargin) {
        TextView text = new TextView(activity);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        if (bold) text.setTypeface(null, 1);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(topMargin);
        parent.addView(text, params);
        return text;
    }

    private GradientDrawable cardBackground(boolean selected) {
        GradientDrawable background = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            new int[]{AppPalette.CARD_TOP, CARD});
        background.setCornerRadius(dp(18));
        background.setStroke(dp(selected ? 2 : 1), selected ? ACCENT : AppPalette.BORDER);
        return background;
    }

    private static String bytes(long value) {
        if (value < 1024) return value + " B";
        if (value < 1024 * 1024) return String.format(Locale.US, "%.1f KB", value / 1024.0);
        if (value < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB", value / (1024.0 * 1024));
        return String.format(Locale.US, "%.2f GB", value / (1024.0 * 1024 * 1024));
    }

    private static String duration(long seconds) {
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long remainder = seconds % 60;
        return hours > 0 ? String.format(Locale.US, "%d:%02d:%02d", hours, minutes, remainder) :
            String.format(Locale.US, "%d:%02d", minutes, remainder);
    }

    private static String date(long millis) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date(millis));
    }

    private static String codec(String mime) {
        if ("video/avc".equals(mime)) return "H.264";
        if ("video/hevc".equals(mime)) return "H.265";
        if ("audio/mp4a-latm".equals(mime)) return "AAC";
        return mime;
    }
}
