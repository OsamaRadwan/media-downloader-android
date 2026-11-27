package com.codex.mediadownloader;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Ikev2VpnProfile;
import android.net.VpnManager;
import android.net.VpnProfileState;
import android.os.Build;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

/** Uses Android's managed IKEv2 tunnel; credentials are handed to Android, not app preferences. */
final class PlatformVpn {
    private static final String CA_FILE = "vpn_ca.der";
    static final int PSK = 0;
    static final int USER_PASSWORD = 1;
    private PlatformVpn() { }

    static boolean supported(Context context) {
        return Build.VERSION.SDK_INT >= 30 &&
            context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_IPSEC_TUNNELS) &&
            context.getSystemService(VpnManager.class) != null;
    }

    static Intent provision(Context context, String server, String identity,
                            int authMode, String username, String secret) {
        if (!supported(context)) throw new IllegalStateException("IKEv2 VPN is unavailable on this phone");
        String host = server == null ? "" : server.trim();
        String id = identity == null ? "" : identity.trim();
        if (host.isEmpty() || host.length() > 253 || !host.matches("[A-Za-z0-9.:-]+"))
            throw new IllegalArgumentException("Enter a VPN server host or IP address");
        if (id.isEmpty() || id.length() > 253) throw new IllegalArgumentException("Enter the server identity");
        if (secret == null || secret.isEmpty()) throw new IllegalArgumentException("Enter the VPN key or password");
        Ikev2VpnProfile.Builder builder = new Ikev2VpnProfile.Builder(host, id);
        if (authMode == PSK) builder.setAuthPsk(secret.getBytes(StandardCharsets.UTF_8));
        else if (authMode == USER_PASSWORD) {
            String user = username == null ? "" : username.trim();
            if (user.isEmpty()) throw new IllegalArgumentException("Enter the VPN username");
            builder.setAuthUsernamePassword(user, secret, loadCa(context));
        } else throw new IllegalArgumentException("Unsupported VPN authentication");
        Ikev2VpnProfile profile = builder.build();
        return context.getSystemService(VpnManager.class).provisionVpnProfile(profile);
    }

    static void connect(Context context) {
        if (!supported(context)) throw new IllegalStateException("IKEv2 VPN is unavailable on this phone");
        VpnManager manager = context.getSystemService(VpnManager.class);
        if (Build.VERSION.SDK_INT >= 33) manager.startProvisionedVpnProfileSession();
        else manager.startProvisionedVpnProfile();
    }

    static void disconnect(Context context) {
        if (!supported(context)) return;
        context.getSystemService(VpnManager.class).stopProvisionedVpnProfile();
    }

    static String status(Context context) {
        if (!supported(context)) return "IKEv2 VPN unavailable on this phone";
        try {
            VpnProfileState state = context.getSystemService(VpnManager.class).getProvisionedVpnProfileState();
            if (state == null) return "No Media Downloader VPN profile configured";
            switch (state.getState()) {
                case VpnProfileState.STATE_CONNECTED: return "Media Downloader VPN connected";
                case VpnProfileState.STATE_CONNECTING: return "Media Downloader VPN connecting";
                case VpnProfileState.STATE_FAILED: return "Media Downloader VPN failed";
                default: return "Media Downloader VPN disconnected";
            }
        } catch (RuntimeException error) { return "VPN status unavailable"; }
    }

    static void importCa(Context context, InputStream input) throws Exception {
        ByteArrayOutputStream limited = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (limited.size() + count > 262144)
                throw new IllegalArgumentException("VPN CA certificate is too large");
            limited.write(buffer, 0, count);
        }
        X509Certificate certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
            .generateCertificate(new ByteArrayInputStream(limited.toByteArray()));
        if (certificate.getBasicConstraints() < 0)
            throw new IllegalArgumentException("The selected certificate is not a CA certificate");
        byte[] encoded = certificate.getEncoded();
        if (encoded.length > 262144) throw new IllegalArgumentException("VPN CA certificate is too large");
        try (FileOutputStream output = context.openFileOutput(CA_FILE, Context.MODE_PRIVATE)) {
            output.write(encoded);
        }
    }

    static boolean hasCa(Context context) { return new File(context.getFilesDir(), CA_FILE).isFile(); }

    static void clearCa(Context context) { context.deleteFile(CA_FILE); }

    private static X509Certificate loadCa(Context context) {
        if (!hasCa(context)) return null;
        try (FileInputStream input = context.openFileInput(CA_FILE)) {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
        } catch (Exception error) { throw new IllegalStateException("Could not read the VPN CA certificate", error); }
    }
}
