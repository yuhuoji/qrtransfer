package org.wowtools.qrtransfer.cli;

import org.wowtools.qrtransfer.common.transfer.V2Frame;

import java.util.ArrayList;
import java.util.List;

final class BenchmarkReceiver {
    interface FrameReader {
        byte[] read();
    }

    interface Controller {
        void acknowledge(int pageNumber);

        void reject(int pageNumber);

        void stop();
    }

    interface Listener {
        void log(String message);
    }

    interface Clock {
        long nanoTime();
    }

    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private BenchmarkReceiver() {
    }

    static BenchmarkReport run(FrameReader reader, Controller controller, long durationSeconds,
                               long initialDelay, long minDelay, long maxDelay, long frameTimeout,
                               Listener listener) throws InterruptedException {
        return run(reader, controller, durationSeconds, initialDelay, minDelay, maxDelay,
                frameTimeout, listener, System::nanoTime, Thread::sleep);
    }

    static BenchmarkReport run(FrameReader reader, Controller controller, long durationSeconds,
                               long initialDelay, long minDelay, long maxDelay, long frameTimeout,
                               Listener listener, Clock clock, Sleeper sleeper)
            throws InterruptedException {
        V2Frame header = waitForHeader(reader, listener, sleeper);
        TransferMetrics metrics = new TransferMetrics();
        List<BenchmarkReport.Sample> samples = new ArrayList<>();
        AdaptiveDelay delay = new AdaptiveDelay(initialDelay, minDelay, maxDelay);
        long started = clock.nanoTime();
        long deadline = started + durationSeconds * 1_000_000_000L;
        long nextProgress = started + 5_000_000_000L;
        long requestStarted = started;
        long expectedOffset = 0;
        int expectedPage = 0;
        metrics.start(started);
        controller.acknowledge(0);
        listener.log("测速开始，持续 " + durationSeconds + " 秒；不写入任何文件");

        try {
            while (clock.nanoTime() < deadline) {
                if (delay.current() > 0) {
                    sleeper.sleep(delay.current());
                }
                long now = clock.nanoTime();
                if (now >= deadline) {
                    break;
                }
                V2Frame frame = decodeOrNull(reader.read());
                long elapsed = elapsedMillis(requestStarted, now);
                if (frame == null) {
                    metrics.recognitionFailure();
                    if (elapsed >= frameTimeout) {
                        controller.reject(expectedPage);
                        delay.onFailure();
                        metrics.timeout();
                        metrics.retry();
                        requestStarted = now;
                        listener.log("页面 " + expectedPage + " 超时，测速中请求降密");
                    }
                    continue;
                }
                if (frame.isBenchmarkHeader()) {
                    if (frame.getSessionId() == header.getSessionId() && elapsed >= frameTimeout) {
                        controller.acknowledge(0);
                        delay.onFailure();
                        requestStarted = now;
                    }
                    continue;
                }
                if (!frame.isData() || frame.getSessionId() != header.getSessionId()) {
                    continue;
                }
                int page = frame.getPageNumber();
                if (page == expectedPage - 1) {
                    controller.acknowledge(page);
                    metrics.duplicatePage();
                    if (elapsed >= frameTimeout) {
                        controller.reject(expectedPage);
                        delay.onFailure();
                        metrics.timeout();
                        metrics.retry();
                        requestStarted = now;
                        listener.log("重复页 " + page + " 持续超时，测速中请求页面 "
                                + expectedPage + " 降密重传");
                    }
                    continue;
                }
                if (page != expectedPage || frame.getOffset() != expectedOffset) {
                    controller.reject(expectedPage);
                    delay.onFailure();
                    metrics.sequenceError();
                    metrics.retry();
                    requestStarted = now;
                    continue;
                }

                byte[] payload = frame.getPayload();
                metrics.acceptedPage(payload.length);
                samples.add(new BenchmarkReport.Sample(now, payload.length, elapsed));
                expectedOffset += payload.length;
                delay.onSuccess(elapsed);
                controller.acknowledge(page);
                expectedPage++;
                requestStarted = now;
                if (now >= nextProgress) {
                    long remaining = Math.max(0, (deadline - now) / 1_000_000_000L);
                    listener.log("测速进度：成功页 " + metrics.pages() + "，有效数据 "
                            + metrics.bytes() + " 字节，剩余约 " + remaining
                            + " 秒，当前等待 " + delay.current() + "ms");
                    nextProgress = now + 5_000_000_000L;
                }
            }
        } catch (InterruptedException | RuntimeException e) {
            controller.stop();
            metrics.finish(clock.nanoTime());
            listener.log(metrics.failureSummary(e.getMessage()));
            throw e;
        }

        controller.stop();
        metrics.finish(clock.nanoTime());
        return BenchmarkReport.create(metrics, header, samples);
    }

    private static V2Frame waitForHeader(FrameReader reader, Listener listener, Sleeper sleeper)
            throws InterruptedException {
        while (true) {
            V2Frame frame = decodeOrNull(reader.read());
            if (frame != null && frame.isBenchmarkHeader()) {
                listener.log("读取测速头：二维码 " + frame.getQrSize() + "px，页面范围 "
                        + frame.getMinPageSize() + "–" + frame.getMaxPageSize() + " 字节");
                return frame;
            }
            if (frame != null && frame.isHeader()) {
                listener.log("检测到正式文件头；测速模式不会接收或写入该文件");
            } else {
                listener.log("未检测到测速二维码，等待");
            }
            sleeper.sleep(300);
        }
    }

    private static V2Frame decodeOrNull(byte[] encoded) {
        if (encoded == null) {
            return null;
        }
        try {
            return V2Frame.decode(encoded);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static long elapsedMillis(long started, long now) {
        return Math.max(0, (now - started) / 1_000_000L);
    }
}
