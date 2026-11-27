package dev.stillmotion.wallpaper;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.SurfaceHolder;

final class CanvasBackend {
    private enum Producer { UNKNOWN, HARDWARE, SOFTWARE }

    private final Resources resources;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF target = new RectF();
    private Producer producer = Producer.UNKNOWN;
    private Bitmap animationImage;

    CanvasBackend(Resources resources) {
        this.resources = resources;
    }

    void newSurface() {
        releaseAnimation();
        producer = Producer.UNKNOWN;
    }

    boolean presentPoster(SurfaceHolder holder, Theme theme) {
        return present(holder, theme, null, 1f, true);
    }

    boolean prepareAnimation(Theme theme) {
        if (producer != Producer.HARDWARE) return false;
        animationImage = theme.decodePoster(resources, true);
        return animationImage != null;
    }

    boolean hasHardwareProducer() {
        return producer == Producer.HARDWARE;
    }

    boolean hasSoftwareProducer() {
        return producer == Producer.SOFTWARE;
    }

    boolean presentAnimation(SurfaceHolder holder, float zoom) {
        return animationImage != null && present(holder, null, animationImage, zoom, false);
    }

    void releaseAnimation() {
        // A recorded hardware draw may still reference it. Let Android retire the buffer.
        animationImage = null;
    }

    private boolean present(SurfaceHolder holder, Theme theme, Bitmap bitmap,
            float zoom, boolean allowSoftwareFallback) {
        if (!holder.getSurface().isValid()) return false;
        if (producer == Producer.UNKNOWN) {
            boolean posted = draw(holder, theme, bitmap, zoom, true);
            if (producer == Producer.HARDWARE) return posted;
            if (!allowSoftwareFallback) return false;
            // A CPU-locked Surface stays on the static backend for this surface lifetime.
            producer = Producer.SOFTWARE;
        }
        return draw(holder, theme, bitmap, zoom, producer == Producer.HARDWARE);
    }

    private boolean draw(SurfaceHolder holder, Theme theme, Bitmap bitmap,
            float zoom, boolean hardware) {
        Bitmap image;
        try {
            image = bitmap != null ? bitmap : theme.decodePoster(resources, hardware);
        } catch (RuntimeException invalidImage) {
            image = null;
        }
        if (theme == null && image == null) return false;
        try {
            Canvas canvas = hardware ? holder.lockHardwareCanvas() : holder.lockCanvas();
            if (canvas == null) return false;
            if (hardware) producer = Producer.HARDWARE;
            try {
                if (image == null) {
                    canvas.drawColor(Color.rgb(18, 29, 40));
                } else {
                    float scale = zoom * Math.max((float) canvas.getWidth() / image.getWidth(),
                            (float) canvas.getHeight() / image.getHeight());
                    float horizontalPadding = (canvas.getWidth() - image.getWidth() * scale) * 0.5f;
                    float verticalPadding = (canvas.getHeight() - image.getHeight() * scale) * 0.5f;
                    target.set(horizontalPadding, verticalPadding,
                            canvas.getWidth() - horizontalPadding, canvas.getHeight() - verticalPadding);
                    canvas.drawBitmap(image, null, target, paint);
                }
            } finally {
                holder.unlockCanvasAndPost(canvas);
            }
            return true;
        } catch (RuntimeException unavailableSurface) {
            return false;
        }
    }
}
