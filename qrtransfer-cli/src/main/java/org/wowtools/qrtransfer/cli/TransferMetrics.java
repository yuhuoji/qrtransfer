package org.wowtools.qrtransfer.cli;

import java.util.Locale;

final class TransferMetrics {
    private long startedNanos;
    private long finishedNanos;
    private long bytes;
    private int pages;
    private int recognitionFailures;
    private int duplicatePages;
    private int timeouts;
    private int retries;
    private int densityDrops;
    private int sequenceErrors;

    void start() {
        start(System.nanoTime());
    }

    void start(long nowNanos) {
        if (startedNanos == 0) {
            startedNanos = nowNanos;
        }
    }

    void reset() {
        startedNanos = 0;
        finishedNanos = 0;
        bytes = 0;
        pages = 0;
        recognitionFailures = 0;
        duplicatePages = 0;
        timeouts = 0;
        retries = 0;
        densityDrops = 0;
        sequenceErrors = 0;
    }

    void finish() {
        finish(System.nanoTime());
    }

    void finish(long nowNanos) {
        if (startedNanos != 0 && finishedNanos == 0) {
            finishedNanos = nowNanos;
        }
    }

    void acceptedPage(int payloadBytes) {
        pages++;
        bytes += payloadBytes;
    }

    void recognitionFailure() {
        recognitionFailures++;
    }

    void duplicatePage() {
        duplicatePages++;
    }

    void timeout() {
        timeouts++;
    }

    void retry() {
        retries++;
    }

    void densityDrop() {
        densityDrops++;
    }

    void sequenceError() {
        sequenceErrors++;
    }

    int pages() {
        return pages;
    }

    long bytes() {
        return bytes;
    }

    long elapsedNanos() {
        if (startedNanos == 0) {
            return 0;
        }
        long end = finishedNanos == 0 ? System.nanoTime() : finishedNanos;
        return Math.max(0, end - startedNanos);
    }

    long finishedNanos() {
        return finishedNanos == 0 ? System.nanoTime() : finishedNanos;
    }

    String progressSummary(long totalBytes) {
        return progressSummary(totalBytes, System.nanoTime());
    }

    String progressSummary(long totalBytes, long nowNanos) {
        long total = Math.max(0, totalBytes);
        long sent = Math.min(bytes, total);
        long remaining = total - sent;
        if (startedNanos == 0) {
            return "传输进度：总量 " + formatBytes(total) + "；等待接收端确认";
        }

        long elapsedNanos = Math.max(0, (finishedNanos == 0 ? nowNanos : finishedNanos) - startedNanos);
        double seconds = elapsedNanos / 1_000_000_000.0;
        double bytesPerSecond = seconds > 0 ? sent / seconds : 0;
        String eta = pages < 3 || bytesPerSecond <= 0
                ? "计算中（确认 3 页后显示）"
                : formatDuration(remaining / bytesPerSecond);
        double percent = total == 0 ? 100 : sent * 100.0 / total;
        return "传输进度：已确认 " + formatBytes(sent) + " / " + formatBytes(total)
                + " (" + String.format(Locale.ROOT, "%.1f%%", percent) + ")，剩余 "
                + formatBytes(remaining) + System.lineSeparator()
                + "已用 " + formatDuration(seconds) + "，平均 " + formatRate(bytesPerSecond)
                + "，预计剩余 " + eta;
    }

    int recognitionFailures() {
        return recognitionFailures;
    }

    int duplicatePages() {
        return duplicatePages;
    }

    int timeouts() {
        return timeouts;
    }

    int retries() {
        return retries;
    }

    int densityDrops() {
        return densityDrops;
    }

    int sequenceErrors() {
        return sequenceErrors;
    }

    String successSummary() {
        finish();
        return "传输统计：成功；" + commonSummary()
                + "；识别失败 " + recognitionFailures
                + " 次，重复页 " + duplicatePages
                + " 次，页序错误 " + sequenceErrors
                + " 次，超时 " + timeouts
                + " 次，重传请求 " + retries
                + " 次，降密 " + densityDrops + " 次";
    }

    String failureSummary(String reason) {
        finish();
        return "传输统计：失败；" + commonSummary()
                + "；识别失败 " + recognitionFailures
                + " 次，重复页 " + duplicatePages
                + " 次，页序错误 " + sequenceErrors
                + " 次，超时 " + timeouts
                + " 次，重传请求 " + retries
                + " 次，降密 " + densityDrops
                + " 次；原因：" + reason;
    }

    private String commonSummary() {
        long elapsedNanos = elapsedNanos();
        double seconds = elapsedNanos / 1_000_000_000.0;
        double bytesPerSecond = seconds > 0 ? bytes / seconds : 0;
        return "耗时 " + formatDuration(seconds)
                + "，有效数据 " + formatBytes(bytes)
                + "，平均速度 " + formatRate(bytesPerSecond)
                + "，成功页 " + pages;
    }

    private static String formatDuration(double seconds) {
        if (seconds < 60) {
            return String.format(Locale.ROOT, "%.2fs", seconds);
        }
        long wholeSeconds = Math.round(seconds);
        return (wholeSeconds / 60) + "m" + (wholeSeconds % 60) + "s";
    }

    private static String formatBytes(long value) {
        if (value < 1024) {
            return value + " B";
        }
        if (value < 1024L * 1024) {
            return String.format(Locale.ROOT, "%.2f KiB", value / 1024.0);
        }
        return String.format(Locale.ROOT, "%.2f MiB", value / (1024.0 * 1024.0));
    }

    private static String formatRate(double bytesPerSecond) {
        if (bytesPerSecond < 1024) {
            return String.format(Locale.ROOT, "%.2f B/s", bytesPerSecond);
        }
        if (bytesPerSecond < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.2f KiB/s", bytesPerSecond / 1024.0);
        }
        return String.format(Locale.ROOT, "%.2f MiB/s", bytesPerSecond / (1024.0 * 1024.0));
    }
}
