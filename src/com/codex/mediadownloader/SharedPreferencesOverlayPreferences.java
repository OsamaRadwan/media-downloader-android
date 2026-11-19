package com.codex.mediadownloader;

import android.content.Context;
import android.content.SharedPreferences;

/** Owns the existing overlay_settings keys and notifies the UI/service of changes. */
final class SharedPreferencesOverlayPreferences implements OverlayPreferences {
    private static final String FILE = "overlay_settings";
    private static final String CUSTOM_ICON_KEY = "selected_custom_icon";
    private final SharedPreferences prefs;
    private SharedPreferences.OnSharedPreferenceChangeListener listener;

    SharedPreferencesOverlayPreferences(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    @Override public int get(OverlayOption option) {
        try { return option.validate(prefs.getInt(option.key, option.fallback)); }
        catch (ClassCastException invalid) { return option.fallback; }
    }

    @Override public void set(OverlayOption option, int value) {
        prefs.edit().putInt(option.key, option.validate(value)).apply();
    }

    @Override public String selectedCustomIconId() {
        try { return prefs.getString(CUSTOM_ICON_KEY, ""); }
        catch (ClassCastException invalid) { return ""; }
    }

    @Override public void selectCustomIcon(String id) {
        if (!CustomIconStore.validId(id)) throw new IllegalArgumentException("Invalid icon ID");
        prefs.edit().putString(CUSTOM_ICON_KEY, id).putInt(OverlayOption.ICON_STYLE.key, 4).apply();
    }

    @Override public void clearCustomIconSelection() {
        prefs.edit().remove(CUSTOM_ICON_KEY).putInt(OverlayOption.ICON_STYLE.key, 0).apply();
    }

    @Override public void reset() { prefs.edit().clear().apply(); }

    @Override public void startObserving(Runnable onChange) {
        stopObserving();
        listener = (changed, key) -> onChange.run();
        prefs.registerOnSharedPreferenceChangeListener(listener);
    }

    @Override public void stopObserving() {
        if (listener != null) prefs.unregisterOnSharedPreferenceChangeListener(listener);
        listener = null;
    }
}
