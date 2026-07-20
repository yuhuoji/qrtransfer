package org.wowtools.qrtransfer.common.transfer;

import org.wowtools.qrtransfer.common.pojo.FileHead;
import org.wowtools.qrtransfer.common.protobuf.QrPageProto;
import org.wowtools.qrtransfer.common.util.Constant;
import org.wowtools.qrtransfer.common.util.Md5Util;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Screen-independent page receiver used by command-line integrations. */
public final class TransferReceiver {
    private TransferReceiver() {
    }

    public interface QrReader {
        byte[] read();
    }

    public interface PageController {
        void next();

        void previous();
    }

    public interface Listener {
        void log(String message);
    }

    public interface PayloadValidator {
        void validate(Path file) throws IOException;
    }

    public static final class Result {
        private final boolean verified;
        private final long bytesWritten;
        private final String sourceMd5;
        private final String targetMd5;

        private Result(boolean verified, long bytesWritten, String sourceMd5, String targetMd5) {
            this.verified = verified;
            this.bytesWritten = bytesWritten;
            this.sourceMd5 = sourceMd5;
            this.targetMd5 = targetMd5;
        }

        public boolean isVerified() {
            return verified;
        }

        public long getBytesWritten() {
            return bytesWritten;
        }

        public String getSourceMd5() {
            return sourceMd5;
        }

        public String getTargetMd5() {
            return targetMd5;
        }
    }

    public static Result receive(QrReader reader, PageController controller, Path output, boolean overwrite,
                                 long pageDelayMs, PayloadValidator validator, Listener listener)
            throws IOException, InterruptedException {
        if (pageDelayMs < 0) {
            throw new IllegalArgumentException("pageDelay 不能小于 0");
        }
        if (Files.exists(output) && !overwrite) {
            throw new IllegalArgumentException("输出文件已存在；如需替换，请使用 --overwrite: " + output);
        }
        Path parent = output.toAbsolutePath().getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw new IllegalArgumentException("输出目录不存在: " + parent);
        }

        FileHead header = readHeader(reader, listener);
        byte[] headerBytes = header.toByte();
        Path temp = Files.createTempFile(parent, output.getFileName().toString() + ".", ".part");
        Thread cleanupHook = new Thread(() -> deleteQuietly(temp), "qrtransfer-temp-cleanup");
        Runtime.getRuntime().addShutdownHook(cleanupHook);
        long written = 0;
        int previousPage = -1;
        boolean next = true;
        try (OutputStream stream = Files.newOutputStream(temp)) {
            while (true) {
                if (next) {
                    controller.next();
                } else {
                    controller.previous();
                }
                if (pageDelayMs > 0) {
                    Thread.sleep(pageDelayMs);
                }
                byte[] encoded = reader.read();
                if (encoded == null) {
                    listener.log("未检测到二维码，重试");
                    continue;
                }
                if (previousPage == -1 && sameBytes(headerBytes, encoded)) {
                    listener.log("仍为文件头，继续翻页");
                    next = true;
                    continue;
                }
                QrPageProto.QrPagePb page;
                try {
                    page = QrPageProto.QrPagePb.parseFrom(encoded);
                } catch (Exception e) {
                    listener.log("二维码页解析失败，重试");
                    continue;
                }
                int pageNumber = page.getPageNum();
                if (pageNumber == previousPage) {
                    listener.log("重复页 " + pageNumber + "，继续翻页");
                    next = true;
                    continue;
                }
                if (pageNumber != previousPage + 1) {
                    listener.log("页面跳转：当前 " + previousPage + "，读取到 " + pageNumber);
                    next = pageNumber < previousPage + 1;
                    continue;
                }
                byte[] bytes = page.getBytes().toByteArray();
                stream.write(bytes);
                written += bytes.length;
                previousPage = pageNumber;
                listener.log("收到第 " + pageNumber + " 页，" + bytes.length + " 字节");
                if (Constant.Flag_End.equals(page.getMessage())) {
                    break;
                }
                next = true;
            }
        } catch (InterruptedException | RuntimeException | IOException e) {
            Files.deleteIfExists(temp);
            removeCleanupHook(cleanupHook);
            throw e;
        }

        String targetMd5 = Md5Util.getFileMD5(temp.toFile());
        if (!header.getMd5().equals(targetMd5)) {
            Files.deleteIfExists(temp);
            removeCleanupHook(cleanupHook);
            return new Result(false, written, header.getMd5(), targetMd5);
        }
        try {
            validator.validate(temp);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(temp);
            removeCleanupHook(cleanupHook);
            throw e;
        }
        move(temp, output, overwrite);
        removeCleanupHook(cleanupHook);
        return new Result(true, written, header.getMd5(), targetMd5);
    }

    private static FileHead readHeader(QrReader reader, Listener listener) throws InterruptedException {
        while (true) {
            byte[] bytes = reader.read();
            if (bytes == null) {
                listener.log("未检测到二维码，等待文件头");
                Thread.sleep(500);
                continue;
            }
            try {
                FileHead header = new FileHead(bytes);
                if (header.getMd5() == null || header.getMd5().isBlank() || header.getFileSize() < 0) {
                    throw new IllegalArgumentException("无效文件头");
                }
                listener.log("读取文件头，大小: " + header.getFileSizeStr());
                return header;
            } catch (Exception e) {
                listener.log("文件头解析失败，重试");
                Thread.sleep(500);
            }
        }
    }

    private static boolean sameBytes(byte[] first, byte[] second) {
        if (first.length != second.length) {
            return false;
        }
        for (int i = 0; i < first.length; i++) {
            if (first[i] != second[i]) {
                return false;
            }
        }
        return true;
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
            // Best effort during JVM shutdown.
        }
    }

    private static void removeCleanupHook(Thread hook) {
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException ignored) {
            // Shutdown is already in progress and the hook will perform cleanup.
        }
    }
}
