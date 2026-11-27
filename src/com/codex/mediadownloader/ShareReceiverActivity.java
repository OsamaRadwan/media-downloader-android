package com.codex.mediadownloader;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

/** Starts the same download pipeline when a source app shares a post directly. */
public final class ShareReceiverActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent shared = getIntent();
        String link = shared != null && Intent.ACTION_SEND.equals(shared.getAction()) &&
            "text/plain".equals(shared.getType()) ?
            SharedPostLink.fromText(shared.getStringExtra(Intent.EXTRA_TEXT)) : null;
        if (link == null) {
            Toast.makeText(this, "Share one TikTok, Instagram, or RedNote post link", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        Intent download = new Intent(this, DownloadService.class);
        download.putExtra("shareUrl", link);
        try {
            startForegroundService(download);
            Toast.makeText(this, "Download queued", Toast.LENGTH_SHORT).show();
        } catch (RuntimeException error) {
            DiagnosticsStore.record(this, "share.start_download", error);
            Toast.makeText(this, "Could not start download", Toast.LENGTH_LONG).show();
        }
        finish();
    }
}
