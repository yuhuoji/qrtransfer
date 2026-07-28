package org.wowtools.qrtransfer.cli;

final class AdaptivePageSizer {
    private final int minimum;
    private final int maximum;
    private final Integer fixed;
    private final boolean pageLocalRecovery;
    private int target;
    private int current;
    private int successes;

    AdaptivePageSizer(int minimum, int initial, int maximum, Integer fixed) {
        this(minimum, initial, maximum, fixed, false);
    }

    AdaptivePageSizer(int minimum, int initial, int maximum, Integer fixed,
                      boolean pageLocalRecovery) {
        if (minimum < 32 || maximum < minimum || initial < minimum || initial > maximum) {
            throw new IllegalArgumentException("页面大小范围无效");
        }
        if (fixed != null && (fixed < minimum || fixed > maximum)) {
            throw new IllegalArgumentException("固定页面大小必须位于最小值和最大值之间");
        }
        this.minimum = minimum;
        this.maximum = maximum;
        this.fixed = fixed;
        this.pageLocalRecovery = pageLocalRecovery;
        this.target = fixed == null ? initial : fixed;
        this.current = target;
    }

    int current() {
        return current;
    }

    int target() {
        return target;
    }

    void onSuccess() {
        if (fixed != null) {
            return;
        }
        if (pageLocalRecovery && current < target) {
            current = target;
            successes = 0;
            return;
        }
        successes++;
        if (successes >= 4) {
            target = Math.min(maximum, target + 128);
            current = target;
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
        if (!pageLocalRecovery) {
            target = current;
        }
        return changed;
    }
}
