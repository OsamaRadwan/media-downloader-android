package com.codex.mediadownloader;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.ImageView;

final class OverlaySettings {
    private static final String[] ICON_RESOURCES = {
        "download_arrow", "download_arrow_outline", "download_arrow_simple", "download_arrow_tray"
    };

    private OverlaySettings() { }

    static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static int iconResource(Context context, int style) {
        return context.getResources().getIdentifier(
            ICON_RESOURCES[style], "drawable", context.getPackageName());
    }

    static void style(ImageView icon, Context context, OverlayPreferences prefs, CustomIconStore icons) {
        int size = prefs.get(OverlayOption.SIZE);
        int padding = prefs.get(OverlayOption.PADDING);
        padding = Math.min(padding, (size - 20) / 2);
        int iconStyle = prefs.get(OverlayOption.ICON_STYLE);
        boolean customLoaded = false;
        if (iconStyle == 4) {
            android.graphics.Bitmap custom = icons.load(prefs.selectedCustomIconId());
            if (custom != null) {
                icon.setImageBitmap(custom);
                customLoaded = true;
            } else icon.setImageResource(iconResource(context, 0));
        } else {
            icon.setImageResource(iconResource(context, iconStyle));
        }
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setPadding(dp(context, padding), dp(context, padding), dp(context, padding), dp(context, padding));
        int color = prefs.get(OverlayOption.ARROW_COLOR) & 0x00ffffff;
        int opacity = prefs.get(OverlayOption.ARROW_OPACITY);
        if (customLoaded) {
            icon.setImageTintList(null);
            icon.setImageAlpha(Math.round(opacity * 255f / 100f));
        } else {
            icon.setImageAlpha(255);
            icon.setImageTintList(ColorStateList.valueOf((Math.round(opacity * 255f / 100f) << 24) | color));
        }

        int backgroundStyle = prefs.get(OverlayOption.BACKGROUND_STYLE);
        if (backgroundStyle == 0) {
            icon.setBackground(null);
        } else {
            GradientDrawable background = new GradientDrawable();
            background.setShape(backgroundStyle == 1 ? GradientDrawable.OVAL : GradientDrawable.RECTANGLE);
            if (backgroundStyle == 2) background.setCornerRadius(dp(context, 12));
            int backgroundColor = prefs.get(OverlayOption.BACKGROUND_COLOR) & 0x00ffffff;
            int backgroundOpacity = prefs.get(OverlayOption.BACKGROUND_OPACITY);
            background.setColor((Math.round(backgroundOpacity * 255f / 100f) << 24) | backgroundColor);
            icon.setBackground(background);
        }
    }

    static void place(WindowManager.LayoutParams params, Context context, OverlayPreferences prefs) {
        place(params, context, prefs, OverlayPlacement.TIKTOK);
    }

    static void place(WindowManager.LayoutParams params, Context context, OverlayPreferences prefs,
                      OverlayPlacement placement) {
        int size = prefs.get(OverlayOption.SIZE);
        params.width = dp(context, size);
        params.height = dp(context, size);
        params.gravity = (prefs.get(placement.side) == 0 ? Gravity.LEFT : Gravity.RIGHT) | Gravity.TOP;
        params.x = dp(context, prefs.get(placement.gap));
        int usableHeight = context.getResources().getDisplayMetrics().heightPixels;
        int center = usableHeight * prefs.get(placement.height) / 100;
        params.y = Math.max(0, Math.min(usableHeight - params.height, center - params.height / 2));
    }

    static void placeLandscapeVideo(WindowManager.LayoutParams params, Context context,
                                    OverlayPreferences prefs, Rect videoBounds) {
        place(params, context, prefs, OverlayPlacement.TIKTOK_LANDSCAPE);
        if (videoBounds == null || videoBounds.isEmpty()) return;
        int screenWidth = context.getResources().getDisplayMetrics().widthPixels;
        int screenHeight = context.getResources().getDisplayMetrics().heightPixels;
        boolean right = prefs.get(OverlayOption.TIKTOK_LANDSCAPE_SIDE) == 1;
        int left = OverlayGeometry.horizontal(screenWidth, videoBounds.left, videoBounds.right,
            params.width, dp(context, prefs.get(OverlayOption.TIKTOK_LANDSCAPE_GAP)),
            right);
        params.gravity = (right ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP;
        params.x = right ? screenWidth - left - params.width : left;
        params.y = OverlayGeometry.vertical(screenHeight, videoBounds.top, videoBounds.bottom,
            params.height, prefs.get(OverlayOption.TIKTOK_LANDSCAPE_HEIGHT));
    }
}
