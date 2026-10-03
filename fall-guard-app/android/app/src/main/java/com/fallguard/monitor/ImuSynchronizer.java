package com.fallguard.monitor;

import java.util.ArrayDeque;

/** Interpolate both sensor streams onto one monotonic 100 Hz timeline. No gap filling. */
final class ImuSynchronizer {
    static final long STEP_NS = 10_000_000L;
    static final long MAX_GAP_NS = 30_000_000L;
    interface Sink { void sample(long time, float[] values); void gap(); }
    private final Sink sink;
    private final ArrayDeque<Point> accel = new ArrayDeque<>(), gyro = new ArrayDeque<>();
    private long next;
    private static final class Point {
        final long time; final float[] xyz;
        Point(long time, float x, float y, float z) { this.time = time; xyz = new float[]{x,y,z}; }
    }
    ImuSynchronizer(Sink sink) { this.sink = sink; }
    void reset() { accel.clear(); gyro.clear(); next = 0; sink.gap(); }
    void add(boolean acceleration, long time, float x, float y, float z) {
        if (Float.isNaN(x) || Float.isInfinite(x) || Float.isNaN(y) || Float.isInfinite(y)
                || Float.isNaN(z) || Float.isInfinite(z)) { reset(); return; }
        ArrayDeque<Point> queue = acceleration ? accel : gyro;
        if (!queue.isEmpty() && (time <= queue.getLast().time || time - queue.getLast().time > MAX_GAP_NS)) reset();
        queue.add(new Point(time,x,y,z));
        if (queue.size() > 16) { reset(); return; }
        if (accel.size() < 2 || gyro.size() < 2) return;
        if (next == 0) next = Math.max(accel.getFirst().time, gyro.getFirst().time);
        while (next <= Math.min(accel.getLast().time, gyro.getLast().time)) {
            float[] values = new float[6];
            interpolate(accel, next, values, 0);
            interpolate(gyro, next, values, 3);
            sink.sample(next, values);
            next += STEP_NS;
        }
    }
    private static void interpolate(ArrayDeque<Point> queue, long time, float[] out, int offset) {
        while (queue.size() > 2) {
            Point first = queue.removeFirst();
            if (queue.getFirst().time > time) { queue.addFirst(first); break; }
        }
        Point a = queue.getFirst();
        java.util.Iterator<Point> points = queue.iterator();
        points.next();
        Point b = points.next();
        float fraction = (float) ((double) (time - a.time) / (b.time - a.time));
        for (int c = 0; c < 3; c++) out[offset+c] = a.xyz[c] + fraction * (b.xyz[c] - a.xyz[c]);
    }
}
