package dev.stillmotion.wallpaper;

final class OrbitaMotion {
    static final long WAKE_MS = 1_167;
    static final long ZOOM_MS = 1_050;
    private long wake = -1;
    private long zoom = -1;
    private float zoomFrom;
    private boolean asleep;
    private boolean settled;
    private boolean afterEnabled;
    private long settleTime;
    private long pausedAt = -1;
    private long pausedMillis;
    private boolean zoomInterrupted;

    void screenOff() {
        wake = zoom = -1;
        asleep = true;
        settled = false;
        afterEnabled = false;
        pausedAt = -1;
        pausedMillis = 0;
        zoomInterrupted = false;
    }

    void startWake(long now) {
        if (wake < 0 && zoom < 0) wake = now;
    }

    void startZoom(long now) {
        startZoom(now, false);
    }

    void startZoom(long now, boolean playAfter) {
        if (zoom >= 0) return;
        if (wake < 0) wake = now - WAKE_MS;
        zoomFrom = wakeProgress(now);
        zoom = now;
        afterEnabled = playAfter;
    }

    float wakeProgress(long now) {
        return zoom >= 0 ? zoomFrom + (1f - zoomFrom) * zoomProgress(now)
                : wake < 0 ? 0 : ease(now - wake, WAKE_MS);
    }

    float zoomProgress(long now) {
        return zoom < 0 ? 0 : settled || zoomInterrupted ? 1f : ease(now - zoom, ZOOM_MS);
    }

    float phase(long now) {
        if (wake < 0) return -WAKE_MS / 1000f;
        long time = zoom < 0 ? now : settled ? settleTime
                : afterEnabled ? (pausedAt < 0 ? now : pausedAt) - pausedMillis
                : Math.min(now, zoom + ZOOM_MS);
        return (time - wake - WAKE_MS) / 1000f;
    }

    boolean waitingForUnlock() {
        return zoom < 0 && (asleep || wake >= 0);
    }

    boolean zooming(long now) {
        return zoom >= 0 && !settled && !zoomInterrupted && now - zoom < ZOOM_MS;
    }

    void finishZoom() {
        if (zoom >= 0) {
            settleTime = zoom + ZOOM_MS;
            settled = true;
        }
    }

    boolean playingAfter(long now) {
        return afterEnabled && zoom >= 0 && !settled && (zoomInterrupted || now >= zoom + ZOOM_MS);
    }

    float afterStrength(long now) {
        return afterEnabled && zoom >= 0 ? Math.max(0f, (zoomProgress(now) - .8f) * 5f) : 0f;
    }

    void pauseAfter(long now) {
        if (!afterEnabled) {
            finishZoom();
            return;
        }
        if (zoom >= 0 && pausedAt < 0) {
            pausedAt = Math.max(now, zoom + ZOOM_MS);
            zoomInterrupted = true;
        }
    }

    void resumeAfter(long now) {
        if (pausedAt >= 0) {
            pausedMillis += now - pausedAt;
            pausedAt = -1;
        }
    }

    void disableAfter(long now) {
        if (!afterEnabled || zoom < 0 || settled) return;
        settleTime = Math.max(zoom + ZOOM_MS, (pausedAt < 0 ? now : pausedAt) - pausedMillis);
        afterEnabled = false;
        settled = true;
    }

    private static float ease(long elapsed, long duration) {
        float t = Math.max(0, Math.min(1, (float) elapsed / duration));
        return t * t * (3 - 2 * t);
    }
}
