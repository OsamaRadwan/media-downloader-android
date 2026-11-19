package com.codex.mediadownloader;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.ClipboardManager;
import android.content.ClipData;
import android.content.ClipDescription;
import android.net.Uri;
import android.graphics.PixelFormat;
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
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager windowManager;
    private WindowManager.LayoutParams overlayParams;
    private ImageView overlay;
    private OverlayPreferences settings;
    private CustomIconStore customIcons;
    private boolean collecting;
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
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && type != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return;
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
            Log.d("MediaDownloader", "Top app: " + topApp);
            if (TIKTOK.equals(topApp)) showOverlay(); else hideOverlay();
            return;
        }
    }

    private boolean isOverlayWindow(AccessibilityNodeInfo root) {
        if (root == null) return false;
        CharSequence description = root.getContentDescription();
        if (description != null && description.toString().startsWith("Download current TikTok post")) return true;
        for (int i = 0; i < root.getChildCount(); i++) {
            if (isOverlayWindow(root.getChild(i))) return true;
        }
        return false;
    }

    private void showOverlay() {
        if (overlay != null) return;
        // Use the application context so the window gets its own overlay token,
        // rather than inheriting this accessibility service's overlay token.
        windowManager = (WindowManager) getApplicationContext().getSystemService(WINDOW_SERVICE);
        ImageView icon = new ImageView(this);
        OverlaySettings.style(icon, this, settings, customIcons);
        icon.setContentDescription("Download current TikTok post. Long press to open Media Downloader");
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
        OverlaySettings.place(params, this, settings);
        try {
            windowManager.addView(icon, params);
        } catch (RuntimeException error) {
            Log.e("MediaDownloader", "Could not add overlay", error);
            return;
        }
        overlay = icon;
        overlayParams = params;
    }

    private void refreshOverlay() {
        if (overlay == null || overlayParams == null || settings == null) return;
        OverlaySettings.style(overlay, this, settings, customIcons);
        OverlaySettings.place(overlayParams, this, settings);
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
        AccessibilityNodeInfo share = null;
        if (root != null && root.getPackageName() != null && TIKTOK.contentEquals(root.getPackageName())) {
            share = find(root, "Share video");
            if (share == null) share = find(root, "Share photo");
            if (share == null) share = find(root, "Share post");
        }
        if (share == null || !share.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            Toast.makeText(this, "Open a TikTok post first", Toast.LENGTH_SHORT).show();
            return;
        }
        collecting = true;
        handler.postDelayed(() -> {
            AccessibilityNodeInfo copy = find(getRootInActiveWindow(), "Copy link");
            long copyStartedAt = System.currentTimeMillis();
            if (copy == null || !copy.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                finishCollecting();
                Toast.makeText(this, "Could not find Copy link", Toast.LENGTH_SHORT).show();
                return;
            }
            handler.postDelayed(() -> {
                try {
                    if (overlay == null || overlayParams == null) throw new Exception("Download button disappeared");
                    overlayParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                    windowManager.updateViewLayout(overlay, overlayParams);
                    overlay.setFocusableInTouchMode(true);
                    overlay.requestFocus();
                    handler.postDelayed(() -> readFreshLink(copyStartedAt, System.currentTimeMillis() + 5000), 150);
                } catch (Exception error) { failCollecting(error); }
            }, 200);
        }, 600);
    }

    private void readFreshLink(long copyStartedAt, long deadline) {
        try {
            if (overlay == null || overlayParams == null) throw new Exception("Download button disappeared");
            if (!overlay.hasWindowFocus()) overlay.requestFocus();
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            ClipDescription description = clipboard.getPrimaryClipDescription();
            long copiedAt = description == null ? 0 : description.getTimestamp();
            if (!overlay.hasWindowFocus() || copiedAt < copyStartedAt) {
                if (System.currentTimeMillis() < deadline) {
                    handler.postDelayed(() -> readFreshLink(copyStartedAt, deadline), 200);
                    return;
                }
                throw new Exception("TikTok did not copy a fresh link");
            }
            ClipData clip = clipboard.getPrimaryClip();
            CharSequence copied = clip == null || clip.getItemCount() == 0 ? null : clip.getItemAt(0).coerceToText(this);
            Matcher match = Pattern.compile("https?://[^\\s]+", Pattern.CASE_INSENSITIVE).matcher(copied == null ? "" : copied);
            if (!match.find()) throw new Exception("TikTok did not copy a link");
            String shareUrl = match.group().replaceAll("[),.;]+$", "");
            String host = Uri.parse(shareUrl).getHost();
            if (host == null || !(host.equals("tiktok.com") || host.endsWith(".tiktok.com"))) throw new Exception("Copied link is not from TikTok");
            Log.i("MediaDownloader", "Fresh TikTok link copied at " + copiedAt);
            Intent intent = new Intent(this, DownloadService.class);
            intent.putExtra("shareUrl", shareUrl);
            startForegroundService(intent);
            finishCollecting();
        } catch (Exception error) { failCollecting(error); }
    }

    private void failCollecting(Exception error) {
        Log.e("MediaDownloader", "Could not start download", error);
        Toast.makeText(this, "Could not start download: " + error.getMessage(), Toast.LENGTH_LONG).show();
        finishCollecting();
    }

    private void finishCollecting() {
        if (overlay != null && overlayParams != null) {
            overlayParams.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            windowManager.updateViewLayout(overlay, overlayParams);
            overlay.setFocusableInTouchMode(false);
        }
        collecting = false;
        scheduleVisibilityCheck();
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
}
