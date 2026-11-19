package com.codex.mediadownloader;

import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import java.io.File;

final class MediaMux {
    private MediaMux() { }

    static final class Tracks {
        final int width;
        final int height;
        final String videoMime;
        final String audioMime;
        final long durationMs;

        Tracks(int width, int height, String videoMime, String audioMime, long durationMs) {
            this.width = width;
            this.height = height;
            this.videoMime = videoMime;
            this.audioMime = audioMime;
            this.durationMs = durationMs;
        }
    }

    static Tracks inspectMuxed(File file) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(file.getAbsolutePath());
            return inspect(extractor);
        } finally {
            extractor.release();
        }
    }

    static Tracks inspectMuxed(Context context, Uri uri) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(context, uri, null);
            return inspect(extractor);
        } finally {
            extractor.release();
        }
    }

    private static Tracks inspect(MediaExtractor extractor) throws Exception {
            int videoIndex = -1;
            int audioIndex = -1;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (mime == null) continue;
                if (videoIndex < 0 && mime.startsWith("video/")) videoIndex = i;
                if (audioIndex < 0 && mime.startsWith("audio/")) audioIndex = i;
            }
            if (videoIndex < 0) throw new Exception("MP4 has no video track");
            if (audioIndex < 0) throw new Exception("MP4 has no audio track");
            MediaFormat video = extractor.getTrackFormat(videoIndex);
            MediaFormat audio = extractor.getTrackFormat(audioIndex);
            extractor.selectTrack(videoIndex);
            if (extractor.getSampleTrackIndex() != videoIndex) throw new Exception("MP4 video track has no samples");
            extractor.unselectTrack(videoIndex);
            extractor.selectTrack(audioIndex);
            extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
            if (extractor.getSampleTrackIndex() != audioIndex) throw new Exception("MP4 audio track has no samples");
            long durationMs = video.containsKey(MediaFormat.KEY_DURATION) ? video.getLong(MediaFormat.KEY_DURATION) / 1000L : 0;
            return new Tracks(video.getInteger(MediaFormat.KEY_WIDTH), video.getInteger(MediaFormat.KEY_HEIGHT),
                video.getString(MediaFormat.KEY_MIME), audio.getString(MediaFormat.KEY_MIME), durationMs);
    }
}
