package dev.stillmotion.wallpaper;

final class NativePolicy {
    static final int VISIBLE = 1;
    static final int HIDDEN = 2;
    static final int SURFACE_READY = 3;
    static final int SURFACE_LOST = 4;
    static final int SCREEN_OFF = 5;
    static final int SCREEN_ON = 6;
    static final int USER_PRESENT = 7;
    static final int FINISH = 8;
    static final int CANCEL = 9;
    static final int DESTROY = 10;
    static final int DEFER_USER_PRESENT = 11;
    static final int TRY_DEFERRED_UNLOCK = 12;
    static final int START = 1;
    static final int STOP = 2;
    static final int WAIT = 3;

    static final boolean AVAILABLE;
    private static volatile String linkFailure;

    static {
        boolean loaded;
        try {
            System.loadLibrary("stillmotion_core");
            loaded = true;
        } catch (LinkageError | SecurityException unavailable) {
            loaded = false;
            recordFailure(unavailable);
        }
        AVAILABLE = loaded;
    }

    private NativePolicy() {}

    static native int reduce(int previous, int event, boolean allowed);
    static native int parseTheme(long descriptor);

    static int reduceForPlayback(int previous, int event, boolean allowed) {
        if (linkFailure == null) {
            try {
                return reduce(previous, event, allowed);
            } catch (LinkageError unavailable) {
                recordFailure(unavailable);
            }
        }
        return JavaPolicy.reduce(previous, event, allowed);
    }

    static int parseThemeForPlayback(long descriptor) {
        if (linkFailure == null) {
            try {
                return parseTheme(descriptor);
            } catch (LinkageError unavailable) {
                recordFailure(unavailable);
            }
        }
        return JavaPolicy.parseTheme(descriptor);
    }

    static String status() {
        String reason = linkFailure;
        return reason == null ? "rust" : "java (" + reason + ")";
    }

    private static void recordFailure(Throwable failure) {
        String message = failure.getMessage();
        // Keep the linker reason, while leaving installation paths out of the diagnostic.
        linkFailure = message == null ? failure.getClass().getSimpleName()
                : message.replaceAll("/[^\\s\"']+", "[path]");
    }
}
