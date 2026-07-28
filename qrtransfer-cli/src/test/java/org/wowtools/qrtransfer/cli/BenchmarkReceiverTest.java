package org.wowtools.qrtransfer.cli;

import org.junit.jupiter.api.Test;
import org.wowtools.qrtransfer.common.transfer.V2Frame;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertTrue;

class BenchmarkReceiverTest {
    @Test
    void runsWithVirtualClockStopsSenderAndWritesNoFile() throws Exception {
        FakeClock clock = new FakeClock();
        AtomicBoolean headerSent = new AtomicBoolean();
        AtomicInteger prelude = new AtomicInteger();
        AtomicInteger page = new AtomicInteger();
        AtomicLong offset = new AtomicLong();
        AtomicBoolean stopped = new AtomicBoolean();
        long session = 91L;

        BenchmarkReport report = BenchmarkReceiver.run(
                () -> {
                    if (!headerSent.getAndSet(true)) {
                        return V2Frame.benchmarkHeader(session, 640, 384, 1800, 2100).encode();
                    }
                    int preludeStep = prelude.getAndIncrement();
                    if (preludeStep == 0) {
                        return V2Frame.data(
                                session + 1, 0, 0, new byte[512], false).encode();
                    }
                    if (preludeStep == 1) {
                        byte[] corrupted = V2Frame.data(
                                session, 0, 0, new byte[512], false).encode();
                        corrupted[corrupted.length / 2] ^= 0x01;
                        return corrupted;
                    }
                    int number = page.getAndIncrement();
                    long currentOffset = offset.getAndAdd(512);
                    return V2Frame.data(
                            session, number, currentOffset, new byte[512], false).encode();
                },
                new BenchmarkReceiver.Controller() {
                    @Override
                    public void acknowledge(int pageNumber) {
                    }

                    @Override
                    public void reject(int pageNumber) {
                    }

                    @Override
                    public void stop() {
                        stopped.set(true);
                    }
                },
                1, 100, 50, 200, 500, 3, ignored -> { }, clock, clock::sleep);

        assertTrue(stopped.get());
        assertTrue(report.averageBytesPerSecond() > 0);
        assertTrue(report.format().contains("测速完成"));
        assertTrue(report.format().contains("识别失败 1"));
    }

    private static final class FakeClock implements BenchmarkReceiver.Clock {
        private long now = 1;

        @Override
        public long nanoTime() {
            return now;
        }

        private void sleep(long millis) {
            now += millis * 1_000_000L;
        }
    }
}
