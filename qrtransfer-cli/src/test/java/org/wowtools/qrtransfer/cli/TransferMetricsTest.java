package org.wowtools.qrtransfer.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TransferMetricsTest {
    @Test
    void reportsSuccessfulTransferCounters() {
        TransferMetrics metrics = new TransferMetrics();
        metrics.start();
        metrics.acceptedPage(1024);
        metrics.acceptedPage(512);
        metrics.recognitionFailure();
        metrics.duplicatePage();
        metrics.sequenceError();
        metrics.timeout();
        metrics.retry();
        metrics.densityDrop();

        String summary = metrics.successSummary();

        assertTrue(summary.contains("传输统计：成功"));
        assertTrue(summary.contains("有效数据 1.50 KiB"));
        assertTrue(summary.contains("成功页 2"));
        assertTrue(summary.contains("识别失败 1 次"));
        assertTrue(summary.contains("重复页 1 次"));
        assertTrue(summary.contains("页序错误 1 次"));
        assertTrue(summary.contains("超时 1 次"));
        assertTrue(summary.contains("重传请求 1 次"));
        assertTrue(summary.contains("降密 1 次"));
    }

    @Test
    void reportsFailureReason() {
        TransferMetrics metrics = new TransferMetrics();
        metrics.start();

        String summary = metrics.failureSummary("MD5 校验失败");

        assertTrue(summary.contains("传输统计：失败"));
        assertTrue(summary.contains("原因：MD5 校验失败"));
    }

    @Test
    void reportsConfirmedSenderProgressAndEta() {
        TransferMetrics metrics = new TransferMetrics();
        metrics.start(1_000_000_000L);
        metrics.acceptedPage(1_000);
        metrics.acceptedPage(1_000);
        metrics.acceptedPage(1_000);

        String progress = metrics.progressSummary(6_000, 4_000_000_000L);

        assertTrue(progress.contains("已确认 2.93 KiB / 5.86 KiB (50.0%)"));
        assertTrue(progress.contains("剩余 2.93 KiB"));
        assertTrue(progress.contains("已用 3.00s"));
        assertTrue(progress.contains("平均 1000.00 B/s"));
        assertTrue(progress.contains("预计剩余 3.00s"));
    }
}
