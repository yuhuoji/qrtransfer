package org.wowtools.qrtransfer.cli;

final class AdaptiveDelay {
    private final long minimum;
    private final long maximum;
    private long current;
    private double ewma;

    AdaptiveDelay(long initial, long minimum, long maximum) {
        if (minimum < 0 || maximum < minimum || initial < minimum || initial > maximum) {
            throw new IllegalArgumentException("等待时间范围无效");
        }
        this.current = initial;
        this.minimum = minimum;
        this.maximum = maximum;
        this.ewma = initial;
    }

    long current() {
        return current;
    }

    void onSuccess(long observedMillis) {
        ewma = ewma * 0.8 + Math.max(1, observedMillis) * 0.2;
        current = clamp(Math.round(ewma * 0.75));
    }

    void onFailure() {
        current = clamp(Math.max(current + 1, Math.round(current * 1.5)));
        ewma = Math.max(ewma, current);
    }

    private long clamp(long value) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
