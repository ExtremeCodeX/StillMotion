package dev.stillmotion.wallpaper;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class HostJniTest {
    private static int event(int state, int event, boolean allowed, int expectedEffect) {
        int result = NativePolicy.reduceForPlayback(state, event, allowed);
        if (result < 0 || (result >>> 8) != expectedEffect) {
            throw new AssertionError("Unexpected JNI result: " + result + " event=" + event);
        }
        return result & 255;
    }

    private static void checkCycles(int stopEvent) {
        int state = 0;
        for (int cycle = 0; cycle < 150; cycle++) {
            state = event(state, NativePolicy.SURFACE_READY, false, 0);
            state = event(state, NativePolicy.VISIBLE, false, 0);
            state = event(state, NativePolicy.SCREEN_OFF, false, 0);
            state = event(state, NativePolicy.SCREEN_ON, false, 0);
            state = event(state, NativePolicy.USER_PRESENT, true, 1);
            state = event(state, stopEvent, false, 2);
            state = event(state, NativePolicy.VISIBLE, false, 0);
            state = event(state, NativePolicy.USER_PRESENT, true, 0);
        }
        state = event(state, NativePolicy.DESTROY, false, 0);
        event(state, NativePolicy.DESTROY, true, 0);
        event(state, NativePolicy.USER_PRESENT, true, 0);
    }

    public static void main(String[] args) throws Exception {
        boolean fallback = args.length != 0 && args[0].equals("fallback");
        if (fallback == NativePolicy.AVAILABLE) throw new AssertionError(NativePolicy.status());
        if (!fallback) checkNativeParity();
        if (NativePolicy.reduceForPlayback(-1, 1, true) != -1
                || NativePolicy.reduceForPlayback(0, 300, true) != -1) {
            throw new AssertionError("Invalid JNI arguments must fail safely");
        }
        byte[] theme = {'S', 1, 1, 24, 4, (byte) 176, 15, 0};
        int motion = NativePolicy.parseThemeForPlayback(ByteBuffer.wrap(theme).getLong());
        if (motion != ((1200 << 16) | (24 << 8) | 15)
                || NativePolicy.parseThemeForPlayback(0) != 0) {
            throw new AssertionError("Unexpected JNI theme decoding");
        }
        checkTransition(motion);
        checkUnlockWindow();
        checkDiagnosticHistory();
        ExecutorService executor = Executors.newFixedThreadPool(4);
        List<Future<?>> tasks = new ArrayList<>();
        try {
            for (int stop : new int[] {NativePolicy.HIDDEN, NativePolicy.SURFACE_LOST,
                    NativePolicy.FINISH, NativePolicy.CANCEL}) {
                tasks.add(executor.submit(() -> checkCycles(stop)));
            }
            for (Future<?> task : tasks) task.get();
        } finally {
            executor.shutdownNow();
        }
        System.out.println((fallback ? "Java fallback" : "JNI JVM") + ": 600 independent cycles passed");
    }

    private static void checkNativeParity() {
        for (int state = -1; state <= 64; state++) {
            for (int event = 0; event <= 13; event++) {
                for (boolean allowed : new boolean[]{false, true}) {
                    if (NativePolicy.reduce(state, event, allowed) != JavaPolicy.reduce(state, event, allowed)) {
                        throw new AssertionError("Policy mismatch: state=" + state + " event=" + event);
                    }
                }
            }
        }
        byte[] theme = {'S', 1, 1, 24, 4, (byte) 176, 15, 0};
        for (int index = 0; index < theme.length; index++) {
            byte original = theme[index];
            for (int value = 0; value < 256; value++) {
                theme[index] = (byte) value;
                long encoded = ByteBuffer.wrap(theme).getLong();
                if (NativePolicy.parseTheme(encoded) != JavaPolicy.parseTheme(encoded)) {
                    throw new AssertionError("Theme validation mismatch at byte " + index);
                }
            }
            theme[index] = original;
        }
        System.out.println("Rust and Java policy and theme parity passed");
    }

    private static void checkTransition(int motion) {
        Transition transition = new Transition(10_000, motion);
        if (transition.finished(11_199) || !transition.finished(11_200)
                || !transition.finished(11_300)
                || transition.nextFrame(10_000) != 10_042
                || transition.nextFrame(10_500) != 10_542
                || transition.nextFrame(11_198) != 11_200
                || transition.zoom(10_000) <= transition.zoom(10_600)
                || transition.zoom(11_200) != 1f
                || transition.zoom(11_300) != 1f) {
            throw new AssertionError("Unexpected deadline, frame pacing, or final poster zoom");
        }
        System.out.println("Playback clock: deadline, late callback and final zoom passed");
    }

    private static void checkUnlockWindow() {
        UnlockWindow window = new UnlockWindow();
        if (window.armed()) throw new AssertionError("Unexpected pending unlock");
        window.arm(10_000);
        window.arm(10_299);
        if (!window.armed() || window.expired(10_299) || !window.expired(10_300)) {
            throw new AssertionError("The unlock window must not extend on duplicate events");
        }
        window.clear();
        if (window.armed()) throw new AssertionError("Pending unlock survived reset");
        window.arm(20_000);
        if (!window.armed() || window.expired(20_001)) throw new AssertionError("New unlock lost");
        int state = event(0, NativePolicy.SURFACE_READY, false, 0);
        state = event(state, NativePolicy.SCREEN_OFF, false, 0);
        state = event(state, NativePolicy.DEFER_USER_PRESENT, false, 3);
        state = event(state, NativePolicy.VISIBLE, false, 0);
        state = event(state, NativePolicy.TRY_DEFERRED_UNLOCK, true, 1);
        state = event(state, NativePolicy.HIDDEN, false, 2);
        event(state, NativePolicy.TRY_DEFERRED_UNLOCK, true, 0);
        System.out.println("Deferred unlock: bounded window, single playback and cancellation passed");
    }

    private static void checkDiagnosticHistory() {
        for (int index = 0; index < 100; index++) DebugEvents.add("event " + index);
        String[] history = DebugEvents.snapshot().split("\n");
        if (history.length != 48 || !history[0].equals("event 52") || !history[47].equals("event 99")) {
            throw new AssertionError("Diagnostic memory must keep only the latest events");
        }
    }
}
