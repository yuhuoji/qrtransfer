package org.wowtools.qrtransfer.cli;

import org.wowtools.qrtransfer.common.transfer.Utf8Text;
import org.wowtools.qrtransfer.common.transfer.V2Frame;
import org.wowtools.qrtransfer.common.util.BinaryQRCodeUtil;
import org.wowtools.qrtransfer.common.util.Md5Util;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Canvas;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.KeyboardFocusManager;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;

final class AdaptiveSenderWindow extends JFrame {
    static final class Config {
        final Path input;
        final boolean text;
        final int qrSize;
        final int minPageSize;
        final int initialPageSize;
        final int maxPageSize;
        final Integer fixedPageSize;
        final boolean pageLocalRecovery;
        final boolean compactEncoding;
        final boolean resume;

        Config(Path input, boolean text, int qrSize, int minPageSize, int initialPageSize,
               int maxPageSize, Integer fixedPageSize, boolean pageLocalRecovery,
               boolean compactEncoding, boolean resume) {
            this.input = input;
            this.text = text;
            this.qrSize = qrSize;
            this.minPageSize = minPageSize;
            this.initialPageSize = initialPageSize;
            this.maxPageSize = maxPageSize;
            this.fixedPageSize = fixedPageSize;
            this.pageLocalRecovery = pageLocalRecovery;
            this.compactEncoding = compactEncoding;
            this.resume = resume;
        }
    }

    private final Config config;
    private final QrCanvas canvas;
    private final BufferedImage testImage;
    private final JTextArea log = new JTextArea();
    private final JTextArea progress = new JTextArea(3, 36);
    private final long sessionId = new SecureRandom().nextLong();
    private final java.awt.KeyEventDispatcher dispatcher = this::dispatchKey;
    private final TransferMetrics metrics = new TransferMetrics();
    private final Timer progressTimer = new Timer(1000, event -> updateProgress());

    private RandomAccessFile input;
    private long fileSize;
    private String md5;
    private AdaptivePageSizer pageSizer;
    private V2Frame header;
    private V2Frame currentPage;
    private long currentOffset;
    private int currentPageNumber;
    private boolean headerVisible;
    private boolean ready;
    private boolean completed;
    private boolean resumeReadyVisible;
    private boolean resumeConfirmVisible;
    private final StringBuilder resumeCheckpointDigits = new StringBuilder();
    private long resumeOffset;
    private long sourceLastModified;

    AdaptiveSenderWindow(Config config) {
        super("qrtransfer send（adaptive V2）");
        this.config = config;
        this.canvas = new QrCanvas(config.qrSize);
        this.testImage = new BufferedImage(config.qrSize, config.qrSize, BufferedImage.TYPE_INT_RGB);
    }

    void showWindow() {
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLayout(new BorderLayout(12, 12));
        add(canvas, BorderLayout.WEST);
        progress.setEditable(false);
        progress.setFocusable(false);
        progress.setLineWrap(true);
        progress.setWrapStyleWord(true);
        progress.setText("传输进度：正在准备文件...");
        log.setEditable(false);
        log.setLineWrap(true);
        JPanel details = new JPanel(new BorderLayout(0, 8));
        details.add(progress, BorderLayout.NORTH);
        details.add(new JScrollPane(log), BorderLayout.CENTER);
        add(details, BorderLayout.CENTER);
        setSize(Math.max(config.qrSize + 500, 820), Math.max(config.qrSize + 90, 480));
        setLocationByPlatform(true);
        setVisible(true);
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(dispatcher);
        progressTimer.start();
        append("正在计算文件 MD5 并校准二维码容量...");
        Thread prepare = new Thread(this::prepare, "qrtransfer-v2-prepare");
        prepare.setDaemon(true);
        prepare.start();
    }

    @Override
    public void dispose() {
        progressTimer.stop();
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(dispatcher);
        closeInput();
        super.dispose();
    }

    private void prepare() {
        try {
            if (config.text) {
                Utf8Text.validate(config.input);
            }
            fileSize = java.nio.file.Files.size(config.input);
            sourceLastModified = java.nio.file.Files.getLastModifiedTime(config.input).toMillis();
            md5 = Md5Util.getFileMD5(config.input.toFile());
            input = new RandomAccessFile(config.input.toFile(), "r");
            header = V2Frame.header(sessionId, fileSize, md5, config.text);
            int calibratedMax = calibrateMaximum();
            if (config.fixedPageSize != null && config.fixedPageSize > calibratedMax) {
                throw new IllegalArgumentException("固定页面 " + config.fixedPageSize
                        + " 字节超过当前二维码可识别上限 " + calibratedMax);
            }
            int initial = Math.min(config.initialPageSize, calibratedMax);
            if (initial < config.minPageSize) {
                throw new IllegalArgumentException("二维码尺寸过小，无法容纳最小页面 "
                        + config.minPageSize + " 字节");
            }
            pageSizer = new AdaptivePageSizer(config.minPageSize, initial,
                    calibratedMax, config.fixedPageSize, config.pageLocalRecovery);
            SwingUtilities.invokeLater(() -> {
                try {
                    showFrame(header);
                    headerVisible = true;
                    ready = true;
                    append("已加载 " + config.input.getFileName() + "，" + fileSize
                            + " 字节，MD5 " + md5);
                    append("二维码本机可识别上限 " + calibratedMax + " 字节；当前 "
                            + pageSizer.current() + " 字节/页");
                    if (config.compactEncoding) {
                        append("Fast 紧凑编码已启用：取消 Base64 膨胀，优先提高单页吞吐");
                    }
                    append("请保持本窗口激活；接收端将自动确认、升速和降密重传。");
                    if (config.resume) {
                        append("断点续传已启用；接收端可协商最近的 1 MiB 检查点。");
                    }
                    updateProgress();
                } catch (Exception e) {
                    fail(e);
                }
            });
        } catch (Exception e) {
            SwingUtilities.invokeLater(() -> fail(e));
        }
    }

    private boolean dispatchKey(KeyEvent event) {
        if (event.getID() != KeyEvent.KEY_PRESSED || !isActive() || !ready) {
            return false;
        }
        char key = event.getKeyChar();
        if (resumeReadyVisible) {
            if (event.getKeyCode() == KeyEvent.VK_ESCAPE) {
                resetToHeader();
                return true;
            }
            if (event.getKeyCode() == KeyEvent.VK_ENTER) {
                confirmResumeCheckpoint();
                return true;
            }
            if (key == '5') {
                resumeCheckpointDigits.setLength(0);
                append("已清空恢复检查点编号，等待重新输入");
                return true;
            }
            if (key >= '0' && key <= '9' && resumeCheckpointDigits.length() < 12) {
                resumeCheckpointDigits.append(key);
                append("恢复检查点编号输入中：" + resumeCheckpointDigits);
                return true;
            }
            return false;
        }
        if (resumeConfirmVisible) {
            if (key == '7') {
                startAt(resumeOffset);
                return true;
            }
            if (key == '8' || event.getKeyCode() == KeyEvent.VK_ESCAPE) {
                enterResumeReady();
                return true;
            }
            return false;
        }
        if (key == '0') {
            resetToHeader();
            return true;
        }
        if (headerVisible) {
            if (key == '1') {
                startAt(0);
                return true;
            }
            if (key == '6' && config.resume) {
                enterResumeReady();
                return true;
            }
            return false;
        }
        if (currentPage == null) {
            return false;
        }
        char acknowledge = currentPageNumber % 2 == 0 ? '1' : '2';
        char reject = currentPageNumber % 2 == 0 ? '3' : '4';
        if (key == acknowledge) {
            acknowledgeCurrent();
            return true;
        }
        if (key == reject) {
            rejectCurrent();
            return true;
        }
        return false;
    }

    private synchronized void startAt(long offset) {
        if (!headerVisible && !resumeConfirmVisible) {
            return;
        }
        metrics.start();
        updateProgress();
        headerVisible = false;
        resumeReadyVisible = false;
        resumeConfirmVisible = false;
        currentOffset = offset;
        currentPageNumber = 0;
        append(offset == 0 ? "从头开始传输" : "从断点偏移 " + offset + " 继续传输");
        showCurrentPage();
    }

    private synchronized void enterResumeReady() {
        try {
            resumeCheckpointDigits.setLength(0);
            resumeReadyVisible = true;
            resumeConfirmVisible = false;
            headerVisible = false;
            showFrame(V2Frame.resumeReady(sessionId));
            append("已进入恢复协商，等待接收端发送检查点编号");
        } catch (Exception e) {
            fail(e);
        }
    }

    private synchronized void confirmResumeCheckpoint() {
        try {
            if (resumeCheckpointDigits.length() == 0) {
                append("恢复检查点编号为空，继续等待");
                return;
            }
            long checkpoint = Long.parseLong(resumeCheckpointDigits.toString());
            long offset = Math.multiplyExact(checkpoint, ResumeCheckpoint.DEFAULT_BLOCK_SIZE);
            if (offset < 0 || offset > fileSize) {
                append("恢复检查点越界：" + checkpoint);
                resumeCheckpointDigits.setLength(0);
                return;
            }
            verifySourceUnchanged();
            String prefixMd5 = ResumeCheckpoint.md5(config.input, 0, offset);
            resumeOffset = offset;
            resumeReadyVisible = false;
            resumeConfirmVisible = true;
            showFrame(V2Frame.resumeConfirm(sessionId, offset, prefixMd5));
            append("恢复确认：检查点 " + checkpoint + "，偏移 " + offset
                    + "，前缀 MD5 " + prefixMd5);
        } catch (Exception e) {
            fail(e);
        }
    }

    private synchronized void acknowledgeCurrent() {
        if (currentPage == null || completed) {
            return;
        }
        metrics.acceptedPage(currentPage.getPayload().length);
        updateProgress();
        if (currentPage.isEnd()) {
            completed = true;
            ready = false;
            metrics.finish();
            updateProgress();
            append("接收端已确认文件完整性校验成功");
            append(metrics.successSummary());
            return;
        }
        currentOffset += currentPage.getPayload().length;
        currentPageNumber++;
        pageSizer.onSuccess();
        showCurrentPage();
    }

    private synchronized void rejectCurrent() {
        if (currentPage == null) {
            return;
        }
        int old = pageSizer.current();
        metrics.retry();
        boolean changed = pageSizer.onFailure();
        if (!changed) {
            append("当前页识别失败，但页面大小已到下限/固定值 " + old
                    + "；请增大 --qr-size 或改用 safe profile");
            return;
        }
        metrics.densityDrop();
        append("接收端请求" + recoveryScope() + "降密：" + old + " → "
                + pageSizer.current() + " 字节/页");
        showCurrentPage();
    }

    private synchronized void resetToHeader() {
        try {
            currentPage = null;
            currentOffset = 0;
            currentPageNumber = 0;
            completed = false;
            resumeReadyVisible = false;
            resumeConfirmVisible = false;
            resumeCheckpointDigits.setLength(0);
            metrics.reset();
            showFrame(header);
            headerVisible = true;
            append("已重置到文件头");
            updateProgress();
        } catch (Exception e) {
            fail(e);
        }
    }

    private void showCurrentPage() {
        try {
            verifySourceUnchanged();
            while (true) {
                long remaining = fileSize - currentOffset;
                int size = (int) Math.min(pageSizer.current(), Math.max(0, remaining));
                byte[] payload = new byte[size];
                input.seek(currentOffset);
                input.readFully(payload);
                boolean end = currentOffset + size >= fileSize;
                V2Frame candidate = V2Frame.data(sessionId, currentPageNumber,
                        currentOffset, payload, end);
                byte[] encoded = candidate.encode();
                if (canReadLocally(encoded)) {
                    currentPage = candidate;
                    showFrame(candidate);
                    append("页面 " + currentPageNumber + "，偏移 " + currentOffset + "，"
                            + size + " 字节" + (end ? "（末页）" : ""));
                    return;
                }
                metrics.recognitionFailure();
                int old = pageSizer.current();
                if (!pageSizer.onFailure()) {
                    throw new IllegalStateException("页面 " + old + " 字节在当前二维码尺寸下无法识别");
                }
                metrics.densityDrop();
                append("本机校准" + recoveryScope() + "降密：" + old + " → "
                        + pageSizer.current());
            }
        } catch (Exception e) {
            fail(e);
        }
    }

    private void verifySourceUnchanged() throws IOException {
        if (java.nio.file.Files.size(config.input) != fileSize
                || java.nio.file.Files.getLastModifiedTime(config.input).toMillis()
                != sourceLastModified) {
            throw new IOException("源文件在准备或传输期间发生变化，已停止发送");
        }
    }

    private int calibrateMaximum() throws Exception {
        int low = config.minPageSize;
        int high = config.maxPageSize;
        int best = 0;
        while (low <= high) {
            int middle = low + (high - low) / 2;
            byte[] payload = new byte[middle];
            for (int i = 0; i < payload.length; i++) {
                payload[i] = (byte) (i * 31 + 17);
            }
            byte[] encoded = V2Frame.data(sessionId, 0, 0, payload, false).encode();
            if (canReadLocally(encoded)) {
                best = middle;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return best;
    }

    private boolean canReadLocally(byte[] encoded) {
        try {
            generateQr(encoded, testImage);
            return Arrays.equals(encoded, BinaryQRCodeUtil.parse(testImage));
        } catch (Exception e) {
            return false;
        }
    }

    private String recoveryScope() {
        return config.pageLocalRecovery ? "当前页" : "全局";
    }

    private void showFrame(V2Frame frame) throws Exception {
        byte[] encoded = frame.encode();
        if (config.compactEncoding && frame.isData()) {
            BinaryQRCodeUtil.generateCompact(encoded, canvas.image);
        } else {
            BinaryQRCodeUtil.generate(encoded, canvas.image);
        }
        canvas.repaint();
    }

    private void generateQr(byte[] encoded, BufferedImage image) throws Exception {
        if (config.compactEncoding) {
            BinaryQRCodeUtil.generateCompact(encoded, image);
        } else {
            BinaryQRCodeUtil.generate(encoded, image);
        }
    }

    private void fail(Exception e) {
        ready = false;
        updateProgress();
        append("发送失败：" + e.getMessage());
        append(metrics.failureSummary(e.getMessage()));
    }

    private void append(String message) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> append(message));
            return;
        }
        log.append(message + System.lineSeparator());
        log.setCaretPosition(log.getDocument().getLength());
    }

    private void updateProgress() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::updateProgress);
            return;
        }
        if (header == null) {
            progress.setText("传输进度：正在准备文件...");
            return;
        }
        progress.setText(metrics.progressSummary(fileSize));
    }

    private void closeInput() {
        if (input != null) {
            try {
                input.close();
            } catch (IOException ignored) {
                // Window is closing.
            }
        }
    }

    private static final class QrCanvas extends Canvas {
        private final BufferedImage image;

        private QrCanvas(int size) {
            this.image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
            setPreferredSize(new Dimension(size + 20, size + 20));
        }

        @Override
        public void paint(Graphics graphics) {
            graphics.drawImage(image, 10, 10, this);
        }
    }
}
