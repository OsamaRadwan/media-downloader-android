package com.codex.mediadownloader;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.ClipboardManager;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.res.Configuration;
import android.net.Uri;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.ImageView;
import android.widget.Toast;
import java.util.List;

public class OverlayAccessibilityService extends AccessibilityService implements View.OnClickListener {
    private static final String TIKTOK = "com.zhiliaoapp.musically";
    private static final String INSTAGRAM = "com.instagram.android";
    private static final String REDNOTE = "com.xingin.xhs";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager windowManager;
    private WindowManager.LayoutParams overlayParams;
    private ImageView overlay;
    private OverlayPreferences settings;
    private CustomIconStore customIcons;
    private boolean collecting;
    private String sourceApp = TIKTOK;
    private OverlayPlacement placement = OverlayPlacement.TIKTOK;
    private boolean returnToTikTokLandscape;
    private final Runnable visibilityCheck = this::syncVisibility;

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        settings = new SharedPreferencesOverlayPreferences(this);
        customIcons = new CustomIconStore(this);
        settings.startObserving(() -> handler.post(this::refreshOverlay));
        scheduleVisibilityCheck();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOWS_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && type != AccessibilityEvent.TYPE_VIEW_SCROLLED) return;
        if (collecting) return;
        if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            handler.removeCallbacks(visibilityCheck);
            handler.post(visibilityCheck);
        } else {
            scheduleVisibilityCheck();
        }
    }

    @Override public void onInterrupt() { }
    @Override public void onDestroy() {
        handler.removeCallbacks(visibilityCheck);
        if (settings != null) settings.stopObserving();
        hideOverlay();
        super.onDestroy();
    }

    private void scheduleVisibilityCheck() {
        handler.removeCallbacks(visibilityCheck);
        handler.postDelayed(visibilityCheck, 150);
    }

    private void syncVisibility() {
        if (collecting) return;
        AccessibilityNodeInfo activeRoot = getRootInActiveWindow();
        if (activeRoot != null && activeRoot.getPackageName() != null &&
            getPackageName().contentEquals(activeRoot.getPackageName()) && !isOverlayWindow(activeRoot)) {
            hideOverlay();
            return;
        }
        List<AccessibilityWindowInfo> windows = getWindows();
        for (AccessibilityWindowInfo window : windows) {
            if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
            AccessibilityNodeInfo root = window.getRoot();
            if (root == null || root.getPackageName() == null) continue;
            String topApp = root.getPackageName().toString();
            // Android can report the floating button as an application window.
            if (getPackageName().equals(topApp)) {
                if (isOverlayWindow(root)) continue;
                hideOverlay();
                return;
            }
            if (TIKTOK.equals(topApp)) {
                showOverlay(TIKTOK, isLandscape() ? OverlayPlacement.TIKTOK_LANDSCAPE : OverlayPlacement.TIKTOK);
            } else if (INSTAGRAM.equals(topApp)) {
                OverlayPlacement instagramPlacement = find(root, "Reels") != null && find(root, "Home") == null ?
                    OverlayPlacement.INSTAGRAM_REEL : OverlayPlacement.INSTAGRAM_FEED;
                showOverlay(INSTAGRAM, instagramPlacement);
            } else if (REDNOTE.equals(topApp)) {
                if (find(root, "Say something") == null || findRedNoteShare(root) == null) hideOverlay();
                else showOverlay(REDNOTE, find(root, "Picture, No.") == null ?
                    OverlayPlacement.REDNOTE_VIDEO : OverlayPlacement.REDNOTE_PHOTO);
            } else hideOverlay();
            return;
        }
    }

    private boolean isOverlayWindow(AccessibilityNodeInfo root) {
        if (root == null) return false;
        CharSequence description = root.getContentDescription();
        if (description != null && description.toString().startsWith("Download current post")) return true;
        for (int i = 0; i < root.getChildCount(); i++) {
            if (isOverlayWindow(root.getChild(i))) return true;
        }
        return false;
    }

    private void showOverlay(String app, OverlayPlacement newPlacement) {
        if (overlay != null) {
            if (!app.equals(sourceApp) || newPlacement != placement) {
                sourceApp = app;
                placement = newPlacement;
                refreshOverlay();
            }
            return;
        }
        sourceApp = app;
        placement = newPlacement;
        // Use the application context so the window gets its own overlay token,
        // rather than inheriting this accessibility service's overlay token.
        windowManager = (WindowManager) getApplicationContext().getSystemService(WINDOW_SERVICE);
        ImageView icon = new ImageView(this);
        OverlaySettings.style(icon, this, settings, customIcons);
        icon.setContentDescription("Download current post. Long press to open Media Downloader");
        icon.setOnClickListener(this);
        icon.setOnLongClickListener(view -> {
            Intent open = new Intent(this, MainActivity.class);
            open.putExtra(MainActivity.EXTRA_PAGE, MainActivity.DOWNLOADS_PAGE);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(open);
            return true;
        });
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(0, 0,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT);
        OverlaySettings.place(params, this, settings, placement);
        try {
            windowManager.addView(icon, params);
        } catch (RuntimeException error) {
            DiagnosticsStore.record(this, "overlay.add_view", error);
            Log.e("MediaDownloader", "Could not add overlay", error);
            return;
        }
        overlay = icon;
        overlayParams = params;
    }

    private void refreshOverlay() {
        if (overlay == null || overlayParams == null || settings == null) return;
        OverlaySettings.style(overlay, this, settings, customIcons);
        OverlaySettings.place(overlayParams, this, settings, placement);
        windowManager.updateViewLayout(overlay, overlayParams);
    }

    private void hideOverlay() {
        if (overlay == null) return;
        windowManager.removeView(overlay);
        overlay = null;
        overlayParams = null;
    }

    @Override public void onClick(View view) {
        if (collecting) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null && root.getPackageName() != null && getPackageName().contentEquals(root.getPackageName())) return;
        Log.d("MediaDownloader", "Overlay tapped; source=" + sourceApp +
            " active=" + (root == null ? "none" : root.getPackageName()));
        DiagnosticsStore.trace(this, "overlay.tap", "source=" +
            (root == null || root.getPackageName() == null ? "unavailable" : root.getPackageName()));
        String app = root == null || root.getPackageName() == null ? "" : root.getPackageName().toString();
        if (TIKTOK.equals(app) && isLandscape()) {
            collecting = true;
            returnToTikTokLandscape = true;
            if (!performGlobalAction(GLOBAL_ACTION_BACK)) {
                failCollecting(new Exception("Could not return to the current TikTok video"));
                return;
            }
            DiagnosticsStore.trace(this, "overlay.landscape", "Returning to current post share control");
            handler.postDelayed(() -> clickLandscapeTikTokShare(System.currentTimeMillis() + 5000), 250);
            return;
        }
        AccessibilityNodeInfo share = null;
        if (TIKTOK.equals(app)) {
            share = findTikTokShare(root);
        } else if (INSTAGRAM.equals(app)) {
            share = findShare(root);
        } else if (REDNOTE.equals(app)) {
            share = findRedNoteShare(root);
        }
        AccessibilityNodeInfo action = clickableAncestor(share);
        if (action == null || !action.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            Log.d("MediaDownloader", "No actionable share control in the active post; found=" + (share != null));
            DiagnosticsStore.record(this, "overlay.share", "Share action unavailable on current post");
            Toast.makeText(this, "Open a post first", Toast.LENGTH_SHORT).show();
            return;
        }
        collecting = true;
        DiagnosticsStore.trace(this, "overlay.share", "Share control activated");
        handler.postDelayed(() -> clickCopyWhenReady(app, System.currentTimeMillis() + 4000),
            INSTAGRAM.equals(app) ? 1000 : 600);
    }

    private boolean isLandscape() {
        return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    private AccessibilityNodeInfo findTikTokShare(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo share = find(root, "Share video");
        if (share == null) share = find(root, "Share photo");
        if (share == null) share = find(root, "Share post");
        if (share == null) share = find(root, "Share story");
        if (share == null) share = find(root, "Share Story");
        return share;
    }

    private void clickLandscapeTikTokShare(long deadline) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null && TIKTOK.equals(String.valueOf(root.getPackageName())) && !isLandscape() &&
            find(root, "Full screen") != null) {
            AccessibilityNodeInfo action = clickableAncestor(findTikTokShare(root));
            if (action != null && action.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                DiagnosticsStore.trace(this, "overlay.landscape", "Current portrait post share activated");
                handler.postDelayed(() -> clickCopyWhenReady(TIKTOK, System.currentTimeMillis() + 4000), 600);
                return;
            }
        }
        if (System.currentTimeMillis() < deadline) {
            handler.postDelayed(() -> clickLandscapeTikTokShare(deadline), 200);
            return;
        }
        failCollecting(new Exception("Could not find the current TikTok video's share control"));
    }

    private void clickCopyWhenReady(String app, long deadline) {
        AccessibilityNodeInfo copy = findActionInWindows("Copy link");
        if (copy == null && REDNOTE.equals(app)) copy = find(getRootInActiveWindow(), "Copy link");
        long copyStartedAt = System.currentTimeMillis();
        if (copy != null && copy.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            startClipboardRead(app, copyStartedAt);
            return;
        }
        if (copy != null && tapCopyControl(copy, app, copyStartedAt)) return;
        if (System.currentTimeMillis() < deadline) {
            handler.postDelayed(() -> clickCopyWhenReady(app, deadline), 200);
            return;
        }
        DiagnosticsStore.record(this, "overlay.copy_link", "Copy link action unavailable");
        failCollecting(new Exception("Could not find Copy link"));
    }

    private boolean tapCopyControl(AccessibilityNodeInfo copy, String app, long startedAt) {
        Rect bounds = new Rect();
        copy.getBoundsInScreen(bounds);
        if (bounds.isEmpty() || bounds.width() > 400 || bounds.height() > 400) return false;
        Path path = new Path();
        path.moveTo(bounds.centerX(), bounds.centerY());
        android.accessibilityservice.GestureDescription gesture =
            new android.accessibilityservice.GestureDescription.Builder()
                .addStroke(new android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 70))
                .build();
        return dispatchGesture(gesture, new GestureResultCallback() {
            @Override public void onCompleted(android.accessibilityservice.GestureDescription ignored) {
                Log.d("MediaDownloader", "Copy gesture completed");
                startClipboardRead(app, startedAt);
            }
            @Override public void onCancelled(android.accessibilityservice.GestureDescription ignored) {
                Log.d("MediaDownloader", "Copy gesture cancelled");
                failCollecting(new Exception("Could not tap Copy link"));
            }
        }, handler);
    }

    private void startClipboardRead(String app, long copyStartedAt) {
        Log.d("MediaDownloader", "Awaiting fresh clipboard link");
        DiagnosticsStore.trace(this, "overlay.copy_link", "Copy link control activated");
        handler.postDelayed(() -> {
                try {
                    if (overlay == null || overlayParams == null) throw new Exception("Download button disappeared");
                    overlayParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                    windowManager.updateViewLayout(overlay, overlayParams);
                    overlay.setFocusableInTouchMode(true);
                    overlay.requestFocus();
                    handler.postDelayed(() -> readFreshLink(app, copyStartedAt, System.currentTimeMillis() + 5000), 150);
                } catch (Exception error) { failCollecting(error); }
        }, 200);
    }

    private void readFreshLink(String app, long copyStartedAt, long deadline) {
        try {
            if (overlay == null || overlayParams == null) throw new Exception("Download button disappeared");
            if (!overlay.hasWindowFocus()) overlay.requestFocus();
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            ClipDescription description = clipboard.getPrimaryClipDescription();
            long copiedAt = description == null ? 0 : description.getTimestamp();
            if (!overlay.hasWindowFocus() || copiedAt < copyStartedAt) {
                if (System.currentTimeMillis() < deadline) {
                    handler.postDelayed(() -> readFreshLink(app, copyStartedAt, deadline), 200);
                    return;
                }
                throw new Exception("The source app did not copy a fresh link");
            }
            ClipData clip = clipboard.getPrimaryClip();
            CharSequence copied = clip == null || clip.getItemCount() == 0 ? null : clip.getItemAt(0).coerceToText(this);
            Matcher match = Pattern.compile("https?://[^\\s]+", Pattern.CASE_INSENSITIVE).matcher(copied == null ? "" : copied);
            if (!match.find()) throw new Exception("The source app did not copy a link");
            String shareUrl = match.group().replaceAll("[),.;]+$", "");
            String host = Uri.parse(shareUrl).getHost();
            boolean valid = INSTAGRAM.equals(app) ? InstagramResolver.accepts(shareUrl) :
                REDNOTE.equals(app) ? RedNoteResolver.accepts(shareUrl) :
                host != null && (host.equals("tiktok.com") || host.endsWith(".tiktok.com"));
            if (!valid) throw new Exception("Copied link is not a post from the current app");
            Log.i("MediaDownloader", "Fresh post link copied at " + copiedAt);
            DiagnosticsStore.trace(this, "overlay.fresh_link", "host=" + host +
                " clipboardAgeMs=" + Math.max(0, System.currentTimeMillis() - copiedAt));
            Intent intent = new Intent(this, DownloadService.class);
            intent.putExtra("shareUrl", shareUrl);
            startForegroundService(intent);
            boolean shareSheetOpen = findActionInWindows("Copy link") != null;
            boolean restoreLandscape = returnToTikTokLandscape;
            finishCollecting();
            if (shareSheetOpen) performGlobalAction(GLOBAL_ACTION_BACK);
            if (restoreLandscape) handler.postDelayed(() -> restoreTikTokLandscape(System.currentTimeMillis() + 3000), 450);
        } catch (Exception error) { failCollecting(error); }
    }

    private void failCollecting(Exception error) {
        DiagnosticsStore.record(this, "overlay.start_download", error);
        Log.e("MediaDownloader", "Could not start download", error);
        Toast.makeText(this, "Could not start download: " + error.getMessage(), Toast.LENGTH_LONG).show();
        boolean restoreLandscape = returnToTikTokLandscape;
        finishCollecting();
        if (restoreLandscape) {
            if (findActionInWindows("Copy link") != null) performGlobalAction(GLOBAL_ACTION_BACK);
            handler.postDelayed(() -> restoreTikTokLandscape(System.currentTimeMillis() + 3000), 450);
        }
    }

    private void finishCollecting() {
        if (overlay != null && overlayParams != null) {
            overlayParams.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            windowManager.updateViewLayout(overlay, overlayParams);
            overlay.setFocusableInTouchMode(false);
        }
        collecting = false;
        returnToTikTokLandscape = false;
        scheduleVisibilityCheck();
    }

    private void restoreTikTokLandscape(long deadline) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || !TIKTOK.equals(String.valueOf(root.getPackageName()))) return;
        if (isLandscape()) return;
        AccessibilityNodeInfo fullScreen = clickableAncestor(find(root, "Full screen"));
        if (fullScreen != null && fullScreen.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            DiagnosticsStore.trace(this, "overlay.landscape", "Returned to full-screen post");
            return;
        }
        if (System.currentTimeMillis() < deadline)
            handler.postDelayed(() -> restoreTikTokLandscape(deadline), 200);
    }

    private AccessibilityNodeInfo find(AccessibilityNodeInfo root, String needle) {
        if (root == null || !root.isVisibleToUser()) return null;
        CharSequence description = root.getContentDescription();
        CharSequence text = root.getText();
        if ((description != null && description.toString().startsWith(needle)) ||
            (text != null && text.toString().startsWith(needle))) return root;
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo match = find(root.getChild(i), needle);
            if (match != null) return match;
        }
        return null;
    }

    private AccessibilityNodeInfo findShare(AccessibilityNodeInfo root) {
        java.util.ArrayList<AccessibilityNodeInfo> candidates = new java.util.ArrayList<>();
        collectShare(root, candidates);
        if (candidates.isEmpty()) return null;
        int middle = getResources().getDisplayMetrics().heightPixels / 2;
        AccessibilityNodeInfo best = null;
        int bestDistance = Integer.MAX_VALUE;
        Rect bounds = new Rect();
        for (AccessibilityNodeInfo candidate : candidates) {
            candidate.getBoundsInScreen(bounds);
            // On a scrolling feed, the previous post's controls may remain at the top
            // while the next post fills the screen. Never select that previous post.
            if (bounds.centerY() < middle) continue;
            int distance = Math.abs(bounds.centerY() - middle);
            if (distance < bestDistance) { best = candidate; bestDistance = distance; }
        }
        return best;
    }

    private AccessibilityNodeInfo findRedNoteShare(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo labelled = find(root, "分享");
        if (labelled != null) return labelled;
        return findRedNoteShareIcon(root);
    }

    private AccessibilityNodeInfo findRedNoteShareIcon(AccessibilityNodeInfo root) {
        if (root == null || !root.isVisibleToUser()) return null;
        if ("android.widget.ImageView".contentEquals(root.getClassName())) {
            Rect bounds = new Rect();
            root.getBoundsInScreen(bounds);
            int width = getResources().getDisplayMetrics().widthPixels;
            int height = getResources().getDisplayMetrics().heightPixels;
            if (bounds.left >= width * 8 / 10 && bounds.top < height / 10 &&
                bounds.width() >= 40 && bounds.width() <= 120 && bounds.height() <= 120)
                return root;
        }
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo candidate = findRedNoteShareIcon(root.getChild(i));
            if (candidate != null) return candidate;
        }
        return null;
    }

    private void collectShare(AccessibilityNodeInfo root, java.util.List<AccessibilityNodeInfo> found) {
        if (root == null || !root.isVisibleToUser()) return;
        CharSequence description = root.getContentDescription();
        if (description != null) {
            String label = description.toString();
            if (label.startsWith("Send post.") || label.startsWith("Send reel.") ||
                label.equals("Share")) found.add(root);
        }
        for (int i = 0; i < root.getChildCount(); i++) collectShare(root.getChild(i), found);
    }

    private AccessibilityNodeInfo clickableAncestor(AccessibilityNodeInfo node) {
        for (int depth = 0; node != null && depth < 5; depth++, node = node.getParent()) {
            if (node.isClickable() || node.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK))
                return node;
        }
        return null;
    }

    private AccessibilityNodeInfo findActionInWindows(String label) {
        AccessibilityNodeInfo action = findAction(getRootInActiveWindow(), label);
        if (action != null) return action;
        for (AccessibilityWindowInfo window : getWindows()) {
            action = findAction(window.getRoot(), label);
            if (action != null) return action;
        }
        return null;
    }

    private AccessibilityNodeInfo findAction(AccessibilityNodeInfo root, String needle) {
        if (root == null) return null;
        CharSequence description = root.getContentDescription();
        CharSequence label = root.getText();
        if ((description != null && description.toString().startsWith(needle)) ||
            (label != null && label.toString().startsWith(needle))) {
            AccessibilityNodeInfo action = clickableAncestor(root);
            if (action != null) return action;
        }
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo action = findAction(root.getChild(i), needle);
            if (action != null) return action;
        }
        return null;
    }

}
