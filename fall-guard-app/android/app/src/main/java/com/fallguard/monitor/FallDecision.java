package com.fallguard.monitor;

/** Model candidates and the cancellation/alarm state are separate. */
final class FallDecision {
    private int consecutive;
    boolean accept(double probability) {
        consecutive = probability >= 0.7 ? consecutive + 1 : 0;
        return consecutive >= 2;
    }
    void reset() { consecutive = 0; }
}
