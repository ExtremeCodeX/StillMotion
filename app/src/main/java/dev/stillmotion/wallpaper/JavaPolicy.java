package dev.stillmotion.wallpaper;

import static dev.stillmotion.wallpaper.NativePolicy.*;

// Same value protocol as native/src/lib.rs, checked against JNI on the host.
final class JavaPolicy {
    private static final int SURFACE = 1;
    private static final int SHOWN = 2;
    private static final int READY = SURFACE | SHOWN;
    private static final int WAS_OFF = 4;
    private static final int PENDING = 8;
    private static final int PLAYING = 16;
    private static final int DESTROYED = 32;

    private JavaPolicy() {}

    static int reduce(int state, int event, boolean allowed) {
        if (!isValid(state) || event < VISIBLE || event > TRY_DEFERRED_UNLOCK) return -1;
        if (state == DESTROYED) return state;
        int next = state;
        boolean ready = (state & READY) == READY;
        switch (event) {
            case VISIBLE: next |= SHOWN; break;
            case HIDDEN: next &= ~(SHOWN | PLAYING | PENDING); break;
            case SURFACE_READY: next = (next | SURFACE) & ~PLAYING; break;
            case SURFACE_LOST: next &= ~(SURFACE | PLAYING | PENDING); break;
            case SCREEN_OFF: next = (next | WAS_OFF) & ~(PENDING | PLAYING); break;
            case USER_PRESENT:
                if ((state & WAS_OFF) != 0 && ready && allowed) next |= PLAYING;
                next &= ~WAS_OFF;
                break;
            case DEFER_USER_PRESENT:
                if ((state & WAS_OFF) != 0) next = (next & ~WAS_OFF) | PENDING;
                break;
            case TRY_DEFERRED_UNLOCK:
                if ((state & PENDING) != 0 && ready && allowed) next = (next & ~PENDING) | PLAYING;
                break;
            case CANCEL:
            case FINISH: next &= ~(PLAYING | PENDING); break;
            case DESTROY: next = DESTROYED; break;
            default: break;
        }
        if (((state ^ next) & PLAYING) != 0) {
            return next | (((next & PLAYING) != 0 ? START : STOP) << 8);
        }
        return (state & PENDING) == 0 && (next & PENDING) != 0 ? next | (WAIT << 8) : next;
    }

    private static boolean isValid(int state) {
        if (state < 0 || state >= 64 || ((state & DESTROYED) != 0 && state != DESTROYED)) return false;
        if ((state & PENDING) != 0 && (state & (WAS_OFF | PLAYING)) != 0) return false;
        return (state & PLAYING) == 0 || (state & (READY | WAS_OFF | PENDING)) == READY;
    }

    static int parseTheme(long descriptor) {
        if ((descriptor >>> 40) != 0x530101 || (descriptor & 255) != 0) return 0;
        int fps = (int) (descriptor >>> 32) & 255;
        int duration = (int) (descriptor >>> 16) & 65535;
        int zoom = (int) (descriptor >>> 8) & 255;
        if (fps < 12 || fps > 30 || duration < 250 || duration > 2000 || zoom < 1 || zoom > 25) return 0;
        return (duration << 16) | (fps << 8) | zoom;
    }
}
