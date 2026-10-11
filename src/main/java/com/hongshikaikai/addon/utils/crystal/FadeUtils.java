package com.hongshikaikai.addon.utils.crystal;

/** Progress of a timed fade. */
public class FadeUtils {
    public long length;
    private long start;

    public FadeUtils(long ms) {
        this.length = ms;
        this.reset();
    }

    public void reset() {
        this.start = System.currentTimeMillis();
    }

    public boolean isEnd() {
        return getTime() >= length;
    }

    protected long getTime() {
        return System.currentTimeMillis() - start;
    }

    public void setLength(long length) {
        this.length = length;
    }

    public double getFadeOne() {
        return isEnd() || length <= 0 ? 1.0 : (double) getTime() / length;
    }

    public double ease(Easing easing) {
        return easing.ease(Math.min(Math.max(getFadeOne(), 0.0), 1.0));
    }
}
