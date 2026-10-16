package com.qingtiao.app;

import java.util.LinkedHashMap;
import java.util.Map;

/** Small, in-memory retry budget. A changing countdown must not cause a tap storm. */
final class ClickGuard {
    private static final long[] RETRY_DELAYS = {0, 900, 2000, 4000};
    private final Map<String, Attempt> attempts = new LinkedHashMap<>();
    private long lastAction = -10000;

    long waitMillis(String target, long now) {
        Attempt attempt = attempts.get(target);
        if (attempt != null && now - attempt.started >= 30000) {
            attempts.remove(target);
            attempt = null;
        }
        long globalDelay = Math.max(0, 450 - (now - lastAction));
        if (attempt == null) return globalDelay;
        if (attempt.count >= RETRY_DELAYS.length) return -1;
        return Math.max(globalDelay, Math.max(0,
                RETRY_DELAYS[attempt.count] - (now - attempt.last)));
    }

    void record(String target, long now) {
        if (!attempts.containsKey(target) && attempts.size() >= 32) {
            attempts.remove(attempts.keySet().iterator().next());
        }
        Attempt attempt = attempts.get(target);
        if (attempt == null) {
            attempt = new Attempt(now);
            attempts.put(target, attempt);
        }
        attempt.count++;
        attempt.last = now;
        lastAction = now;
    }

    void clearTargets() {
        attempts.clear();
        // Retain the short global cooldown across transitions to avoid double clicks.
    }

    private static final class Attempt {
        final long started;
        long last;
        int count;

        Attempt(long now) { started = now; }
    }
}
