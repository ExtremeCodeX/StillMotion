package dev.stillmotion.wallpaper;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.Surface;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

final class VideoClip extends MediaCodec.Callback {
    interface Listener {
        void ended(VideoClip clip, String reason, int submitted);
    }

    private static final long MAX_PLAYBACK_MS = 16_500;
    private static final long MAX_START_MS = 2_000;
    private static final long MAX_LATE_MS = 80;
    private final HandlerThread thread = new HandlerThread("stillmotion-video");
    private final Handler main;
    private final Listener listener;
    private final Object outputLock = new Object();
    private final Handler worker;
    private final long requestedAt = SystemClock.uptimeMillis();
    private volatile boolean cancelled;
    private boolean closed;
    private boolean inputEnded;
    private MediaCodec codec;
    private MediaExtractor extractor;
    private long firstPts = -1;
    private long firstFrame;
    private long lastPresentation;
    private int submitted;
    private boolean repeat;
    private long durationUs;
    private long loopOffsetUs;
    private long maxSampleUs = -1;

    VideoClip(Handler main, Listener listener) {
        this.main = main;
        this.listener = listener;
        thread.start();
        worker = new Handler(thread.getLooper());
    }

    void start(File file, Surface surface) {
        start(file, surface, false);
    }

    void start(File file, Surface surface, boolean loop) {
        if (!loop) worker.postAtTime(() -> close("video-deadline"), requestedAt + MAX_PLAYBACK_MS);
        worker.post(() -> {
            repeat = loop;
            open(file, surface);
        });
    }

    void cancel() {
        synchronized (outputLock) {
            if (cancelled) return;
            cancelled = true;
        }
        worker.postAtFrontOfQueue(() -> close("video-cancelled"));
    }

    private void open(File file, Surface surface) {
        if (cancelled || !surface.isValid()) {
            close("video-surface-unavailable");
            return;
        }
        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(file.getPath());
            MediaFormat format = selectVideoTrack();
            String decoder = format == null ? null : hardwareDecoder(format);
            if (decoder == null) {
                close("video-hardware-decoder-unavailable");
                return;
            }
            durationUs = durationIfSupported(format);
            if (repeat && (extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) == 0) {
                close("video-loop-no-sync-frame");
                return;
            }
            codec = MediaCodec.createByCodecName(decoder);
            codec.setCallback(this, worker);
            if (cancelled || !surface.isValid()) {
                close("video-cancelled");
                return;
            }
            codec.configure(format, surface, null, 0);
            if (cancelled) {
                close("video-cancelled");
                return;
            }
            codec.start();
            codec.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
            worker.postAtTime(() -> {
                if (firstPts < 0) close("video-start-timeout");
            }, requestedAt + MAX_START_MS);
        } catch (IOException | RuntimeException failure) {
            close("video-prepare-failed");
        }
    }

    private MediaFormat selectVideoTrack() {
        for (int index = 0; index < extractor.getTrackCount(); index++) {
            MediaFormat format = extractor.getTrackFormat(index);
            if (!MediaFormat.MIMETYPE_VIDEO_AVC.equals(format.getString(MediaFormat.KEY_MIME))) continue;
            if (durationIfSupported(format) < 0) return null;
            extractor.selectTrack(index);
            return format;
        }
        return null;
    }

    static long durationIfSupported(MediaFormat format) {
        if (!MediaFormat.MIMETYPE_VIDEO_AVC.equals(format.getString(MediaFormat.KEY_MIME))
                || !format.containsKey(MediaFormat.KEY_WIDTH) || !format.containsKey(MediaFormat.KEY_HEIGHT)
                || !format.containsKey(MediaFormat.KEY_DURATION)) return -1;
        int width = format.getInteger(MediaFormat.KEY_WIDTH);
        int height = format.getInteger(MediaFormat.KEY_HEIGHT);
        long duration = format.getLong(MediaFormat.KEY_DURATION);
        boolean supportedSize = (width == 1080 && height == 2340)
                || (width == 720 && height == 1560) || (width == 360 && height == 780);
        return supportedSize && duration > 0 && duration <= 14_100_000 ? duration : -1;
    }

    private static String hardwareDecoder(MediaFormat format) {
        if (Build.VERSION.SDK_INT < 29) return null;
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (info.isEncoder() || !info.isHardwareAccelerated() || info.isAlias()) continue;
            for (String mime : info.getSupportedTypes()) {
                if (MediaFormat.MIMETYPE_VIDEO_AVC.equalsIgnoreCase(mime)
                        && info.getCapabilitiesForType(mime).isFormatSupported(format)) {
                    return info.getName();
                }
            }
        }
        return null;
    }

    @Override
    public void onInputBufferAvailable(MediaCodec source, int index) {
        if (closed || cancelled || inputEnded) return;
        try {
            ByteBuffer buffer = source.getInputBuffer(index);
            if (buffer == null) {
                close("video-input-unavailable");
                return;
            }
            int count = extractor.readSampleData(buffer, 0);
            if (count < 0 && repeat) {
                loopOffsetUs += Math.max(durationUs, maxSampleUs + 1);
                maxSampleUs = -1;
                extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                count = extractor.readSampleData(buffer, 0);
                if (count < 0) {
                    close("video-loop-seek-failed");
                    return;
                }
            }
            if (count < 0) {
                inputEnded = true;
                source.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            } else {
                long timestamp = extractor.getSampleTime();
                maxSampleUs = Math.max(maxSampleUs, timestamp);
                source.queueInputBuffer(index, 0, count, timestamp + loopOffsetUs, 0);
                extractor.advance();
            }
        } catch (RuntimeException error) {
            close("video-input-failed");
        }
    }

    @Override
    public void onOutputBufferAvailable(MediaCodec source, int index, MediaCodec.BufferInfo info) {
        if (closed || cancelled) return;
        if (info.size > 0 && firstPts < 0) {
            firstPts = info.presentationTimeUs;
            firstFrame = SystemClock.uptimeMillis();
        }
        boolean end = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
        boolean picture = info.size > 0;
        if (picture) {
            long due = firstFrame + Math.max(0, (info.presentationTimeUs - firstPts) / 1_000);
            lastPresentation = Math.max(lastPresentation, due);
            worker.postAtTime(() -> submit(index, due, true), due);
        } else {
            submit(index, 0, false);
        }
        if (end) worker.postAtTime(() -> close("video-finished"), lastPresentation + 50);
    }

    private void submit(int index, long due, boolean picture) {
        try {
            synchronized (outputLock) {
                if (closed || cancelled) return;
                boolean render = picture && SystemClock.uptimeMillis() - due <= MAX_LATE_MS;
                codec.releaseOutputBuffer(index, render);
                if (render) submitted++;
            }
        } catch (RuntimeException error) {
            close("video-submit-failed");
        }
    }

    @Override
    public void onOutputFormatChanged(MediaCodec source, MediaFormat format) {}

    @Override
    public void onError(MediaCodec source, MediaCodec.CodecException failure) {
        close("video-codec-error");
    }

    private void close(String reason) {
        if (closed) return;
        closed = true;
        worker.removeCallbacksAndMessages(null);
        try {
            if (codec != null) codec.release();
        } catch (RuntimeException ignored) {
            reason = "video-release-failed";
        } finally {
            try {
                if (extractor != null) extractor.release();
            } catch (RuntimeException ignored) {
                reason = "video-extractor-release-failed";
            } finally {
                thread.quitSafely();
            }
        }
        String outcome = reason;
        codec = null;
        extractor = null;
        main.post(() -> listener.ended(this, outcome, submitted));
    }
}
