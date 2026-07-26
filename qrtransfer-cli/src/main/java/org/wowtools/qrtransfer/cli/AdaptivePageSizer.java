package org.wowtools.qrtransfer.cli;

final class AdaptivePageSizer {
    private final int minimum;
    private final int maximum;
    private final Integer fixed;
    private int current;
    private int successes;

    AdaptivePageSizer(int minimum, int initial, int maximum, Integer fixed) {
        if (minimum < 32 || maximum < minimum || initial < minimum || initial > maximum) {
            throw new IllegalArgumentException("页面大小范围无效");
        }
        if (fixed != null && (fixed < minimum || fixed > maximum)) {
            throw new IllegalArgumentException("固定页面大小必须位于最小值和最大值之间");
        }
        this.minimum = minimum;
        this.maximum = maximum;
        this.fixed = fixed;
        this.current = fixed == null ? initial : fixed;
    }

    int current() {
        return current;
    }

    void onSuccess() {
        if (fixed != null) {
            return;
        }
        successes++;
        if (successes >= 4) {
            current = Math.min(maximum, current + 128);
            successes = 0;
        }
    }

    boolean onFailure() {
        successes = 0;
        if (fixed != null) {
            return false;
        }
        int reduced = Math.max(minimum, (int) Math.floor(current * 0.7));
        boolean changed = reduced < current;
        current = reduced;
        return changed;
    }
}
