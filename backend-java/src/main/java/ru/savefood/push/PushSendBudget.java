package ru.savefood.push;

import java.util.concurrent.atomic.AtomicInteger;

/** Shared across all recipient, volunteer, web-push and FCM jobs for one lot. */
public final class PushSendBudget {
    private final AtomicInteger remaining;
    public PushSendBudget(int maximum) {
        if (maximum < 0) throw new IllegalArgumentException("Negative push budget");
        remaining = new AtomicInteger(maximum);
    }
    public int remaining() { return remaining.get(); }
    public boolean tryAcquire() {
        return remaining.getAndUpdate(value -> Math.max(0, value - 1)) > 0;
    }
}
