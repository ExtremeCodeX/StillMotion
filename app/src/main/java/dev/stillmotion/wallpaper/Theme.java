package dev.stillmotion.wallpaper;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.DataInputStream;
import java.io.IOException;

final class Theme {
    static final String THEME_KEY = "theme";
    static final String VIDEO_THEME_KEY = "flyme_video";
    static final String FLYME_THEME_KEY = "flyme_theme_id";
    static final String STATIC_KEY = "static";
    static final Theme DUNES = new Theme("dunes", BuildConfig.DUNES_POSTER, BuildConfig.DUNES_MOTION);
    static final Theme ORBIT = new Theme("orbit", BuildConfig.ORBIT_POSTER, BuildConfig.ORBIT_MOTION);
    private static final int MAX_PIXELS = 2_100_000;

    final String id;
    final int poster;
    private final int motion;

    private Theme(String id, int poster, int motion) {
        this.id = id;
        this.poster = poster;
        this.motion = motion;
    }

    static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("wallpaper", Context.MODE_PRIVATE);
    }

    static Theme selected(SharedPreferences preferences) {
        return ORBIT.id.equals(preferences.getString(THEME_KEY, DUNES.id)) ? ORBIT : DUNES;
    }

    static Theme selectedVideo(SharedPreferences preferences) {
        int legacyIndex = Math.max(0, Math.min(3, preferences.getInt(VIDEO_THEME_KEY, 0)));
        return new Theme(preferences.getString(FLYME_THEME_KEY, FlymePack.id(legacyIndex)), 0, 0);
    }

    int readMotion(Resources resources) {
        try (DataInputStream input = new DataInputStream(resources.openRawResource(motion))) {
            long descriptor = input.readLong();
            return input.read() == -1 ? NativePolicy.parseThemeForPlayback(descriptor) : 0;
        } catch (IOException | Resources.NotFoundException invalid) {
            return 0;
        }
    }

    Bitmap decodePoster(Resources resources, boolean hardware) {
        if (poster == 0) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false;
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeResource(resources, poster, options);
        if (options.outWidth < 1 || options.outHeight < 1
                || (long) options.outWidth * options.outHeight > MAX_PIXELS) {
            return null;
        }
        options.inJustDecodeBounds = false;
        options.inPreferredConfig = hardware ? Bitmap.Config.HARDWARE : Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeResource(resources, poster, options);
    }
}
