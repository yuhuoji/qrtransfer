package org.wowtools.qrtransfer.cli;

import org.junit.jupiter.api.Test;
import org.wowtools.qrtransfer.common.transfer.V2Frame;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BenchmarkReportTest {
    @Test
    void reportsPeakAndRecommendsFastForStableSamples() {
        TransferMetrics metrics = new TransferMetrics();
        metrics.start(1);
        List<BenchmarkReport.Sample> samples = new ArrayList<>();
        for (int second = 1; second <= 20; second++) {
            metrics.acceptedPage(1600);
            samples.add(new BenchmarkReport.Sample(
                    second * 1_000_000_000L, 1600, 100));
        }
        metrics.finish(20_000_000_001L);

        BenchmarkReport report = BenchmarkReport.create(metrics,
                V2Frame.benchmarkHeader(1L, 640, 384, 1800, 2100), samples);

        assertEquals("fast", report.recommendedProfile());
        assertEquals(1600, report.stablePageSize());
        assertEquals(100, report.stableCycleMillis());
        assertTrue(report.averageBytesPerSecond() > 1500);
        assertTrue(report.peakBytesPerSecond() > 0);
        assertTrue(report.format().contains("10 秒窗口峰值"));
        assertTrue(report.format().contains("--qr-size 640"));
    }

    @Test
    void recommendsSafeWhenRetriesAreFrequent() {
        TransferMetrics metrics = new TransferMetrics();
        metrics.start(1);
        List<BenchmarkReport.Sample> samples = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            metrics.acceptedPage(900);
            metrics.retry();
            samples.add(new BenchmarkReport.Sample(i * 500_000_000L, 900, 450));
        }
        metrics.finish(5_000_000_001L);

        BenchmarkReport report = BenchmarkReport.create(metrics,
                V2Frame.benchmarkHeader(1L, 640, 384, 1800, 2100), samples);

        assertEquals("safe", report.recommendedProfile());
        assertTrue(report.format().contains("重传率 50.0%"));
    }

    @Test
    void ignoresOldFastSamplesWhenFinalMinuteHasNoSuccess() {
        TransferMetrics metrics = new TransferMetrics();
        metrics.start(1);
        List<BenchmarkReport.Sample> samples = new ArrayList<>();
        for (int second = 1; second <= 10; second++) {
            metrics.acceptedPage(1800);
            samples.add(new BenchmarkReport.Sample(
                    second * 1_000_000_000L, 1800, 90));
        }
        metrics.finish(120_000_000_001L);

        BenchmarkReport report = BenchmarkReport.create(metrics,
                V2Frame.benchmarkHeader(1L, 640, 384, 1800, 2150), samples);

        assertEquals("safe", report.recommendedProfile());
        assertEquals(384, report.stablePageSize());
    }
}
