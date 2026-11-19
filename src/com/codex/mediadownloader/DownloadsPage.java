package com.codex.mediadownloader;

import android.app.Activity;
import android.content.ClipData;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class DownloadsPage {
    private static final int PAGE = 0xff102330;
    private static final int CARD = 0xff1b3443;
    private static final int TEXT = 0xfff3f7f9;
    private static final int MUTED = 0xffb8cbd2;
    private static final int ACCENT = 0xff54dbc5;
    private static final int ERROR = 0xffff9e95;
    private final Activity activity;
    private final DownloadsController controller;
    private final ScrollView scroll;
    private final LinearLayout list;
    private final TextView summary;
    private final TextView empty;
    private final Map<Integer, Row> rows = new HashMap<>();
    private int focusId;
    private boolean focused;

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
        summary = text(page, "", 14, ACCENT, true, 18);
        empty = text(page, "No downloads yet. Open a TikTok video or photo album and tap the floating arrow.", 15, MUTED, false, 24);
        list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        page.addView(list);
    }

    View view() { return scroll; }

    void focus(int id) {
        focusId = id;
        focused = false;
        for (Row row : rows.values()) row.root.setBackground(cardBackground(row.id == focusId));
    }

    void refresh() {
        DownloadsState state = controller.load();
        List<DownloadItem> entries = state.items;
        summary.setText(state.activeCount + " active  •  " + state.completeCount +
            " complete  •  " + state.failedCount + " failed");
        empty.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
        for (int i = 0; i < entries.size(); i++) {
            DownloadItem entry = entries.get(i);
            Row row = rows.get(entry.id);
            if (row == null) {
                row = new Row(entry.id);
                rows.put(entry.id, row);
                list.addView(row.root, i, row.params);
            }
            row.update(entry);
            if (focusId == entry.id && !focused) {
                Row target = row;
                scroll.post(() -> scroll.smoothScrollTo(0, list.getTop() + target.root.getTop()));
                focused = true;
            }
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
        final Button retryMissing;
        String shownThumbnail = "";
        int shownPhotoFingerprint;
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
            thumbnail.setBackgroundColor(0xff294655);
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
            retryMissing = new Button(activity);
            retryMissing.setText("Retry missing photos");
            retryMissing.setAllCaps(false);
            retryMissing.setTextSize(14);
            retryMissing.setMinHeight(0);
            retryMissing.setBackgroundTintList(android.content.res.ColorStateList.valueOf(ACCENT));
            LinearLayout.LayoutParams retryParams = new LinearLayout.LayoutParams(-2, dp(40));
            retryParams.gravity = Gravity.END;
            retryParams.topMargin = dp(8);
            root.addView(retryMissing, retryParams);
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
            if (shownUpdatedAt == entry.updatedAt && !entry.isActive()) return;
            shownUpdatedAt = entry.updatedAt;
            boolean album = "album".equals(entry.mediaType);
            title.setText(entry.profileName.isEmpty() ?
                (entry.videoId.isEmpty() ? "Download" : (album ? "Album " : "Video ") + entry.videoId) :
                "@" + entry.profileName + (album ? " • photo album" : ""));
            state.setText(entry.stage.isEmpty() ? entry.state : entry.stage);
            state.setTextColor("failed".equals(entry.state) || "partial".equals(entry.state) ? ERROR : ACCENT);
            file.setText(entry.fileName.isEmpty() ? "Reading post details…" : entry.fileName);
            int savedPhotos = 0;
            for (DownloadItem.PhotoItem photo : entry.photos) if (!photo.savedUri.isEmpty()) savedPhotos++;
            if (album && !entry.photos.isEmpty()) {
                progress.setVisibility(View.VISIBLE);
                progress.setIndeterminate(false);
                progress.setProgress(savedPhotos * 1000 / entry.photos.size());
            } else if (entry.isActive()) {
                progress.setVisibility(View.VISIBLE);
                progress.setIndeterminate(entry.totalBytes <= 0);
                if (entry.totalBytes > 0) progress.setProgress((int) Math.min(1000,
                    entry.downloadedBytes * 1000 / entry.totalBytes));
            } else if ("complete".equals(entry.state)) {
                progress.setVisibility(View.VISIBLE);
                progress.setIndeterminate(false);
                progress.setProgress(1000);
            } else {
                progress.setVisibility(View.GONE);
            }
            String amount;
            if (album) amount = savedPhotos + " of " + entry.photos.size() + " photos saved • " +
                bytes(entry.downloadedBytes);
            else {
                amount = "Downloaded: " + bytes(entry.downloadedBytes) + " / " +
                    (entry.totalBytes > 0 ? bytes(entry.totalBytes) : "size unknown");
                if (entry.totalBytes > 0) amount += " (" + Math.min(100, entry.downloadedBytes * 100 / entry.totalBytes) + "%)";
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
            if (!entry.videoId.isEmpty()) metadata.append(album ? "Post ID: " : "Video ID: ").append(entry.videoId).append('\n');
            if (!entry.accountId.isEmpty()) metadata.append("Account ID: ").append(entry.accountId).append('\n');
            if (entry.publishTimeSeconds > 0) metadata.append("Published: ").append(date(entry.publishTimeSeconds * 1000L)).append('\n');
            if (entry.width > 0 && entry.height > 0) metadata.append("Resolution: ").append(entry.width).append(" × ").append(entry.height).append('\n');
            if (entry.durationMs > 0) metadata.append("Duration: ").append(duration(entry.durationMs / 1000L)).append('\n');
            if (!entry.videoMime.isEmpty()) metadata.append("Video: ").append(codec(entry.videoMime)).append('\n');
            if (!entry.audioMime.isEmpty()) metadata.append("Audio: ").append(codec(entry.audioMime)).append('\n');
            if (album && !entry.postTitle.isEmpty()) metadata.append("Title: ").append(entry.postTitle).append('\n');
            if (album && !entry.caption.isEmpty()) metadata.append("Caption: ").append(entry.caption).append('\n');
            if (album && !entry.hashtags.isEmpty()) metadata.append("Hashtags: ").append(entry.hashtags).append('\n');
            metadata.append("Started: ").append(date(entry.createdAt));
            if (entry.finishedAt > 0) metadata.append("\nFinished: ").append(date(entry.finishedAt));
            if ("complete".equals(entry.state)) metadata.append("\nSaved in: Downloads");
            details.setText(metadata.toString());
            error.setVisibility(entry.error.isEmpty() ? View.GONE : View.VISIBLE);
            error.setText(entry.error.isEmpty() ? "" : "Error: " + entry.error);
            String tiktokUrl = entry.tiktokUrl();
            tiktokLink.setVisibility(tiktokUrl.isEmpty() ? View.GONE : View.VISIBLE);
            tiktokLink.setOnClickListener(v -> openTikTok(tiktokUrl));
            if (entry.isActive() && !expanded) expanded = true;
            toggle.setText(expanded ? "Hide details ▲" : "Show details ▼");
            details.setVisibility(expanded ? View.VISIBLE : View.GONE);
            boolean canOpen = !album && "complete".equals(entry.state) && !entry.savedUri.isEmpty();
            open.setVisibility(canOpen ? View.VISIBLE : View.GONE);
            open.setOnClickListener(v -> openVideo(entry.savedUri));
            String firstPhotoUri = album && !entry.photos.isEmpty() ? entry.photos.get(0).savedUri : "";
            thumbnail.setClickable(canOpen || !firstPhotoUri.isEmpty());
            thumbnail.setContentDescription(album ? "Open first downloaded photo" : canOpen ? "Play downloaded video" : null);
            thumbnail.setOnClickListener(canOpen ? v -> openVideo(entry.savedUri) :
                !firstPhotoUri.isEmpty() ? v -> openPhoto(firstPhotoUri, entry.photos.get(0).mime) : null);
            retryMissing.setVisibility(album && !entry.isActive() && savedPhotos < entry.photos.size() &&
                !entry.photos.isEmpty() ? View.VISIBLE : View.GONE);
            retryMissing.setOnClickListener(v -> {
                Intent retry = new Intent(activity, DownloadService.class).putExtra("retryJobId", entry.id);
                activity.startForegroundService(retry);
                retryMissing.setEnabled(false);
            });
            int photoFingerprint = 1;
            for (DownloadItem.PhotoItem photo : entry.photos)
                photoFingerprint = 31 * photoFingerprint +
                    (photo.savedUri + photo.error + photo.thumbnailPath).hashCode();
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
                        photoPreview.setContentDescription("Open photo " + photo.index);
                        photoPreview.setOnClickListener(v -> openPhoto(photo.savedUri, photo.mime));
                    }
                }
                String label = "Photo " + photo.index + " / " + entry.photos.size() + " • " +
                    (!photo.savedUri.isEmpty() ? bytes(photo.bytes) + " • " + photo.width + " × " + photo.height :
                        photo.error.isEmpty() ? "waiting" : "failed: " + photo.error);
                text(photoRow, label, 13, photo.error.isEmpty() ? TEXT : ERROR, true, 0);
                if (!photo.savedUri.isEmpty() && photo.sourceWidth > 0 && photo.sourceHeight > 0) {
                    boolean matches = PhotoQuality.matchesListed(photo.width, photo.height,
                        photo.sourceWidth, photo.sourceHeight);
                    text(photoRow, matches ? "Matches TikTok's listed resolution" :
                        "Below TikTok's listed " + photo.sourceWidth + " × " + photo.sourceHeight,
                        12, matches ? ACCENT : ERROR, false, 2);
                }
                if (!photo.fileName.isEmpty()) text(photoRow, photo.fileName, 12, MUTED, false, 2);
                if (!photo.savedUri.isEmpty()) {
                    Button openPhoto = new Button(activity);
                    openPhoto.setText("Open photo");
                    openPhoto.setAllCaps(false);
                    openPhoto.setTextSize(12);
                    openPhoto.setMinHeight(0);
                    LinearLayout.LayoutParams photoButton = new LinearLayout.LayoutParams(-2, dp(36));
                    photoButton.topMargin = dp(3);
                    photoRow.addView(openPhoto, photoButton);
                    openPhoto.setOnClickListener(v -> openPhoto(photo.savedUri, photo.mime));
                }
            }
            }
            albumPhotos.setVisibility(album && expanded ? View.VISIBLE : View.GONE);
            if (!entry.thumbnailPath.equals(shownThumbnail)) {
                shownThumbnail = entry.thumbnailPath;
                Bitmap image = entry.thumbnailPath.isEmpty() ? null : BitmapFactory.decodeFile(entry.thumbnailPath);
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

    private void openTikTok(String url) {
        Intent open = new Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            activity.startActivity(new Intent(open).setPackage("com.zhiliaoapp.musically"));
        } catch (ActivityNotFoundException missingTikTok) {
            try { activity.startActivity(open); }
            catch (ActivityNotFoundException missingHandler) {
                Toast.makeText(activity, "Could not open this TikTok post", Toast.LENGTH_LONG).show();
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
        GradientDrawable background = new GradientDrawable();
        background.setColor(CARD);
        background.setCornerRadius(dp(18));
        if (selected) background.setStroke(dp(2), ACCENT);
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
