package com.hongshikaikai.addon.utils.crystal;

/**
 * Millisecond wall-clock timer.
 *
 * <p>Deliberately a wall clock and not a tick counter: the modules gate placement and breaking on
 * real elapsed time ("300ms between placements"), and a tick counter would make the same setting
 * mean a different duration at a different tick rate.
 */
public class Timer {
    private long startTime = -1L;

    public Timer() {
        reset();
    }

    public void reset() {
        startTime = System.currentTimeMillis();
    }

    public long getMs() {
        return System.currentTimeMillis() - startTime;
    }

    public boolean passedS(double s) {
        return passed((long) (s * 1000.0));
    }

    public boolean passedMs(double ms) {
        return passed((long) ms);
    }

    public void setMs(long ms) {
        startTime = System.currentTimeMillis() - ms;
    }

    public boolean passed(long ms) {
        return System.currentTimeMillis() - startTime >= ms;
    }
}
