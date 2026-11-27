package dev.stillmotion.wallpaper;

/** Conservative pixel bounds for the fragment shader's two possible contours. */
final class OrbitaBounds {
    int left;
    int bottom;
    int width;
    int height;

    void update(int surfaceWidth, int surfaceHeight, float wake, float zoom, boolean radial) {
        float scale = Math.max(surfaceWidth / 360f, surfaceHeight / 780f);
        float centerX = 200f * wake + 60f * zoom;
        float centerY = -234f + 257f * wake - 48f * zoom;
        float radius = 41f + 292f * wake + 294f * zoom;
        // The sum of absolute wave coefficients bounds the largest displacement.
        float margin = radial ? 8f : (3.4f + 15f * wake)
                * (.6f + .4f * (float) Math.sin(Math.PI * zoom)) * 1.58f;
        float extent = (radius + margin) * scale + 2f;
        float x = surfaceWidth * .5f + centerX * scale;
        float y = surfaceHeight * .5f - centerY * scale;
        float offsetX = radial ? 0f : (2f + 9f * wake) * scale;
        float offsetY = radial ? 0f : (1f + 5f * wake) * scale;
        left = clip((int) Math.floor(x - extent), surfaceWidth);
        bottom = clip((int) Math.floor(y - extent - offsetY), surfaceHeight);
        int right = clip((int) Math.ceil(x + extent + offsetX), surfaceWidth);
        int top = clip((int) Math.ceil(y + extent), surfaceHeight);
        width = right - left;
        height = top - bottom;
    }

    private static int clip(int coordinate, int limit) {
        return Math.min(limit, Math.max(0, coordinate));
    }
}
