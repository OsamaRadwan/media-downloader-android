package com.codex.mediadownloader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    static final String EXTRA_PAGE = "com.codex.mediadownloader.page";
    static final String EXTRA_JOB_ID = "com.codex.mediadownloader.job_id";
    static final String DOWNLOADS_PAGE = "downloads";
    private static final String SETTINGS_PAGE = "settings";
    private static final int PICK_ICON_REQUEST = 42;
    private static final int EXPORT_DIAGNOSTICS_REQUEST = 43;
    private static final int PAGE = 0xff102330;
    private static final int CARD = 0xff1b3443;
    private static final int TEXT = 0xfff3f7f9;
    private static final int MUTED = 0xffb8cbd2;
    private static final int ACCENT = 0xff54dbc5;
    private OverlayPreferences prefs;
    private CustomIconStore customIcons;
    private String currentPage = DOWNLOADS_PAGE;
    private int focusJobId;
    private DownloadsPage downloadsPage;
    private DownloadRepository downloadRepository;
    private DiagnosticsRepository diagnosticsRepository;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refreshDownloads = new Runnable() {
        @Override public void run() {
            if (downloadsPage != null) {
                downloadsPage.refresh();
                handler.postDelayed(this, 750);
            }
        }
    };
    private ImageView preview;
    private FrameLayout liveButtonHost;
    private ImageView liveButton;
    private ScrollView settingsScroll;
    private RadioGroup iconStyleGroup;
    private final List<CustomIconButton> customIconButtons = new ArrayList<>();
    private Button arrowColorButton;
    private Button backgroundColorButton;
    private TextView overlayStatus;
    private TextView accessibilityStatus;
    private TextView notificationStatus;
    private TextView diagnosticsStatus;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(PAGE);
        getWindow().setNavigationBarColor(PAGE);
        prefs = new SharedPreferencesOverlayPreferences(this);
        customIcons = new CustomIconStore(this);
        downloadRepository = new PreferencesDownloadRepository(this);
        diagnosticsRepository = new FileDiagnosticsRepository(this);
        downloadRepository.importExistingAsync();
        if (state != null) currentPage = state.getString("page", DOWNLOADS_PAGE);
        if (getIntent() != null && DOWNLOADS_PAGE.equals(getIntent().getStringExtra(EXTRA_PAGE))) {
            currentPage = DOWNLOADS_PAGE;
            focusJobId = getIntent().getIntExtra(EXTRA_JOB_ID, 0);
        }
        buildScreen();
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        prefs.startObserving(() -> runOnUiThread(this::refreshPreview));
        refreshStatus();
        refreshPreview();
        handler.removeCallbacks(refreshDownloads);
        if (downloadsPage != null) handler.post(refreshDownloads);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(refreshDownloads);
        prefs.stopObserving();
        super.onPause();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (DOWNLOADS_PAGE.equals(intent.getStringExtra(EXTRA_PAGE))) {
            currentPage = DOWNLOADS_PAGE;
            focusJobId = intent.getIntExtra(EXTRA_JOB_ID, 0);
            handler.removeCallbacks(refreshDownloads);
            buildScreen();
            handler.post(refreshDownloads);
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("page", currentPage);
        super.onSaveInstanceState(state);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        refreshStatus();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == EXPORT_DIAGNOSTICS_REQUEST) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) writeDiagnostics(data.getData());
            return;
        }
        if (requestCode != PICK_ICON_REQUEST || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri image = data.getData();
        Toast.makeText(this, "Adding icon…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                CustomIconStore.Entry added = customIcons.importIcon(image);
                runOnUiThread(() -> {
                    prefs.selectCustomIcon(added.id);
                    if (SETTINGS_PAGE.equals(currentPage)) rebuildSettingsAtScroll();
                    Toast.makeText(this, "Added " + added.name, Toast.LENGTH_SHORT).show();
                });
            } catch (Exception error) {
                DiagnosticsStore.record(this, "settings.icon_import", error);
                runOnUiThread(() -> Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show());
            }
        }, "custom-icon-import").start();
    }

    private int dp(int value) { return OverlaySettings.dp(this, value); }

    private void buildScreen() {
        FrameLayout host = new FrameLayout(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(PAGE);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            root.setPadding(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom());
            return insets;
        });
        LinearLayout tabs = new LinearLayout(this);
        tabs.setPadding(dp(12), dp(8), dp(12), dp(8));
        root.addView(tabs, new LinearLayout.LayoutParams(-1, dp(60)));
        tab(tabs, "Downloads", DOWNLOADS_PAGE);
        tab(tabs, "Settings", SETTINGS_PAGE);
        preview = null;
        liveButtonHost = null;
        liveButton = null;
        settingsScroll = null;
        iconStyleGroup = null;
        customIconButtons.clear();
        arrowColorButton = null;
        backgroundColorButton = null;
        overlayStatus = null;
        accessibilityStatus = null;
        notificationStatus = null;
        diagnosticsStatus = null;
        if (DOWNLOADS_PAGE.equals(currentPage)) {
            downloadsPage = new DownloadsPage(this, new DownloadsController(downloadRepository), focusJobId);
            root.addView(downloadsPage.view(), new LinearLayout.LayoutParams(-1, 0, 1));
        } else {
            downloadsPage = null;
            root.addView(buildSettingsView(), new LinearLayout.LayoutParams(-1, 0, 1));
        }
        host.addView(root, new FrameLayout.LayoutParams(-1, -1));
        if (SETTINGS_PAGE.equals(currentPage)) {
            liveButtonHost = host;
            liveButton = new ImageView(this);
            liveButton.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            liveButton.setClickable(false);
            liveButton.setFocusable(false);
            host.addView(liveButton, new FrameLayout.LayoutParams(0, 0, Gravity.TOP | Gravity.LEFT));
            host.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop)
                    refreshLiveButton();
            });
        }
        setContentView(host);
        if (liveButton != null) host.post(this::refreshLiveButton);
        if (downloadsPage != null) downloadsPage.refresh();
    }

    private void tab(LinearLayout tabs, String title, String page) {
        Button button = new Button(this);
        button.setText(title);
        button.setAllCaps(false);
        boolean selected = page.equals(currentPage);
        button.setTextColor(selected ? PAGE : TEXT);
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(selected ? ACCENT : CARD));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, 1);
        params.leftMargin = dp(4);
        params.rightMargin = dp(4);
        tabs.addView(button, params);
        button.setOnClickListener(view -> {
            if (page.equals(currentPage)) return;
            handler.removeCallbacks(refreshDownloads);
            currentPage = page;
            focusJobId = 0;
            buildScreen();
            if (downloadsPage != null) handler.post(refreshDownloads);
        });
    }

    private View buildSettingsView() {
        ScrollView scroll = new ScrollView(this);
        settingsScroll = scroll;
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(PAGE);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(24), dp(20), dp(32));
        scroll.addView(page);

        addText(page, "Media Downloader", 27, TEXT, true, 0);
        addText(page, "The button at the screen border shows its live position and appearance. Use the larger preview below to inspect the icon. On TikTok, tap to download or hold to open this app. Changes save automatically.", 14, MUTED, false, 8);

        FrameLayout previewCard = new FrameLayout(this);
        GradientDrawable previewBackground = new GradientDrawable();
        previewBackground.setColor(CARD);
        previewBackground.setCornerRadius(dp(18));
        previewCard.setBackground(previewBackground);
        LinearLayout.LayoutParams previewCardParams = new LinearLayout.LayoutParams(-1, dp(120));
        previewCardParams.topMargin = dp(22);
        page.addView(previewCard, previewCardParams);
        preview = new ImageView(this);
        preview.setContentDescription("Download arrow preview");
        previewCard.addView(preview, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER));
        addText(page, "Preview", 12, MUTED, false, 7);

        section(page, "Placement");
        addText(page, "Screen side", 15, TEXT, true, 12);
        radioGroup(page, new String[]{"Left", "Right"}, OverlayOption.SIDE);
        slider(page, "Vertical position", OverlayOption.HEIGHT, "%");
        slider(page, "Distance from border", OverlayOption.SIDE_GAP, " dp");

        section(page, "Arrow and touch area");
        addText(page, "Icon style (transparent image)", 15, TEXT, true, 12);
        radioGroup(page, new String[]{"Solid", "Outline", "Simple", "Tray"}, OverlayOption.ICON_STYLE);
        addText(page, "Custom images", 15, TEXT, true, 14);
        addText(page, "Add a transparent image from your phone. It stays in the app and can be selected or removed later.", 13, MUTED, false, 5);
        button(page, "Add icon image", this::pickCustomIcon);
        for (CustomIconStore.Entry entry : customIcons.list()) customIconRow(page, entry);
        slider(page, "Button size", OverlayOption.SIZE, " dp");
        slider(page, "Padding around arrow", OverlayOption.PADDING, " dp");
        slider(page, "Arrow opacity", OverlayOption.ARROW_OPACITY, "%");
        arrowColorButton = button(page, "Arrow color", () -> colorDialog(OverlayOption.ARROW_COLOR));

        section(page, "Background");
        addText(page, "Style", 15, TEXT, true, 12);
        radioGroup(page, new String[]{"None", "Circle", "Rounded"}, OverlayOption.BACKGROUND_STYLE);
        backgroundColorButton = button(page, "Background color", () -> colorDialog(OverlayOption.BACKGROUND_COLOR));
        slider(page, "Background opacity", OverlayOption.BACKGROUND_OPACITY, "%");

        section(page, "App access");
        addText(page, "The arrow appears over TikTok. Allow these permissions for downloads and progress notifications.", 13, MUTED, false, 7);
        overlayStatus = addText(page, "", 13, MUTED, false, 12);
        button(page, "Display over other apps", () -> startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:" + getPackageName()))));
        accessibilityStatus = addText(page, "", 13, MUTED, false, 12);
        button(page, "Accessibility service", () -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        notificationStatus = addText(page, "", 13, MUTED, false, 12);
        button(page, "Notification settings", () -> {
            Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            startActivity(intent);
        });

        section(page, "Diagnostics");
        addText(page, "Failures are saved on this phone. Export a report with Android version, permission status, and redacted error details when a feature fails on another device.", 13, MUTED, false, 7);
        diagnosticsStatus = addText(page, "", 13, MUTED, false, 10);
        button(page, "Export diagnostic report", this::pickDiagnosticsDestination);

        section(page, "Reset");
        button(page, "Restore default appearance", () -> {
            prefs.reset();
            recreate();
        });
        refreshPreview();
        refreshStatus();
        return scroll;
    }

    private TextView addText(LinearLayout parent, String value, int size, int color, boolean bold, int topMargin) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        if (bold) text.setTypeface(null, 1);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(topMargin);
        parent.addView(text, params);
        return text;
    }

    private void section(LinearLayout page, String title) {
        addText(page, title, 20, ACCENT, true, 27);
    }

    private void pickCustomIcon() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(intent, PICK_ICON_REQUEST);
    }

    private void pickDiagnosticsDestination() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        intent.putExtra(Intent.EXTRA_TITLE, "MediaDownloader-diagnostics-" + stamp + ".txt");
        try { startActivityForResult(intent, EXPORT_DIAGNOSTICS_REQUEST); }
        catch (RuntimeException error) {
            DiagnosticsStore.record(this, "settings.export_picker", error);
            Toast.makeText(this, "Could not open a file picker", Toast.LENGTH_LONG).show();
        }
    }

    private void writeDiagnostics(Uri destination) {
        new Thread(() -> {
            try (OutputStream output = getContentResolver().openOutputStream(destination, "w")) {
                if (output == null) throw new Exception("Selected document could not be opened");
                diagnosticsRepository.exportTo(output);
                runOnUiThread(() -> Toast.makeText(this, "Diagnostic report saved", Toast.LENGTH_LONG).show());
            } catch (Exception error) {
                DiagnosticsStore.record(this, "settings.export_write", error);
                runOnUiThread(() -> Toast.makeText(this, "Could not save diagnostic report", Toast.LENGTH_LONG).show());
            }
        }, "diagnostics-export").start();
    }

    private void customIconRow(LinearLayout page, CustomIconStore.Entry entry) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, dp(50));
        rowParams.topMargin = dp(6);
        page.addView(row, rowParams);

        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setContentDescription(entry.name + " icon");
        android.graphics.Bitmap bitmap = customIcons.load(entry.id);
        if (bitmap != null) image.setImageBitmap(bitmap);
        row.addView(image, new LinearLayout.LayoutParams(dp(42), dp(42)));

        Button choose = new Button(this);
        choose.setAllCaps(false);
        choose.setSingleLine(true);
        LinearLayout.LayoutParams chooseParams = new LinearLayout.LayoutParams(0, dp(48), 1);
        chooseParams.leftMargin = dp(8);
        row.addView(choose, chooseParams);
        customIconButtons.add(new CustomIconButton(entry, choose));
        choose.setOnClickListener(view -> {
            prefs.selectCustomIcon(entry.id);
            if (iconStyleGroup != null) iconStyleGroup.clearCheck();
            refreshPreview();
        });

        Button remove = new Button(this);
        remove.setAllCaps(false);
        remove.setText("Remove");
        remove.setTextSize(11);
        remove.setTextColor(TEXT);
        remove.setBackgroundTintList(android.content.res.ColorStateList.valueOf(CARD));
        LinearLayout.LayoutParams removeParams = new LinearLayout.LayoutParams(dp(82), dp(48));
        removeParams.leftMargin = dp(4);
        row.addView(remove, removeParams);
        remove.setOnClickListener(view -> new AlertDialog.Builder(this)
            .setTitle("Remove icon image?")
            .setMessage(entry.name)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Remove", (dialog, which) -> {
                if (!customIcons.delete(entry.id)) {
                    Toast.makeText(this, "Could not remove icon", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (prefs.get(OverlayOption.ICON_STYLE) == 4 &&
                    entry.id.equals(prefs.selectedCustomIconId())) prefs.clearCustomIconSelection();
                rebuildSettingsAtScroll();
            }).show());
    }

    private void rebuildSettingsAtScroll() {
        int scrollY = settingsScroll == null ? 0 : settingsScroll.getScrollY();
        buildScreen();
        if (settingsScroll != null) settingsScroll.post(() -> settingsScroll.scrollTo(0, scrollY));
    }

    private void refreshCustomChoices() {
        for (CustomIconButton choice : customIconButtons) {
            boolean selected = prefs.get(OverlayOption.ICON_STYLE) == 4 &&
                choice.entry.id.equals(prefs.selectedCustomIconId());
            choice.button.setText(choice.entry.name + (selected ? " ✓" : ""));
            choice.button.setTextColor(selected ? PAGE : TEXT);
            choice.button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(selected ? ACCENT : CARD));
        }
    }

    private static final class CustomIconButton {
        final CustomIconStore.Entry entry;
        final Button button;
        CustomIconButton(CustomIconStore.Entry entry, Button button) {
            this.entry = entry;
            this.button = button;
        }
    }

    private RadioGroup radioGroup(LinearLayout page, String[] labels, OverlayOption option) {
        RadioGroup group = new RadioGroup(this);
        group.setOrientation(option == OverlayOption.ICON_STYLE ? RadioGroup.VERTICAL : RadioGroup.HORIZONTAL);
        group.setSaveEnabled(false);
        for (int i = 0; i < labels.length; i++) {
            RadioButton radio = new RadioButton(this);
            radio.setId(View.generateViewId());
            radio.setSaveEnabled(false);
            radio.setText(labels[i]);
            radio.setTextColor(TEXT);
            radio.setTextSize(13);
            radio.setButtonTintList(android.content.res.ColorStateList.valueOf(ACCENT));
            radio.setPadding(0, 0, dp(10), 0);
            if (option == OverlayOption.ICON_STYLE) {
                radio.setCompoundDrawablesWithIntrinsicBounds(0, 0, OverlaySettings.iconResource(this, i), 0);
                radio.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(TEXT));
                radio.setCompoundDrawablePadding(dp(12));
                radio.setMinHeight(dp(44));
            }
            group.addView(radio);
        }
        int selected = prefs.get(option);
        if (selected >= 0 && selected < labels.length) group.check(group.getChildAt(selected).getId());
        if (option == OverlayOption.ICON_STYLE) iconStyleGroup = group;
        group.setOnCheckedChangeListener((changedGroup, checkedId) -> {
            for (int i = 0; i < changedGroup.getChildCount(); i++) {
                if (changedGroup.getChildAt(i).getId() == checkedId) {
                    prefs.set(option, i);
                    break;
                }
            }
        });
        page.addView(group);
        return group;
    }

    private void slider(LinearLayout page, String title, OverlayOption option, String unit) {
        int min = option.min;
        int max = option.max;
        int initial = prefs.get(option);
        TextView label = addText(page, title + ": " + initial + unit, 15, TEXT, false, 18);
        SeekBar seek = new SeekBar(this);
        seek.setSaveEnabled(false);
        seek.setMax(max - min);
        seek.setProgress(initial - min);
        seek.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        seek.setThumbTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        page.addView(seek, new LinearLayout.LayoutParams(-1, dp(36)));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                int value = min + progress;
                label.setText(title + ": " + value + unit);
                if (fromUser) prefs.set(option, value);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
    }

    private Button button(LinearLayout page, String title, Runnable action) {
        Button button = new Button(this);
        button.setText(title);
        button.setAllCaps(false);
        button.setTextColor(TEXT);
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(CARD));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(50));
        params.topMargin = dp(8);
        page.addView(button, params);
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private void colorDialog(OverlayOption option) {
        String[] names = {"White", "Black", "Yellow", "Cyan", "Green", "Red", "Custom hex color…"};
        int[] colors = {Color.WHITE, Color.BLACK, Color.YELLOW, Color.CYAN, 0xff0bb99a, Color.RED};
        new AlertDialog.Builder(this).setTitle(option == OverlayOption.ARROW_COLOR ? "Arrow color" : "Background color")
            .setItems(names, (dialog, which) -> {
                if (which < colors.length) {
                    prefs.set(option, colors[which]);
                } else {
                    EditText input = new EditText(this);
                    input.setInputType(InputType.TYPE_CLASS_TEXT);
                    input.setSingleLine(true);
                    input.setText(String.format(Locale.US, "#%06X", prefs.get(option) & 0x00ffffff));
                    new AlertDialog.Builder(this).setTitle("Enter #RRGGBB")
                        .setView(input)
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Apply", (d, w) -> {
                            String value = input.getText().toString().trim();
                            if (!value.matches("#[0-9a-fA-F]{6}")) {
                                Toast.makeText(this, "Use a color such as #FFFFFF", Toast.LENGTH_LONG).show();
                                return;
                            }
                            prefs.set(option, Color.parseColor(value));
                        }).show();
                }
            }).show();
    }

    private void refreshPreview() {
        if (preview == null || prefs == null) return;
        OverlaySettings.style(preview, this, prefs, customIcons);
        int size = dp(prefs.get(OverlayOption.SIZE));
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) preview.getLayoutParams();
        params.width = size;
        params.height = size;
        preview.setLayoutParams(params);
        if (arrowColorButton != null) arrowColorButton.setText("Arrow color  " + colorLabel(OverlayOption.ARROW_COLOR));
        if (backgroundColorButton != null) backgroundColorButton.setText("Background color  " + colorLabel(OverlayOption.BACKGROUND_COLOR));
        refreshCustomChoices();
        refreshLiveButton();
    }

    private void refreshLiveButton() {
        if (liveButton == null || liveButtonHost == null || liveButtonHost.getWidth() == 0 || prefs == null) return;
        OverlaySettings.style(liveButton, this, prefs, customIcons);
        WindowManager.LayoutParams screen = new WindowManager.LayoutParams();
        OverlaySettings.place(screen, this, prefs);
        int x = prefs.get(OverlayOption.SIDE) == 0 ? screen.x : liveButtonHost.getWidth() - screen.x - screen.width;
        WindowInsets insets = liveButtonHost.getRootWindowInsets();
        int y = screen.y + (insets == null ? 0 : insets.getSystemWindowInsetTop());
        x = Math.max(0, Math.min(liveButtonHost.getWidth() - screen.width, x));
        y = Math.max(0, Math.min(liveButtonHost.getHeight() - screen.height, y));
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) liveButton.getLayoutParams();
        if (params.width != screen.width || params.height != screen.height ||
            params.leftMargin != x || params.topMargin != y) {
            params.width = screen.width;
            params.height = screen.height;
            params.leftMargin = x;
            params.topMargin = y;
            liveButton.setLayoutParams(params);
        }
    }

    private String colorLabel(OverlayOption option) {
        return String.format(Locale.US, "#%06X", prefs.get(option) & 0x00ffffff);
    }

    private void refreshStatus() {
        if (overlayStatus == null) return;
        overlayStatus.setText("Display over other apps: " + (Settings.canDrawOverlays(this) ? "Allowed" : "Off"));
        String services = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        boolean enabled = services != null && services.contains(getPackageName() + "/" + getPackageName() + ".OverlayAccessibilityService");
        accessibilityStatus.setText("Accessibility service: " + (enabled ? "On" : "Off"));
        boolean notifications = Build.VERSION.SDK_INT < 33 || checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED;
        notificationStatus.setText("Notifications: " + (notifications ? "Allowed" : "Off"));
        if (diagnosticsStatus != null)
            diagnosticsStatus.setText("Saved failure events: " + diagnosticsRepository.eventCount());
    }
}
