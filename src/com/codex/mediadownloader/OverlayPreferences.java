package com.codex.mediadownloader;

interface OverlayPreferences {
    int get(OverlayOption option);
    void set(OverlayOption option, int value);
    String selectedCustomIconId();
    void selectCustomIcon(String id);
    void clearCustomIconSelection();
    void reset();
    void startObserving(Runnable onChange);
    void stopObserving();
}
