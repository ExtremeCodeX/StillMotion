package dev.stillmotion.wallpaper;

final class Transition {
    final long start;
    private final long end;
    private final int frameIntervalMillis;
    private final float zoomOffset;

    Transition(long start, int motion) {
        this.start = start;
        end = start + (motion >>> 16);
        int fps = (motion >>> 8) & 255;
        frameIntervalMillis = (1000 + fps - 1) / fps;
        zoomOffset = (motion & 255) / 100f;
    }

    boolean finished(long now) {
        return now >= end;
    }

    float zoom(long now) {
        float remaining = (float) (end - now) / (end - start);
        remaining = Math.max(0f, Math.min(1f, remaining));
        return 1f + zoomOffset * remaining * remaining;
    }

    long nextFrame(long submittedAt) {
        return Math.min(end, submittedAt + frameIntervalMillis);
    }
}
