package com.codex.mediadownloader;

import java.net.URL;
import java.net.ServerSocket;
import java.net.HttpURLConnection;

public final class NetworkRouteTest {
    public static void main(String[] args) throws Exception {
        NetworkRoute system = NetworkRoute.create(NetworkRoute.SYSTEM, "ignored", 9999);
        check(system.mode == NetworkRoute.SYSTEM && system.host.isEmpty(), "System route must not retain proxy data");
        NetworkRoute http = NetworkRoute.create(NetworkRoute.HTTP_PROXY, " proxy.example ", 8080);
        check(http.host.equals("proxy.example") && http.open(new URL("https://example.com")).usingProxy(),
            "HTTP route must use an explicit proxy");
        try (ServerSocket listener = new ServerSocket(0)) {
            final String[] requestLine = {""};
            Thread peer = new Thread(() -> {
                try (java.net.Socket socket = listener.accept()) {
                    socket.setSoTimeout(2000);
                    requestLine[0] = new java.io.BufferedReader(new java.io.InputStreamReader(
                        socket.getInputStream())).readLine();
                    socket.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n".getBytes());
                } catch (Exception error) { throw new RuntimeException(error); }
            });
            peer.start();
            NetworkRoute local = NetworkRoute.create(NetworkRoute.HTTP_PROXY, "127.0.0.1", listener.getLocalPort());
            HttpURLConnection request = local.open(new URL("https://example.com"));
            request.setConnectTimeout(2000);
            request.setReadTimeout(2000);
            try { request.getResponseCode(); } catch (Exception expected) { }
            request.disconnect();
            peer.join(3000);
            check(requestLine[0].startsWith("CONNECT example.com:443 "),
                "HTTPS request did not use the HTTP proxy tunnel");
        }
        try (ServerSocket listener = new ServerSocket(0)) {
            final int[] firstByte = {-1};
            Thread peer = new Thread(() -> {
                try (java.net.Socket socket = listener.accept()) {
                    socket.setSoTimeout(2000);
                    firstByte[0] = socket.getInputStream().read();
                } catch (Exception error) { throw new RuntimeException(error); }
            });
            peer.start();
            NetworkRoute socks = NetworkRoute.create(NetworkRoute.SOCKS_PROXY, "127.0.0.1", listener.getLocalPort());
            HttpURLConnection request = socks.open(new URL("https://example.com"));
            request.setConnectTimeout(2000);
            request.setReadTimeout(2000);
            try { request.getResponseCode(); } catch (Exception expected) { }
            request.disconnect();
            peer.join(3000);
            check(firstByte[0] == 5, "SOCKS route did not start a SOCKS5 handshake");
        }
        rejects(() -> NetworkRoute.create(NetworkRoute.HTTP_PROXY, "bad host", 8080));
        rejects(() -> NetworkRoute.create(NetworkRoute.HTTP_PROXY, "proxy.example", 0));
        rejects(() -> NetworkRoute.create(9, "proxy.example", 8080));
        rejects(() -> http.open(new URL("http://example.com")));
    }

    private static void rejects(Checked action) throws Exception {
        try { action.run(); throw new AssertionError("Invalid route was accepted"); }
        catch (IllegalArgumentException expected) { }
    }

    private static void check(boolean valid, String message) {
        if (!valid) throw new AssertionError(message);
    }

    interface Checked { void run() throws Exception; }
}
