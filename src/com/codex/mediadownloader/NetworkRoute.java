package com.codex.mediadownloader;

import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;

/** Validated route for this app's HTTPS requests. Mode 0 uses Android's system network. */
final class NetworkRoute {
    static final int SYSTEM = 0;
    static final int HTTP_PROXY = 1;
    static final int SOCKS_PROXY = 2;
    final int mode;
    final String host;
    final int port;

    private NetworkRoute(int mode, String host, int port) {
        this.mode = mode;
        this.host = host;
        this.port = port;
    }

    static NetworkRoute create(int mode, String host, int port) {
        if (mode == SYSTEM) return new NetworkRoute(SYSTEM, "", 0);
        if (mode != HTTP_PROXY && mode != SOCKS_PROXY) throw new IllegalArgumentException("Unknown route type");
        String clean = host == null ? "" : host.trim();
        if (clean.length() < 1 || clean.length() > 253 || !clean.matches("[A-Za-z0-9.:-]+") ||
            clean.startsWith(".") || clean.endsWith(".") || clean.contains(".."))
            throw new IllegalArgumentException("Enter a proxy host or IP address");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Proxy port must be 1–65535");
        return new NetworkRoute(mode, clean, port);
    }

    HttpURLConnection open(URL url) throws Exception {
        if (!"https".equalsIgnoreCase(url.getProtocol()))
            throw new IllegalArgumentException("Only HTTPS downloads are supported");
        if (mode == SYSTEM) return (HttpURLConnection) url.openConnection();
        Proxy.Type type = mode == HTTP_PROXY ? Proxy.Type.HTTP : Proxy.Type.SOCKS;
        return (HttpURLConnection) url.openConnection(new Proxy(type,
            InetSocketAddress.createUnresolved(host, port)));
    }

    String label() {
        return mode == SYSTEM ? "System network" :
            (mode == HTTP_PROXY ? "HTTP proxy" : "SOCKS proxy") + " • " + host + ":" + port;
    }
}
