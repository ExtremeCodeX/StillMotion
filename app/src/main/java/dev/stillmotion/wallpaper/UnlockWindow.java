package dev.stillmotion.wallpaper;

final class UnlockWindow {
    // OEM visibility and keyguard callbacks may follow USER_PRESENT in the same transition.
    private static final long GRACE_MILLIS = 300;
    private long deadline = -1;

    void arm(long now) {
        if (!armed()) deadline = now + GRACE_MILLIS;
    }

    boolean armed() {
        return deadline >= 0;
    }

    boolean expired(long now) {
        return now >= deadline;
    }

    void clear() {
        deadline = -1;
    }
}
