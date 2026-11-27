package com.codex.mediadownloader;

import android.content.Context;
import android.content.SharedPreferences;
import java.net.HttpURLConnection;
import java.net.URL;

/** App-owned route settings; connections already open keep their selected route. */
final class NetworkRouteStore {
    private static final String FILE = "network_route";
    private static volatile Context app;

    private NetworkRouteStore() { }

    static void init(Context context) { app = context.getApplicationContext(); }

    static NetworkRoute load(Context context) {
        SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
        try { return NetworkRoute.create(prefs.getInt("mode", 0), prefs.getString("host", ""), prefs.getInt("port", 0)); }
        catch (RuntimeException invalid) { return NetworkRoute.create(NetworkRoute.SYSTEM, "", 0); }
    }

    static void save(Context context, NetworkRoute route) {
        boolean saved = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putInt("mode", route.mode).putString("host", route.host).putInt("port", route.port).commit();
        if (!saved) throw new IllegalStateException("Could not save network route");
    }

    static HttpURLConnection open(URL url) throws Exception {
        Context context = app;
        if (context == null) return NetworkRoute.create(NetworkRoute.SYSTEM, "", 0).open(url);
        return load(context).open(url);
    }

    static boolean usesProxy() {
        Context context = app;
        return context != null && load(context).mode != NetworkRoute.SYSTEM;
    }
}
