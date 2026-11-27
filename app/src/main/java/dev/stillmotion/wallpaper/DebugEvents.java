package dev.stillmotion.wallpaper;

import java.util.ArrayDeque;

final class DebugEvents {
    private static final int CAPACITY = 48;
    private static final ArrayDeque<String> events = new ArrayDeque<>(CAPACITY);

    private DebugEvents() {}

    static synchronized void add(String event) {
        if (events.size() == CAPACITY) events.removeFirst();
        events.addLast(event);
    }

    static synchronized String snapshot() {
        return String.join("\n", events);
    }
}
