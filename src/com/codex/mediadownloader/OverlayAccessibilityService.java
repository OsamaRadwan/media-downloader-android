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
import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import android.view.Gravity;
import android.view.Display;
import android.view.View;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.ImageView;
import android.widget.Toast;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class OverlayAccessibilityService extends AccessibilityService implements View.OnClickListener {
    private static final String TIKTOK = "com.zhiliaoapp.musically";
    private static final String INSTAGRAM = "com.instagram.android";
    private static final String REDNOTE = "com.xingin.xhs";
    private static final String SYSTEM_UI = "com.android.systemui";
    private static final Pattern INSTAGRAM_STORY_HEADER = Pattern.compile(
        "^([A-Za-z0-9._]{1,40})'s story(?:,|$)", Pattern.CASE_INSENSITIVE);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager windowManager;
    private WindowManager.LayoutParams overlayParams;
    private ImageView overlay;
    private OverlayPreferences settings;
    private CustomIconStore customIcons;
    private boolean collecting;
    private String sourceApp = TIKTOK;
    private OverlayPlacement placement = OverlayPlacement.TIKTOK;
    private Rect landscapeVideoBounds;
    private Rect tiktokScreenBounds;
    private boolean landscapeCapture;
    private boolean visibilityCheckQueued;
    private boolean visibilityCheckImmediate;
    private TikTokStoryBatch storyBatch;
    private InstagramStoryBatch instagramStoryBatch;
    private int instagramDirectionCaptures;
    private boolean instagramScanningBackward;
    private int instagramShareRetries;
    private int storyScanPass;
    private int storyDirectionCaptures;
    private boolean storyScanningBackward;
    private final ExecutorService storyValidation = Executors.newSingleThreadExecutor();
    private final Runnable visibilityCheck = () -> {
        visibilityCheckQueued = false;
        visibilityCheckImmediate = false;
        syncVisibility();
    };

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        settings = new SharedPreferencesOverlayPreferences(this);
        customIcons = new CustomIconStore(this);
        settings.startObserving(() -> handler.post(this::refreshOverlay));
        scheduleVisibilityCheck(0);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOWS_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && type != AccessibilityEvent.TYPE_VIEW_SCROLLED) return;
        if (event.getPackageName() == null) {
            if (!collecting) scheduleVisibilityCheck(0);
            return;
        }
        String eventPackage = String.valueOf(event.getPackageName());
        if (!isSourcePackage(eventPackage) && !getPackageName().equals(eventPackage)) {
            if (!SYSTEM_UI.equals(eventPackage) && type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                handler.removeCallbacks(visibilityCheck);
                visibilityCheckQueued = false;
                visibilityCheckImmediate = false;
                if (collecting) {
                    storyBatch = null;
                    instagramStoryBatch = null;
                    releaseOverlayFocus();
                    collecting = false;
                    landscapeCapture = false;
                }
                hideOverlay();
            }
            return;
        }
        if (collecting) return;
        scheduleVisibilityCheck(type == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
            type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ? 0 : 50);
    }

    private static boolean isSourcePackage(String name) {
        return TIKTOK.equals(name) || INSTAGRAM.equals(name) || REDNOTE.equals(name);
    }

    @Override public void onInterrupt() { }
    @Override public void onDestroy() {
        storyBatch = null;
        instagramStoryBatch = null;
        handler.removeCallbacks(visibilityCheck);
        visibilityCheckQueued = false;
        visibilityCheckImmediate = false;
        if (settings != null) settings.stopObserving();
        hideOverlay();
        storyValidation.shutdownNow();
        super.onDestroy();
    }

    private void scheduleVisibilityCheck() {
        scheduleVisibilityCheck(0);
    }

    private void scheduleVisibilityCheck(long delayMs) {
        if (visibilityCheckQueued) {
            if (delayMs != 0 || visibilityCheckImmediate) return;
            handler.removeCallbacks(visibilityCheck);
        }
        visibilityCheckQueued = true;
        visibilityCheckImmediate = delayMs == 0;
        handler.postDelayed(visibilityCheck, delayMs);
    }

    private void syncVisibility() {
        if (collecting) return;
        AccessibilityNodeInfo activeRoot = getRootInActiveWindow();
        if (activeRoot == null || activeRoot.getPackageName() == null) return;
        String activePackage = activeRoot.getPackageName().toString();
        if (SYSTEM_UI.equals(activePackage)) return;
        if (!isSourcePackage(activePackage) && !getPackageName().equals(activePackage)) {
            hideOverlay();
            return;
        }
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
                boolean landscape = isLandscape(root);
                Rect screen = new Rect();
                root.getBoundsInScreen(screen);
                showOverlay(TIKTOK, landscape ? OverlayPlacement.TIKTOK_LANDSCAPE : OverlayPlacement.TIKTOK,
                    landscape ? findTikTokVideoBounds(root) : null, screen);
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
        showOverlay(app, newPlacement, null, null);
    }

    private void showOverlay(String app, OverlayPlacement newPlacement, Rect videoBounds, Rect screenBounds) {
        // A display rotation can leave the old overlay surface visible to WindowManager
        // but empty on screen. Give portrait and landscape their own fresh view surface.
        if (overlay != null && TIKTOK.equals(app) && TIKTOK.equals(sourceApp) &&
            newPlacement != placement &&
            (newPlacement == OverlayPlacement.TIKTOK || newPlacement == OverlayPlacement.TIKTOK_LANDSCAPE)) {
            hideOverlay();
        }
        if (overlay != null) {
            if (!app.equals(sourceApp) || newPlacement != placement ||
                !java.util.Objects.equals(landscapeVideoBounds, videoBounds) ||
                !java.util.Objects.equals(tiktokScreenBounds, screenBounds)) {
                sourceApp = app;
                placement = newPlacement;
                landscapeVideoBounds = videoBounds == null ? null : new Rect(videoBounds);
                tiktokScreenBounds = screenBounds == null ? null : new Rect(screenBounds);
                refreshOverlay();
            }
            return;
        }
        sourceApp = app;
        placement = newPlacement;
        landscapeVideoBounds = videoBounds == null ? null : new Rect(videoBounds);
        tiktokScreenBounds = screenBounds == null ? null : new Rect(screenBounds);
        // Use the application context so the window gets its own overlay token,
        // rather than inheriting this accessibility service's overlay token.
        windowManager = (WindowManager) getApplicationContext().getSystemService(WINDOW_SERVICE);
        ImageView icon = new ImageView(this);
        OverlaySettings.style(icon, this, settings, customIcons);
        icon.setContentDescription("Download current post. Long press to open Media Downloader");
        icon.setOnTouchListener((button, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                button.animate().cancel();
                button.setScaleX(0.78f);
                button.setScaleY(0.78f);
                button.setAlpha(0.45f);
            } else if (event.getActionMasked() == MotionEvent.ACTION_UP ||
                       event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                button.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(300).start();
            }
            return false;
        });
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
        placeOverlay(params);
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
        placeOverlay(overlayParams);
        windowManager.updateViewLayout(overlay, overlayParams);
    }

    private void placeOverlay(WindowManager.LayoutParams params) {
        if (placement == OverlayPlacement.TIKTOK_LANDSCAPE && tiktokScreenBounds != null &&
            !tiktokScreenBounds.isEmpty())
            OverlaySettings.placeLandscapeVideo(params, this, settings, landscapeVideoBounds,
                tiktokScreenBounds.width(), tiktokScreenBounds.height());
        else if (placement == OverlayPlacement.TIKTOK && tiktokScreenBounds != null &&
            !tiktokScreenBounds.isEmpty())
            OverlaySettings.place(params, this, settings, placement, tiktokScreenBounds.height());
        else OverlaySettings.place(params, this, settings, placement);
    }

    private void hideOverlay() {
        if (overlay == null) return;
        windowManager.removeView(overlay);
        overlay = null;
        overlayParams = null;
        landscapeVideoBounds = null;
        tiktokScreenBounds = null;
    }

    private Rect findTikTokVideoBounds(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo player = findNodeById(root, ":id/player_view");
        if (player == null) return null;
        Rect screenBounds = new Rect();
        root.getBoundsInScreen(screenBounds);
        Rect playerBounds = new Rect();
        player.getBoundsInScreen(playerBounds);
        if (playerBounds.isEmpty() || !screenBounds.contains(playerBounds)) return null;
        Rect best = null;
        long bestArea = Long.MAX_VALUE;
        for (int i = 0; i < player.getChildCount(); i++) {
            AccessibilityNodeInfo child = player.getChild(i);
            if (child == null) continue;
            Rect bounds = new Rect();
            child.getBoundsInScreen(bounds);
            long area = (long) bounds.width() * bounds.height();
            if (bounds.width() >= playerBounds.width() / 4 &&
                bounds.height() >= playerBounds.height() / 3 && area < bestArea &&
                playerBounds.contains(bounds)) {
                best = bounds;
                bestArea = area;
            }
        }
        return best == null ? playerBounds : best;
    }

    private AccessibilityNodeInfo findNodeById(AccessibilityNodeInfo root, String suffix) {
        if (root == null) return null;
        String id = root.getViewIdResourceName();
        if (id != null && id.endsWith(suffix)) return root;
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo found = findNodeById(root.getChild(i), suffix);
            if (found != null) return found;
        }
        return null;
    }


    @Override public void onClick(View view) {
        if (collecting) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || !isSourcePackage(String.valueOf(root.getPackageName()))) {
            hideOverlay();
            return;
        }
        Log.d("MediaDownloader", "Overlay tapped; source=" + sourceApp +
            " active=" + (root == null ? "none" : root.getPackageName()));
        DiagnosticsStore.trace(this, "overlay.tap", "source=" +
            (root == null || root.getPackageName() == null ? "unavailable" : root.getPackageName()));
        String app = root == null || root.getPackageName() == null ? "" : root.getPackageName().toString();
        if (TIKTOK.equals(app) && isLandscape(root)) {
            if (findNodeById(root, ":id/player_view") == null) {
                Toast.makeText(this, "Open a video first", Toast.LENGTH_SHORT).show();
                return;
            }
            Rect screen = new Rect();
            root.getBoundsInScreen(screen);
            collecting = true;
            landscapeCapture = true;
            DiagnosticsStore.trace(this, "overlay.landscape", "Opening share in the full-screen player");
            tapTikTokLandscapeShare(screen.right - OverlaySettings.dp(this, 68),
                screen.top + OverlaySettings.dp(this, 36), 0, System.currentTimeMillis() + 5000);
            return;
        }
        AccessibilityNodeInfo share = null;
        AccessibilityNodeInfo story = null;
        boolean tiktokStory = false;
        String instagramStoryAccount = null;
        if (TIKTOK.equals(app)) {
            story = findNodeById(root, ":id/vp_story_immersive_feed");
            tiktokStory = story != null && story.isVisibleToUser();
            share = findTikTokShare(root);
        } else if (INSTAGRAM.equals(app)) {
            instagramStoryAccount = instagramStoryAccount(findInstagramStoryHeader(root));
            share = instagramStoryAccount == null ? findShare(root) : findInstagramStoryShare(root);
        } else if (REDNOTE.equals(app)) {
            share = findRedNoteShare(root);
        }
        AccessibilityNodeInfo action = clickableAncestor(share);
        if (action == null) {
            Log.d("MediaDownloader", "No actionable share control in the active post; found=" + (share != null));
            DiagnosticsStore.record(this, "overlay.share", "Share action unavailable on current post");
            Toast.makeText(this, "Open a post first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (tiktokStory) {
            AccessibilityNodeInfo title = findNodeById(root, ":id/title");
            String account = title == null || title.getText() == null ? "" : title.getText().toString();
            if (account.trim().isEmpty()) {
                Toast.makeText(this, "Could not identify the Story account", Toast.LENGTH_LONG).show();
                return;
            }
            collecting = true;
            Toast.makeText(this, "Collecting this account's Stories", Toast.LENGTH_SHORT).show();
            readTikTokStorySegments(account);
            return;
        }
        if (instagramStoryAccount != null) {
            collecting = true;
            Toast.makeText(this, "Collecting this account's Stories", Toast.LENGTH_SHORT).show();
            readInstagramStorySegments(instagramStoryAccount);
            return;
        }
        if (!action.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            Toast.makeText(this, "Open a post first", Toast.LENGTH_SHORT).show();
            return;
        }
        collecting = true;
        DiagnosticsStore.trace(this, "overlay.share", "Share control activated");
        handler.postDelayed(() -> clickCopyWhenReady(app, System.currentTimeMillis() + 4000),
            INSTAGRAM.equals(app) ? 1000 : 600);
    }

    private boolean isLandscape(AccessibilityNodeInfo root) {
        if (root != null) {
            Rect bounds = new Rect();
            root.getBoundsInScreen(bounds);
            if (!bounds.isEmpty()) return bounds.width() > bounds.height();
        }
        return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    private AccessibilityNodeInfo findTikTokShare(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo story = findNodeById(root, ":id/vp_story_immersive_feed");
        if (story != null && story.isVisibleToUser()) {
            Rect screen = new Rect();
            root.getBoundsInScreen(screen);
            AccessibilityNodeInfo share = findTikTokStoryShare(root, screen);
            if (share != null) return share;
        }
        AccessibilityNodeInfo share = find(root, "Share video");
        if (share == null) share = find(root, "Share photo");
        if (share == null) share = find(root, "Share post");
        if (share == null) share = find(root, "Share story");
        if (share == null) share = find(root, "Share Story");
        return share;
    }

    private AccessibilityNodeInfo findTikTokStoryShare(AccessibilityNodeInfo root, Rect screen) {
        if (root == null || !root.isVisibleToUser()) return null;
        CharSequence label = root.getContentDescription();
        if (label != null && "Share".contentEquals(label)) {
            Rect bounds = new Rect();
            root.getBoundsInScreen(bounds);
            if (bounds.centerX() >= screen.left + screen.width() * 4 / 5 &&
                bounds.centerY() >= screen.top + screen.height() * 4 / 5)
                return root;
        }
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo share = findTikTokStoryShare(root.getChild(i), screen);
            if (share != null) return share;
        }
        return null;
    }

    private void tapTikTokLandscapeShare(int x, int y, int taps, long deadline) {
        if (!collecting || !landscapeCapture) return;
        if (findActionInWindows("Copy link") != null) {
            DiagnosticsStore.trace(this, "overlay.landscape", "Share sheet opened without leaving full screen");
            handler.postDelayed(() -> clickCopyWhenReady(TIKTOK, System.currentTimeMillis() + 4000), 150);
            return;
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || !TIKTOK.equals(String.valueOf(root.getPackageName())) || !isLandscape(root)) {
            failCollecting(new Exception("TikTok full-screen video changed before sharing"));
            return;
        }
        if (taps == 2) {
            if (System.currentTimeMillis() < deadline) {
                handler.postDelayed(() -> tapTikTokLandscapeShare(x, y, taps, deadline), 200);
                return;
            }
            failCollecting(new Exception("Could not open TikTok's full-screen share menu"));
            return;
        }
        Path path = new Path();
        path.moveTo(x, y);
        android.accessibilityservice.GestureDescription gesture =
            new android.accessibilityservice.GestureDescription.Builder()
                .addStroke(new android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 70))
                .build();
        if (!dispatchGesture(gesture, new GestureResultCallback() {
            @Override public void onCompleted(android.accessibilityservice.GestureDescription ignored) {
                handler.postDelayed(() -> tapTikTokLandscapeShare(x, y, taps + 1, deadline), 400);
            }
            @Override public void onCancelled(android.accessibilityservice.GestureDescription ignored) {
                failCollecting(new Exception("Could not tap TikTok's full-screen share control"));
            }
        }, handler)) failCollecting(new Exception("Could not send the share tap"));
    }

    private void clickCopyWhenReady(String app, long deadline) {
        if (!collecting) return;
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
        if (INSTAGRAM.equals(app) && instagramStoryBatch != null && instagramShareRetries < 2) {
            instagramShareRetries++;
            Log.w("MediaDownloader", "Instagram Story share retry=" + instagramShareRetries);
            if (isInstagramShareSheetOpen()) dismissInstagramShareSheet();
            handler.postDelayed(() -> openNextInstagramStoryShare(System.currentTimeMillis() + 2500), 500);
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
        if (!collecting) return;
        Log.d("MediaDownloader", "Awaiting fresh clipboard link");
        DiagnosticsStore.trace(this, "overlay.copy_link", "Copy link control activated");
        handler.postDelayed(() -> {
                try {
                    if (!collecting) return;
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
        if (!collecting) return;
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
            if (storyBatch != null) {
                releaseOverlayFocus();
                storyBatch.captured(shareUrl);
                storyDirectionCaptures++;
                Log.d("MediaDownloader", "Story batch captured item=" + storyBatch.links().size() +
                    " direction=" + (storyScanningBackward ? "backward" : "forward"));
                if (findActionInWindows("Copy link") != null ||
                    findActionInWindows("Send to") != null)
                    performGlobalAction(GLOBAL_ACTION_BACK);
                if (storyDirectionCaptures >= storyBatch.segmentCount()) finishTikTokStoryDirection();
                else handler.postDelayed(this::navigateTikTokStory, 150);
                return;
            }
            if (instagramStoryBatch != null) {
                InstagramStoryBatch batch = instagramStoryBatch;
                batch.captured(shareUrl);
                instagramDirectionCaptures++;
                instagramShareRetries = 0;
                Log.i("MediaDownloader", "Instagram Story captured=" + batch.collectedCount() +
                    "/" + batch.segmentCount() + " backward=" + instagramScanningBackward);
                DiagnosticsStore.trace(this, "overlay.instagram_story", "captured=" +
                    batch.collectedCount() + "/" + batch.segmentCount());
                releaseOverlayFocus();
                boolean sheetOpen = isInstagramShareSheetOpen();
                Log.d("MediaDownloader", "Instagram Story share sheet open after copy=" + sheetOpen);
                closeInstagramStoryShareThenContinue(0);
                return;
            }
            Intent intent = new Intent(this, DownloadService.class);
            intent.putExtra("shareUrl", shareUrl);
            startForegroundService(intent);
            boolean shareSheetOpen = INSTAGRAM.equals(app) ? isInstagramShareSheetOpen() :
                findActionInWindows("Copy link") != null;
            if (INSTAGRAM.equals(app))
                Log.d("MediaDownloader", "Instagram share sheet open after copy=" + shareSheetOpen);
            finishCollecting();
            if (shareSheetOpen) {
                if (INSTAGRAM.equals(app))
                    handler.postDelayed(this::dismissInstagramShareSheet, 250);
                else performGlobalAction(GLOBAL_ACTION_BACK);
            }
        } catch (Exception error) { failCollecting(error); }
    }

    private void failCollecting(Exception error) {
        if (!collecting) return;
        DiagnosticsStore.record(this, "overlay.start_download", error);
        Log.e("MediaDownloader", "Could not start download", error);
        if (storyBatch != null) {
            int captured = storyBatch.links().size();
            storyBatch = null;
            releaseOverlayFocus();
            if (findActionInWindows("Copy link") != null || findActionInWindows("Send to") != null)
                performGlobalAction(GLOBAL_ACTION_BACK);
            finishCollecting();
            Toast.makeText(this, "Story collection stopped after " + captured +
                ". No downloads started: " + error.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        if (instagramStoryBatch != null) {
            int captured = instagramStoryBatch.collectedCount();
            instagramStoryBatch = null;
            releaseOverlayFocus();
            if (isInstagramShareSheetOpen()) dismissInstagramShareSheet();
            finishCollecting();
            Toast.makeText(this, "Story collection stopped after " + captured +
                ". No downloads started: " + error.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, "Could not start download: " + error.getMessage(), Toast.LENGTH_LONG).show();
        boolean closeShareSheet = (landscapeCapture && findActionInWindows("Copy link") != null) ||
            (INSTAGRAM.equals(sourceApp) && isInstagramShareSheetOpen());
        boolean instagramCapture = INSTAGRAM.equals(sourceApp);
        finishCollecting();
        if (closeShareSheet) {
            if (instagramCapture) handler.postDelayed(this::dismissInstagramShareSheet, 250);
            else performGlobalAction(GLOBAL_ACTION_BACK);
        }
    }

    private void finishCollecting() {
        releaseOverlayFocus();
        collecting = false;
        landscapeCapture = false;
        scheduleVisibilityCheck();
    }

    private void releaseOverlayFocus() {
        if (overlay != null && overlayParams != null) {
            overlayParams.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            windowManager.updateViewLayout(overlay, overlayParams);
            overlay.setFocusableInTouchMode(false);
        }
    }

    private String instagramStoryAccount(AccessibilityNodeInfo header) {
        if (header == null || header.getContentDescription() == null) return null;
        Matcher match = INSTAGRAM_STORY_HEADER.matcher(header.getContentDescription());
        return match.find() ? match.group(1) : null;
    }

    private AccessibilityNodeInfo findInstagramStoryHeader(AccessibilityNodeInfo node) {
        if (node == null || !node.isVisibleToUser()) return null;
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (bounds.top < getResources().getDisplayMetrics().heightPixels / 4 &&
            instagramStoryAccount(node) != null) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findInstagramStoryHeader(node.getChild(i));
            if (found != null) return found;
        }
        return null;
    }

    private AccessibilityNodeInfo findInstagramStoryShare(AccessibilityNodeInfo root) {
        if (root == null) return null;
        Rect screen = new Rect();
        root.getBoundsInScreen(screen);
        return findInstagramStoryShare(root, screen);
    }

    private AccessibilityNodeInfo findInstagramStoryShare(AccessibilityNodeInfo node, Rect screen) {
        if (node == null || !node.isVisibleToUser()) return null;
        if (node.getContentDescription() != null &&
            "Share".contentEquals(node.getContentDescription())) {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            if (bounds.centerX() >= screen.left + screen.width() * 4 / 5 &&
                bounds.centerY() >= screen.top + screen.height() * 4 / 5) return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findInstagramStoryShare(node.getChild(i), screen);
            if (found != null) return found;
        }
        return null;
    }

    private void readInstagramStorySegments(String account) {
        readInstagramStorySegments(account, 0);
    }

    private void readInstagramStorySegments(String account, int attempt) {
        takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
            @Override public void onSuccess(ScreenshotResult result) {
                HardwareBuffer buffer = result.getHardwareBuffer();
                Bitmap pixels = null;
                try {
                    if (!collecting || instagramStoryBatch != null) return;
                    Bitmap hardware = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
                    if (hardware == null) throw new Exception("Story screenshot is unavailable");
                    pixels = hardware.copy(Bitmap.Config.ARGB_8888, false);
                    hardware.recycle();
                    if (pixels == null) throw new Exception("Story pixels are unavailable");
                    AccessibilityNodeInfo root = getRootInActiveWindow();
                    if (!INSTAGRAM.equals(String.valueOf(root == null ? null : root.getPackageName())) ||
                        !account.equalsIgnoreCase(instagramStoryAccount(findInstagramStoryHeader(root))))
                        throw new Exception("Instagram Story account changed before capture");
                    int width = pixels.getWidth(), height = pixels.getHeight();
                    int[] above = new int[width], row = new int[width], below = new int[width];
                    List<Integer> best = new ArrayList<>();
                    // The Story frame boundary itself can resemble progress segments.
                    // The actual strip sits just below it, at about 8% of screen height.
                    int firstY = Math.max(6, height / 13);
                    int lastY = Math.min(height - 7, height / 10);
                    int bestY = -1;
                    for (int y = firstY; y <= lastY; y++) {
                        pixels.getPixels(above, 0, width, 0, y - 6, width, 1);
                        pixels.getPixels(row, 0, width, 0, y, width, 1);
                        pixels.getPixels(below, 0, width, 0, y + 6, width, 1);
                        List<Integer> centers = StoryProgressBar.centers(above, row, below);
                        if (centers.size() > best.size() &&
                            StoryProgressBar.evenlySpaced(centers, width)) {
                            best = centers;
                            bestY = y;
                        }
                    }
                    if (best.isEmpty() && attempt < 3) {
                        Log.d("MediaDownloader", "Instagram Story progress strip retry=" + (attempt + 1));
                        handler.postDelayed(() -> readInstagramStorySegments(account, attempt + 1), 250);
                        return;
                    }
                    instagramStoryBatch = new InstagramStoryBatch(account, best.size());
                    instagramDirectionCaptures = 0;
                    instagramScanningBackward = false;
                    instagramShareRetries = 0;
                    Log.i("MediaDownloader", "Instagram Story segments=" + best.size() + " stripY=" + bestY);
                    DiagnosticsStore.trace(OverlayAccessibilityService.this,
                        "overlay.instagram_story", "segments=" + best.size());
                    openNextInstagramStoryShare(System.currentTimeMillis() + 2500);
                } catch (Exception error) { failCollecting(error); }
                finally {
                    if (pixels != null) pixels.recycle();
                    if (buffer != null) buffer.close();
                }
            }
            @Override public void onFailure(int errorCode) {
                failCollecting(new Exception("Story screenshot failed (" + errorCode + ")"));
            }
        });
    }

    private void openNextInstagramStoryShare(long deadline) {
        InstagramStoryBatch batch = instagramStoryBatch;
        if (batch == null) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        String account = instagramStoryAccount(findInstagramStoryHeader(root));
        if (root == null || !INSTAGRAM.equals(String.valueOf(root.getPackageName())) || account == null) {
            if (System.currentTimeMillis() < deadline) {
                handler.postDelayed(() -> openNextInstagramStoryShare(deadline), 120);
                return;
            }
            failCollecting(new Exception("Instagram Story player is unavailable"));
            return;
        }
        if (!batch.isSameAccount(account)) {
            Log.i("MediaDownloader", "Instagram Story account changed during scan");
            finishInstagramStoryDirection();
            return;
        }
        AccessibilityNodeInfo action = clickableAncestor(findInstagramStoryShare(root));
        if (action != null && action.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            Log.i("MediaDownloader", "Instagram Story share opened backward=" + instagramScanningBackward);
            handler.postDelayed(() -> clickCopyWhenReady(INSTAGRAM, System.currentTimeMillis() + 4000), 700);
            return;
        }
        if (System.currentTimeMillis() < deadline) {
            handler.postDelayed(() -> openNextInstagramStoryShare(deadline), 120);
            return;
        }
        failCollecting(new Exception("Could not share the current Instagram Story"));
    }

    private void navigateInstagramStory() {
        if (instagramStoryBatch == null) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || !INSTAGRAM.equals(String.valueOf(root.getPackageName()))) {
            failCollecting(new Exception("Instagram Story player closed during collection"));
            return;
        }
        Rect screen = new Rect();
        root.getBoundsInScreen(screen);
        instagramShareRetries = 0;
        Path path = new Path();
        path.moveTo(screen.left + screen.width() * (instagramScanningBackward ? 3 : 97) / 100,
            screen.top + screen.height() / 3);
        android.accessibilityservice.GestureDescription gesture =
            new android.accessibilityservice.GestureDescription.Builder()
                .addStroke(new android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 60))
                .build();
        if (!dispatchGesture(gesture, new GestureResultCallback() {
            @Override public void onCompleted(android.accessibilityservice.GestureDescription ignored) {
                Log.i("MediaDownloader", "Instagram Story navigation completed backward=" +
                    instagramScanningBackward);
                handler.postDelayed(() -> openNextInstagramStoryShare(System.currentTimeMillis() + 2500), 650);
            }
            @Override public void onCancelled(android.accessibilityservice.GestureDescription ignored) {
                failCollecting(new Exception("Could not navigate the Instagram Story player"));
            }
        }, handler)) failCollecting(new Exception("Could not navigate the Instagram Story player"));
    }

    private void closeInstagramStoryShareThenContinue(int dismissAttempts) {
        InstagramStoryBatch batch = instagramStoryBatch;
        if (batch == null) return;
        if (isInstagramShareSheetOpen()) {
            if (dismissAttempts >= 4) {
                failCollecting(new Exception("Instagram Story share sheet stayed open"));
                return;
            }
            dismissInstagramShareSheet();
            handler.postDelayed(() -> closeInstagramStoryShareThenContinue(dismissAttempts + 1), 350);
            return;
        }
        if (batch.isComplete()) handler.postDelayed(this::finishInstagramStoryBatch, 250);
        else if (instagramDirectionCaptures >= batch.segmentCount())
            handler.postDelayed(this::finishInstagramStoryDirection, 250);
        else handler.postDelayed(this::navigateInstagramStory, 250);
    }

    private void finishInstagramStoryDirection() {
        InstagramStoryBatch batch = instagramStoryBatch;
        if (batch == null) return;
        if (batch.isComplete() || instagramScanningBackward) {
            finishInstagramStoryBatch();
            return;
        }
        instagramScanningBackward = true;
        instagramDirectionCaptures = 0;
        DiagnosticsStore.trace(this, "overlay.instagram_story", "scanning_backwards=true");
        navigateInstagramStory();
    }

    private void finishInstagramStoryBatch() {
        InstagramStoryBatch batch = instagramStoryBatch;
        if (batch == null) return;
        if (!batch.isComplete()) {
            failCollecting(new Exception("Found " + batch.collectedCount() + " of " +
                batch.segmentCount() + " distinct Stories"));
            return;
        }
        List<String> links = batch.links();
        instagramStoryBatch = null;
        finishCollecting();
        for (String link : links) {
            Intent intent = new Intent(this, DownloadService.class);
            intent.putExtra("shareUrl", link);
            startForegroundService(intent);
        }
        DiagnosticsStore.trace(this, "overlay.instagram_story", "queued=" + links.size());
        Toast.makeText(this, "Queued " + links.size() + " Stories for download", Toast.LENGTH_LONG).show();
    }

    private void readTikTokStorySegments(String account) {
        takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
            @Override public void onSuccess(ScreenshotResult result) {
                HardwareBuffer buffer = result.getHardwareBuffer();
                try {
                    if (!collecting || storyBatch != null) return;
                    Bitmap hardware = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
                    if (hardware == null) throw new Exception("Story screenshot is unavailable");
                    Bitmap pixels = hardware.copy(Bitmap.Config.ARGB_8888, false);
                    hardware.recycle();
                    if (pixels == null) throw new Exception("Story pixels are unavailable");
                    AccessibilityNodeInfo root = getRootInActiveWindow();
                    AccessibilityNodeInfo progress = findNodeById(root, ":id/ina");
                    if (progress == null) throw new Exception("Story progress strip is unavailable");
                    Rect bounds = new Rect();
                    progress.getBoundsInScreen(bounds);
                    int[] row = new int[pixels.getWidth()];
                    int[] above = new int[row.length];
                    int[] below = new int[row.length];
                    pixels.getPixels(above, 0, row.length, 0, bounds.top - 5, row.length, 1);
                    pixels.getPixels(row, 0, row.length, 0, bounds.centerY(), row.length, 1);
                    pixels.getPixels(below, 0, row.length, 0, bounds.bottom + 5, row.length, 1);
                    pixels.recycle();
                    List<Integer> centers = StoryProgressBar.centers(above, row, below);
                    Log.i("MediaDownloader", "Story progress pixels=" + row.length +
                        " y=" + bounds.centerY() + " samples=" +
                        Integer.toHexString(row[row.length / 20]) + "," +
                        Integer.toHexString(row[row.length / 2]) + "," +
                        Integer.toHexString(row[row.length * 19 / 20]) +
                        " segments=" + centers.size());
                    storyBatch = new TikTokStoryBatch(account, centers);
                    storyScanPass = 1;
                    storyDirectionCaptures = 0;
                    storyScanningBackward = false;
                    Log.i("MediaDownloader", "Story segments=" + storyBatch.segmentCount());
                    pauseTikTokStoryThenShare();
                } catch (Exception error) { failCollecting(error); }
                finally { buffer.close(); }
            }
            @Override public void onFailure(int errorCode) {
                failCollecting(new Exception("Story screenshot failed (" + errorCode + ")"));
            }
        });
    }

    private void navigateTikTokStory() {
        if (storyBatch == null) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        AccessibilityNodeInfo story = findNodeById(root, ":id/vp_story_immersive_feed");
        AccessibilityNodeInfo title = findNodeById(root, ":id/title");
        String account = title == null || title.getText() == null ? "" : title.getText().toString();
        if (root == null || !TIKTOK.equals(String.valueOf(root.getPackageName())) ||
            story == null || !story.isVisibleToUser() || !storyBatch.isSameTitle(account)) {
            finishTikTokStoryDirection();
            return;
        }
        Rect screen = new Rect();
        root.getBoundsInScreen(screen);
        Path path = new Path();
        path.moveTo(screen.left + screen.width() * (storyScanningBackward ? 12 : 88) / 100,
            screen.top + screen.height() / 3);
        android.accessibilityservice.GestureDescription gesture =
            new android.accessibilityservice.GestureDescription.Builder()
                .addStroke(new android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 60))
                .build();
        if (!dispatchGesture(gesture, new GestureResultCallback() {
            @Override public void onCompleted(android.accessibilityservice.GestureDescription ignored) {
                pauseTikTokStoryThenShare();
            }
            @Override public void onCancelled(android.accessibilityservice.GestureDescription ignored) {
                failCollecting(new Exception("Could not navigate the Story player"));
            }
        }, handler)) failCollecting(new Exception("Could not navigate the Story player"));
    }

    private void pauseTikTokStoryThenShare() {
        if (storyBatch == null) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || findNodeById(root, ":id/vp_story_immersive_feed") == null) {
            finishTikTokStoryDirection();
            return;
        }
        Rect screen = new Rect();
        root.getBoundsInScreen(screen);
        Path path = new Path();
        path.moveTo(screen.centerX(), screen.top + screen.height() / 3);
        android.accessibilityservice.GestureDescription hold =
            new android.accessibilityservice.GestureDescription.Builder()
                .addStroke(new android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 5000))
                .build();
        if (!dispatchGesture(hold, null, handler)) {
            failCollecting(new Exception("Could not pause the Story player"));
            return;
        }
        handler.postDelayed(() -> openNextTikTokStoryShare(System.currentTimeMillis() + 1500), 300);
    }

    private void openNextTikTokStoryShare(long deadline) {
        if (storyBatch == null) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        AccessibilityNodeInfo story = findNodeById(root, ":id/vp_story_immersive_feed");
        AccessibilityNodeInfo title = findNodeById(root, ":id/title");
        String account = title == null || title.getText() == null ? "" : title.getText().toString();
        if (root == null || !TIKTOK.equals(String.valueOf(root.getPackageName())) ||
            story == null || !story.isVisibleToUser() || account.isEmpty()) {
            if (System.currentTimeMillis() < deadline) {
                handler.postDelayed(() -> openNextTikTokStoryShare(deadline), 150);
                return;
            }
            finishTikTokStoryDirection();
            return;
        }
        if (!storyBatch.isSameTitle(account)) {
            finishTikTokStoryDirection();
            return;
        }
        Rect screen = new Rect();
        root.getBoundsInScreen(screen);
        AccessibilityNodeInfo action = clickableAncestor(findTikTokStoryShare(root, screen));
        if (action != null && action.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            handler.postDelayed(() -> clickCopyWhenReady(TIKTOK, System.currentTimeMillis() + 4000), 600);
            return;
        }
        if (System.currentTimeMillis() < deadline) {
            handler.postDelayed(() -> openNextTikTokStoryShare(deadline), 150);
            return;
        }
        failCollecting(new Exception("Could not share the next Story"));
    }

    private void finishTikTokStoryDirection() {
        if (storyBatch == null) return;
        if (storyScanningBackward || storyBatch.segmentCount() == 1) {
            finishTikTokStoryBatch();
            return;
        }
        storyScanningBackward = true;
        storyDirectionCaptures = 0;
        Log.i("MediaDownloader", "Story scan switching to backward pass");
        handler.postDelayed(() -> {
            if (storyBatch == null) return;
            AccessibilityNodeInfo root = getRootInActiveWindow();
            AccessibilityNodeInfo title = findNodeById(root, ":id/title");
            String account = title == null || title.getText() == null ? "" : title.getText().toString();
            if (storyBatch != null && storyBatch.isSameTitle(account)) pauseTikTokStoryThenShare();
            else {
                performGlobalAction(GLOBAL_ACTION_BACK);
                handler.postDelayed(() -> reopenTikTokStoryCard(System.currentTimeMillis() + 5000), 400);
            }
        }, 350);
    }

    private void reopenTikTokStoryCard(long deadline) {
        if (storyBatch == null) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        AccessibilityNodeInfo storyTitle = findNodeById(root, ":id/title");
        String storyAccount = storyTitle == null || storyTitle.getText() == null ? "" : storyTitle.getText().toString();
        if (storyBatch.isSameTitle(storyAccount)) {
            pauseTikTokStoryThenShare();
            return;
        }
        AccessibilityNodeInfo cardTitle = findNodeById(root, ":id/tv_username");
        String title = cardTitle == null || cardTitle.getText() == null ? "" : cardTitle.getText().toString();
        AccessibilityNodeInfo action = clickableAncestor(find(root, "View Stories"));
        if (storyBatch.isSameTitle(title) && action != null &&
            action.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            handler.postDelayed(this::pauseTikTokStoryThenShare, 350);
            return;
        }
        if (System.currentTimeMillis() < deadline) {
            handler.postDelayed(() -> reopenTikTokStoryCard(deadline), 150);
            return;
        }
        failCollecting(new Exception("Could not reopen this account's Stories"));
    }

    private void finishTikTokStoryBatch() {
        if (storyBatch == null) return;
        TikTokStoryBatch batch = storyBatch;
        List<String> links = batch.links();
        String accountTitle = batch.accountTitle();
        int pass = storyScanPass;
        if (links.isEmpty()) {
            failCollecting(new Exception("No Stories were found for this account"));
            return;
        }
        Toast.makeText(this, "Checking " + links.size() + " Story links", Toast.LENGTH_SHORT).show();
        storyValidation.execute(() -> {
            String creatorId = null;
            LinkedHashMap<String, String> unique = new LinkedHashMap<>();
            for (int i = 0; i < links.size(); i++) {
                try {
                    Log.i("MediaDownloader", "Story validating " + (i + 1) + "/" + links.size());
                    StreamResolver.Identity result = StreamResolver.identity(links.get(i),
                        (component, detail) -> DiagnosticsStore.trace(this, component,
                            "story_batch " + detail));
                    Log.i("MediaDownloader", "Story identity index=" + (i + 1) +
                        " post=" + result.postId + " account=" + result.accountId +
                        " titleMatch=" + accountTitle.equals(result.displayName));
                    if (accountTitle.equals(result.displayName)) {
                        if (creatorId == null) creatorId = result.accountId;
                        if (creatorId.equals(result.accountId))
                            unique.putIfAbsent(result.postId, links.get(i));
                    }
                } catch (Exception error) {
                    Log.w("MediaDownloader", "Story identity unavailable for item " + (i + 1) +
                        ": " + error.getMessage());
                    DiagnosticsStore.record(this, "overlay.story_identity", error.getMessage());
                }
            }
            Log.i("MediaDownloader", "Story unique=" + unique.size() +
                "/" + batch.segmentCount() + " pass=" + pass);
            if (unique.size() >= batch.segmentCount()) {
                List<String> verified = new ArrayList<>(unique.values());
                handler.post(() -> {
                    if (storyBatch != batch) return;
                    storyBatch = null;
                    finishCollecting();
                    for (String link : verified) {
                        Intent intent = new Intent(this, DownloadService.class);
                        intent.putExtra("shareUrl", link);
                        startForegroundService(intent);
                    }
                    Log.i("MediaDownloader", "Story batch queued=" + verified.size());
                    DiagnosticsStore.trace(this, "overlay.story_batch", "queued=" + verified.size());
                    Toast.makeText(this, "Queued " + verified.size() + " Stories for download", Toast.LENGTH_LONG).show();
                });
            } else if (pass < 3) {
                handler.post(() -> {
                    if (storyBatch != batch) return;
                    storyScanPass++;
                    storyScanningBackward = false;
                    storyDirectionCaptures = 0;
                    Toast.makeText(this, "Found " + unique.size() + " of " + batch.segmentCount() +
                        " Stories; scanning again", Toast.LENGTH_LONG).show();
                    performGlobalAction(GLOBAL_ACTION_BACK);
                    handler.postDelayed(() -> reopenTikTokStoryCard(System.currentTimeMillis() + 4000), 400);
                });
            } else {
                handler.post(() -> {
                    if (storyBatch == batch) failCollecting(new Exception("Found " + unique.size() +
                        " of " + batch.segmentCount() + " distinct Stories"));
                });
            }
        });
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
        Rect screen = new Rect();
        root.getBoundsInScreen(screen);
        int middle = screen.centerY();
        AccessibilityNodeInfo best = null;
        int bestDistance = Integer.MAX_VALUE;
        boolean bestIsReel = false;
        Rect bounds = new Rect();
        for (AccessibilityNodeInfo candidate : candidates) {
            candidate.getBoundsInScreen(bounds);
            String label = String.valueOf(candidate.getContentDescription());
            boolean reelRail = label.equals("Share") &&
                bounds.centerX() >= screen.left + screen.width() * 4 / 5 &&
                bounds.centerY() >= screen.top + screen.height() * 3 / 5;
            boolean feedRow = (label.startsWith("Send post.") || label.startsWith("Send reel.")) &&
                bounds.centerX() <= screen.left + screen.width() * 3 / 4 &&
                bounds.centerY() >= screen.top + screen.height() * 3 / 5;
            if (!reelRail && !feedRow) continue;
            int distance = Math.abs(bounds.centerY() - middle);
            // The full-screen Reel rail wins over any feed row left in the tree.
            if (best == null || (reelRail && !bestIsReel) ||
                (reelRail == bestIsReel && distance < bestDistance)) {
                best = candidate;
                bestDistance = distance;
                bestIsReel = reelRail;
            }
        }
        Log.d("MediaDownloader", "Instagram share target=" +
            (best == null ? "none" : bestIsReel ? "opened-reel" : "feed") +
            " candidates=" + candidates.size());
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

    private boolean isInstagramShareSheetOpen() {
        for (AccessibilityWindowInfo window : getWindows()) {
            AccessibilityNodeInfo root = window.getRoot();
            if (root == null || !INSTAGRAM.equals(String.valueOf(root.getPackageName()))) continue;
            if (findAction(root, "Copy link") != null) return true;
            Rect screen = new Rect();
            root.getBoundsInScreen(screen);
            if (hasLowerSearchField(root, screen.top + screen.height() / 3)) return true;
        }
        return false;
    }

    private void dismissInstagramShareSheet() {
        boolean sent = performGlobalAction(GLOBAL_ACTION_BACK);
        Log.d("MediaDownloader", "Closing Instagram share sheet actionSent=" + sent);
    }

    private boolean hasLowerSearchField(AccessibilityNodeInfo node, int minimumY) {
        if (node == null || !node.isVisibleToUser()) return false;
        CharSequence text = node.getText();
        CharSequence description = node.getContentDescription();
        if ((text != null && text.toString().startsWith("Search")) ||
            (description != null && description.toString().startsWith("Search"))) {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            if (bounds.top >= minimumY) return true;
        }
        for (int i = 0; i < node.getChildCount(); i++)
            if (hasLowerSearchField(node.getChild(i), minimumY)) return true;
        return false;
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
