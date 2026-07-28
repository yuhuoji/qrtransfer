package org.wowtools.qrtransfer.cli;

import org.wowtools.qrtransfer.common.transfer.Utf8Text;
import org.wowtools.qrtransfer.common.transfer.V2Frame;
import org.wowtools.qrtransfer.common.util.Md5Util;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

final class AdaptiveReceiver {
    interface FrameReader {
        byte[] read();
    }

    interface Controller {
        void acknowledge(int pageNumber);

        void reject(int pageNumber);
    }

    interface Listener {
        void log(String message);
    }

    static final class Result {
        final long bytesWritten;
        final String md5;
        final TransferMetrics metrics;

        Result(long bytesWritten, String md5, TransferMetrics metrics) {
            this.bytesWritten = bytesWritten;
            this.md5 = md5;
            this.metrics = metrics;
        }
    }

    private AdaptiveReceiver() {
    }

    static Result receive(FrameReader reader, Controller controller, Path output, boolean overwrite,
                          boolean requireText, long initialDelay, long minDelay, long maxDelay,
                          long frameTimeout, int downshiftAfterTimeouts, Listener listener)
            throws IOException, InterruptedException {
        if (downshiftAfterTimeouts < 1) {
            throw new IllegalArgumentException("连续超时降密阈值必须大于 0");
        }
        TransferMetrics metrics = new TransferMetrics();
        try {
            return receiveTracked(reader, controller, output, overwrite, requireText,
                    initialDelay, minDelay, maxDelay, frameTimeout,
                    downshiftAfterTimeouts, listener, metrics);
        } catch (IOException | InterruptedException | RuntimeException e) {
            listener.log(metrics.failureSummary(e.getMessage()));
            throw e;
        }
    }

    private static Result receiveTracked(FrameReader reader, Controller controller, Path output,
                                         boolean overwrite, boolean requireText, long initialDelay,
                                         long minDelay, long maxDelay, long frameTimeout,
                                         int downshiftAfterTimeouts,
                                         Listener listener, TransferMetrics metrics)
            throws IOException, InterruptedException {
        validateOutput(output, overwrite);
        V2Frame header = waitForHeader(reader, listener);
        metrics.start();
        if (requireText && !header.isText()) {
            listener.log("接收端启用了 --text；完成后将严格验证 UTF-8");
        }
        Path parent = output.toAbsolutePath().getParent();
        Path temp = Files.createTempFile(parent, output.getFileName().toString() + ".", ".part");
        Thread cleanupHook = new Thread(() -> deleteQuietly(temp), "qrtransfer-v2-temp-cleanup");
        Runtime.getRuntime().addShutdownHook(cleanupHook);

        long written = 0;
        long expectedOffset = 0;
        int expectedPage = 0;
        AdaptiveDelay delay = new AdaptiveDelay(initialDelay, minDelay, maxDelay);
        int consecutiveTimeouts = 0;
        long requestStarted = System.nanoTime();
        controller.acknowledge(0); // request page 0 from the header

        try (OutputStream stream = Files.newOutputStream(temp)) {
            while (true) {
                if (delay.current() > 0) {
                    Thread.sleep(delay.current());
                }
                byte[] encoded = reader.read();
                long elapsed = elapsedMillis(requestStarted);
                V2Frame frame = decodeOrNull(encoded);
                if (frame == null) {
                    metrics.recognitionFailure();
                    if (elapsed >= frameTimeout) {
                        metrics.timeout();
                        requestStarted = System.nanoTime();
                        consecutiveTimeouts++;
                        if (consecutiveTimeouts >= downshiftAfterTimeouts) {
                            controller.reject(expectedPage);
                            delay.onFailure();
                            metrics.retry();
                            consecutiveTimeouts = 0;
                            listener.log("页面 " + expectedPage + " 连续超时 "
                                    + downshiftAfterTimeouts + " 次，已请求当前页降密重传");
                        } else {
                            listener.log("页面 " + expectedPage + " 超时（"
                                    + consecutiveTimeouts + "/" + downshiftAfterTimeouts
                                    + "），继续扫描，暂不降密");
                        }
                    }
                    continue;
                }
                if (frame.isHeader()) {
                    if (frame.getSessionId() != header.getSessionId()) {
                        listener.log("忽略其他传输会话的文件头");
                        continue;
                    }
                    if (elapsed >= frameTimeout) {
                        controller.acknowledge(0);
                        delay.onFailure();
                        consecutiveTimeouts = 0;
                        requestStarted = System.nanoTime();
                        listener.log("仍为文件头，重新请求第 0 页");
                    }
                    continue;
                }
                if (frame.getSessionId() != header.getSessionId()) {
                    listener.log("忽略其他传输会话的数据页");
                    continue;
                }
                int page = frame.getPageNumber();
                if (page == expectedPage - 1) {
                    controller.acknowledge(page);
                    metrics.duplicatePage();
                    consecutiveTimeouts = 0;
                    requestStarted = System.nanoTime();
                    listener.log("重复页 " + page + "，已重新确认，不触发降密");
                    continue;
                }
                if (page != expectedPage || frame.getOffset() != expectedOffset) {
                    metrics.sequenceError();
                    listener.log("页序不一致：期待页 " + expectedPage + "、偏移 " + expectedOffset
                            + "，实际页 " + page + "、偏移 " + frame.getOffset()
                            + "；继续扫描且不触发降密");
                    if (elapsed >= frameTimeout) {
                        throw new IOException("页序持续不一致，停止接收以避免输出错误文件");
                    }
                    continue;
                }

                byte[] payload = frame.getPayload();
                consecutiveTimeouts = 0;
                stream.write(payload);
                written += payload.length;
                expectedOffset += payload.length;
                metrics.acceptedPage(payload.length);
                listener.log("收到第 " + page + " 页，" + payload.length + " 字节，累计 " + written);
                delay.onSuccess(elapsed);
                if (frame.isEnd()) {
                    break;
                }
                controller.acknowledge(page);
                expectedPage++;
                requestStarted = System.nanoTime();
            }
        } catch (IOException | InterruptedException | RuntimeException e) {
            deleteQuietly(temp);
            removeCleanupHook(cleanupHook);
            throw e;
        }

        if (written != header.getFileSize()) {
            deleteQuietly(temp);
            removeCleanupHook(cleanupHook);
            throw new IOException("文件大小校验失败：期望 " + header.getFileSize() + "，实际 " + written);
        }
        String md5 = Md5Util.getFileMD5(temp.toFile());
        if (!header.getMd5Hex().equals(md5)) {
            deleteQuietly(temp);
            removeCleanupHook(cleanupHook);
            throw new IOException("MD5 校验失败：源 " + header.getMd5Hex() + "，接收 " + md5);
        }
        if (requireText || header.isText()) {
            try {
                Utf8Text.validate(temp);
            } catch (IOException e) {
                deleteQuietly(temp);
                removeCleanupHook(cleanupHook);
                throw e;
            }
        }
        move(temp, output, overwrite);
        removeCleanupHook(cleanupHook);
        controller.acknowledge(expectedPage);
        metrics.finish();
        return new Result(written, md5, metrics);
    }

    private static V2Frame waitForHeader(FrameReader reader, Listener listener)
            throws InterruptedException {
        while (true) {
            V2Frame frame = decodeOrNull(reader.read());
            if (frame != null && frame.isHeader()) {
                listener.log("读取 V2 文件头，大小 " + frame.getFileSize() + " 字节，MD5 "
                        + frame.getMd5Hex());
                return frame;
            }
            listener.log("未检测到 V2 文件头，等待");
            Thread.sleep(300);
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

    private static long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }

    private static void validateOutput(Path output, boolean overwrite) {
        if (Files.exists(output) && !overwrite) {
            throw new IllegalArgumentException("输出文件已存在；如需替换，请使用 --overwrite: " + output);
        }
        Path parent = output.toAbsolutePath().getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw new IllegalArgumentException("输出目录不存在: " + parent);
        }
    }

    private static void move(Path temp, Path output, boolean overwrite) throws IOException {
        try {
            if (overwrite) {
                Files.move(temp, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(temp, output, StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (AtomicMoveNotSupportedException e) {
            if (overwrite) {
                Files.move(temp, output, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(temp, output);
            }
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // Best effort during shutdown or failure.
        }
    }

    private static void removeCleanupHook(Thread hook) {
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException ignored) {
            // Shutdown is already in progress.
        }
    }
}
