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
}
