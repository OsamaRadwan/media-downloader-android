package com.codex.mediadownloader;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import java.io.InterruptedIOException;

/** Waits inside an active transfer while Android has no default internet network. */
final class NetworkWait {
    private NetworkWait() { }

    static boolean awaitIfOffline(Context context, Runnable onWaiting) throws InterruptedIOException {
        ConnectivityManager manager = (ConnectivityManager)
            context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null || connected(manager)) return false;
        onWaiting.run();
        Object signal = new Object();
        ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) { wake(); }
            @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                wake();
            }
            private void wake() { synchronized (signal) { signal.notifyAll(); } }
        };
        boolean registered = false;
        try {
            try {
                manager.registerDefaultNetworkCallback(callback);
                registered = true;
            } catch (RuntimeException unavailable) {
                // Periodic checks still recover when callback registration is unavailable.
            }
            while (!connected(manager)) {
                synchronized (signal) {
                    if (!connected(manager)) signal.wait(5000);
                }
            }
            return true;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            InterruptedIOException stopped = new InterruptedIOException("Network wait interrupted");
            stopped.initCause(error);
            throw stopped;
        } finally {
            if (registered) manager.unregisterNetworkCallback(callback);
        }
    }

    private static boolean connected(ConnectivityManager manager) {
        Network network = manager.getActiveNetwork();
        NetworkCapabilities capabilities = network == null ? null :
            manager.getNetworkCapabilities(network);
        return capabilities != null &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }
}
