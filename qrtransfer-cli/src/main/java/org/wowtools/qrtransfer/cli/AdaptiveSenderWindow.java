package org.wowtools.qrtransfer.cli;

import org.wowtools.qrtransfer.common.transfer.Utf8Text;
import org.wowtools.qrtransfer.common.transfer.V2Frame;
import org.wowtools.qrtransfer.common.util.BinaryQRCodeUtil;
import org.wowtools.qrtransfer.common.util.Md5Util;

import javax.swing.JFrame;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
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

        Config(Path input, boolean text, int qrSize, int minPageSize, int initialPageSize,
               int maxPageSize, Integer fixedPageSize) {
            this.input = input;
            this.text = text;
            this.qrSize = qrSize;
            this.minPageSize = minPageSize;
            this.initialPageSize = initialPageSize;
            this.maxPageSize = maxPageSize;
            this.fixedPageSize = fixedPageSize;
        }
    }

    private final Config config;
    private final QrCanvas canvas;
    private final BufferedImage testImage;
    private final JTextArea log = new JTextArea();
    private final long sessionId = new SecureRandom().nextLong();
    private final java.awt.KeyEventDispatcher dispatcher = this::dispatchKey;
    private final TransferMetrics metrics = new TransferMetrics();

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
        log.setEditable(false);
        log.setLineWrap(true);
        add(new JScrollPane(log), BorderLayout.CENTER);
        setSize(Math.max(config.qrSize + 500, 820), Math.max(config.qrSize + 90, 480));
        setLocationByPlatform(true);
        setVisible(true);
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(dispatcher);
        append("正在计算文件 MD5 并校准二维码容量...");
        Thread prepare = new Thread(this::prepare, "qrtransfer-v2-prepare");
        prepare.setDaemon(true);
        prepare.start();
    }

    @Override
    public void dispose() {
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
                    calibratedMax, config.fixedPageSize);
            SwingUtilities.invokeLater(() -> {
                try {
                    showFrame(header);
                    headerVisible = true;
                    ready = true;
                    append("已加载 " + config.input.getFileName() + "，" + fileSize
                            + " 字节，MD5 " + md5);
                    append("二维码本机可识别上限 " + calibratedMax + " 字节；当前 "
                            + pageSizer.current() + " 字节/页");
                    append("请保持本窗口激活；接收端将自动确认、升速和降密重传。");
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
        if (key == '0') {
            resetToHeader();
            return true;
        }
        if (headerVisible) {
            if (key == '1') {
                showFirstPage();
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

    private synchronized void showFirstPage() {
        if (!headerVisible) {
            return;
        }
        metrics.start();
        headerVisible = false;
        currentOffset = 0;
        currentPageNumber = 0;
        showCurrentPage();
    }

    private synchronized void acknowledgeCurrent() {
        if (currentPage == null || completed) {
            return;
        }
        metrics.acceptedPage(currentPage.getPayload().length);
        if (currentPage.isEnd()) {
            completed = true;
            ready = false;
            metrics.finish();
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
        append("接收端请求降密：" + old + " → " + pageSizer.current() + " 字节/页");
        showCurrentPage();
    }

    private synchronized void resetToHeader() {
        try {
            currentPage = null;
            currentOffset = 0;
            currentPageNumber = 0;
            completed = false;
            metrics.reset();
            showFrame(header);
            headerVisible = true;
            append("已重置到文件头");
        } catch (Exception e) {
            fail(e);
        }
    }

    private void showCurrentPage() {
        try {
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
                append("本机校准降密：" + old + " → " + pageSizer.current());
            }
        } catch (Exception e) {
            fail(e);
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
            BinaryQRCodeUtil.generate(encoded, testImage);
            return Arrays.equals(encoded, BinaryQRCodeUtil.parse(testImage));
        } catch (Exception e) {
            return false;
        }
    }

    private void showFrame(V2Frame frame) throws Exception {
        BinaryQRCodeUtil.generate(frame.encode(), canvas.image);
        canvas.repaint();
    }

    private void fail(Exception e) {
        ready = false;
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
