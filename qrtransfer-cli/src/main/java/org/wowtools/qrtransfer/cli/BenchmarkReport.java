package org.wowtools.qrtransfer.cli;

import org.wowtools.qrtransfer.common.transfer.V2Frame;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

final class BenchmarkReport {
    static final class Sample {
        final long receivedNanos;
        final int payloadBytes;
        final long cycleMillis;

        Sample(long receivedNanos, int payloadBytes, long cycleMillis) {
            this.receivedNanos = receivedNanos;
            this.payloadBytes = payloadBytes;
            this.cycleMillis = cycleMillis;
        }
    }

    private final TransferMetrics metrics;
    private final V2Frame header;
    private final double averageBytesPerSecond;
    private final double peakBytesPerSecond;
    private final double retryRate;
    private final int stablePageSize;
    private final long stableCycleMillis;
    private final String recommendedProfile;
    private final int recommendedInitialPageSize;
    private final int recommendedMaxPageSize;
    private final long recommendedInitialDelay;
    private final long recommendedMinDelay;
    private final long recommendedMaxDelay;
    private final long recommendedTimeout;

    private BenchmarkReport(TransferMetrics metrics, V2Frame header, List<Sample> samples) {
        this.metrics = metrics;
        this.header = header;
        double seconds = metrics.elapsedNanos() / 1_000_000_000.0;
        this.averageBytesPerSecond = seconds > 0 ? metrics.bytes() / seconds : 0;
        this.peakBytesPerSecond = peakTenSecondRate(samples, averageBytesPerSecond);
        this.retryRate = metrics.pages() + metrics.retries() == 0 ? 0
                : (double) metrics.retries() / (metrics.pages() + metrics.retries());

        List<Sample> stable = stableSamples(samples, metrics.finishedNanos());
        this.stablePageSize = percentilePayload(stable, 0.25, header.getMinPageSize());
        this.stableCycleMillis = percentileLong(stable, 0.50, 800);
        int medianPage = percentilePayload(stable, 0.50, header.getMinPageSize());
        int upperPage = percentilePayload(stable, 0.75, header.getMinPageSize());
        this.recommendedInitialPageSize = clamp(roundDown(medianPage, 128),
                header.getMinPageSize(), header.getMaxPageSize());
        this.recommendedMaxPageSize = clamp(
                Math.max(recommendedInitialPageSize, roundDown(upperPage, 128) + 128),
                recommendedInitialPageSize, header.getMaxPageSize());

        long p50Cycle = percentileLong(stable, 0.50, 200);
        long p95Cycle = percentileLong(stable, 0.95, 500);
        this.recommendedInitialDelay = clamp(Math.round(p50Cycle * 0.75), 20, 800);
        this.recommendedMinDelay = Math.max(20, recommendedInitialDelay / 2);
        this.recommendedMaxDelay = clamp(
                Math.max(400, recommendedInitialDelay * 4), recommendedInitialDelay, 1200);
        this.recommendedTimeout = clamp(Math.max(900, p95Cycle * 3), 900, 3000);
        this.recommendedProfile = chooseProfile(stablePageSize, stableCycleMillis, retryRate);
    }

    static BenchmarkReport create(TransferMetrics metrics, V2Frame header, List<Sample> samples) {
        if (!header.isBenchmarkHeader()) {
            throw new IllegalArgumentException("测速报告需要 V2 测速头");
        }
        return new BenchmarkReport(metrics, header, samples);
    }

    String recommendedProfile() {
        return recommendedProfile;
    }

    double averageBytesPerSecond() {
        return averageBytesPerSecond;
    }

    double peakBytesPerSecond() {
        return peakBytesPerSecond;
    }

    int stablePageSize() {
        return stablePageSize;
    }

    long stableCycleMillis() {
        return stableCycleMillis;
    }

    String format() {
        StringBuilder out = new StringBuilder();
        out.append("测速完成：耗时 ").append(formatDuration(metrics.elapsedNanos()))
                .append("，有效数据 ").append(formatBytes(metrics.bytes()))
                .append(System.lineSeparator());
        out.append("持续平均速度 ").append(formatRate(averageBytesPerSecond))
                .append("，10 秒窗口峰值 ").append(formatRate(peakBytesPerSecond))
                .append(System.lineSeparator());
        out.append("成功页 ").append(metrics.pages())
                .append("，识别失败 ").append(metrics.recognitionFailures())
                .append("，重复页 ").append(metrics.duplicatePages())
                .append("，页序错误 ").append(metrics.sequenceErrors())
                .append("，超时 ").append(metrics.timeouts())
                .append("，重传 ").append(metrics.retries())
                .append("，重传率 ").append(formatPercent(retryRate))
                .append(System.lineSeparator());
        out.append("最后 60 秒稳定值：约 ").append(stablePageSize)
                .append(" B/页，页面周期 ").append(stableCycleMillis).append("ms")
                .append(System.lineSeparator());
        out.append("建议 profile：").append(recommendedProfile).append(System.lineSeparator());
        out.append("建议发送参数：--qr-size ").append(header.getQrSize())
                .append(" --min-page-size ").append(header.getMinPageSize())
                .append(" --initial-page-size ").append(recommendedInitialPageSize)
                .append(" --max-page-size ").append(recommendedMaxPageSize)
                .append(System.lineSeparator());
        out.append("建议接收参数：--initial-delay ").append(recommendedInitialDelay)
                .append(" --min-delay ").append(recommendedMinDelay)
                .append(" --max-delay ").append(recommendedMaxDelay)
                .append(" --frame-timeout ").append(recommendedTimeout);
        return out.toString();
    }

    private static List<Sample> stableSamples(List<Sample> samples, long finishedNanos) {
        if (samples.isEmpty()) {
            return List.of();
        }
        long cutoff = finishedNanos - 60_000_000_000L;
        List<Sample> result = new ArrayList<>();
        for (Sample sample : samples) {
            if (sample.receivedNanos >= cutoff) {
                result.add(sample);
            }
        }
        return result;
    }

    private static double peakTenSecondRate(List<Sample> samples, double fallback) {
        if (samples.size() < 2) {
            return fallback;
        }
        long first = samples.get(0).receivedNanos;
        long windowNanos = 10_000_000_000L;
        long bytes = 0;
        int left = 0;
        double peak = 0;
        for (int right = 0; right < samples.size(); right++) {
            Sample current = samples.get(right);
            bytes += current.payloadBytes;
            while (left < right
                    && current.receivedNanos - samples.get(left).receivedNanos >= windowNanos) {
                bytes -= samples.get(left++).payloadBytes;
            }
            if (current.receivedNanos - first >= windowNanos) {
                peak = Math.max(peak, bytes / 10.0);
            }
        }
        return peak > 0 ? peak : fallback;
    }

    private static int percentilePayload(List<Sample> samples, double percentile, int fallback) {
        if (samples.isEmpty()) {
            return fallback;
        }
        List<Integer> values = new ArrayList<>(samples.size());
        for (Sample sample : samples) {
            values.add(sample.payloadBytes);
        }
        values.sort(Comparator.naturalOrder());
        return values.get(percentileIndex(values.size(), percentile));
    }

    private static long percentileLong(List<Sample> samples, double percentile, long fallback) {
        if (samples.isEmpty()) {
            return fallback;
        }
        List<Long> values = new ArrayList<>(samples.size());
        for (Sample sample : samples) {
            values.add(sample.cycleMillis);
        }
        values.sort(Comparator.naturalOrder());
        return values.get(percentileIndex(values.size(), percentile));
    }

    private static int percentileIndex(int size, double percentile) {
        return Math.max(0, Math.min(size - 1, (int) Math.ceil(size * percentile) - 1));
    }

    private static String chooseProfile(int pageSize, long cycleMillis, double retryRate) {
        if (pageSize >= 1536 && cycleMillis <= 150 && retryRate <= 0.05) {
            return "fast";
        }
        if (pageSize >= 1000 && cycleMillis <= 300 && retryRate <= 0.12) {
            return "balanced";
        }
        return "safe";
    }

    private static int roundDown(int value, int step) {
        return Math.max(step, value / step * step);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static long clamp(long value, long minimum, long maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static String formatDuration(long nanos) {
        long seconds = Math.round(nanos / 1_000_000_000.0);
        return (seconds / 60) + "m" + (seconds % 60) + "s";
    }

    private static String formatBytes(long value) {
        if (value < 1024L * 1024) {
            return String.format(Locale.ROOT, "%.2f KiB", value / 1024.0);
        }
        return String.format(Locale.ROOT, "%.2f MiB", value / (1024.0 * 1024.0));
    }

    private static String formatRate(double value) {
        if (value < 1024) {
            return String.format(Locale.ROOT, "%.2f B/s", value);
        }
        return String.format(Locale.ROOT, "%.2f KiB/s", value / 1024.0);
    }

    private static String formatPercent(double value) {
        return String.format(Locale.ROOT, "%.1f%%", value * 100);
    }
}
