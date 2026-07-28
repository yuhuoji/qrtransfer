package org.wowtools.qrtransfer.cli;

import org.wowtools.qrtransfer.common.transfer.V2Frame;
import org.wowtools.qrtransfer.common.util.BinaryQRCodeUtil;

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
import java.security.SecureRandom;
import java.util.Arrays;

final class BenchmarkSenderWindow extends JFrame {
    static final class Config {
        final int qrSize;
        final int minPageSize;
        final int initialPageSize;
        final int maxPageSize;
        final boolean pageLocalRecovery;

        Config(int qrSize, int minPageSize, int initialPageSize, int maxPageSize,
               boolean pageLocalRecovery) {
            this.qrSize = qrSize;
            this.minPageSize = minPageSize;
            this.initialPageSize = initialPageSize;
            this.maxPageSize = maxPageSize;
            this.pageLocalRecovery = pageLocalRecovery;
        }
    }

    private final Config config;
    private final QrCanvas canvas;
    private final BufferedImage testImage;
    private final JTextArea log = new JTextArea();
    private final long sessionId = new SecureRandom().nextLong();
    private final java.awt.KeyEventDispatcher dispatcher = this::dispatchKey;
    private final TransferMetrics metrics = new TransferMetrics();

    private AdaptivePageSizer pageSizer;
    private V2Frame header;
    private V2Frame currentPage;
    private long currentOffset;
    private int currentPageNumber;
    private boolean headerVisible;
    private boolean ready;
    private boolean completed;

    BenchmarkSenderWindow(Config config) {
        super("qrtransfer benchmark send（adaptive V2）");
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
        append("正在校准测速二维码容量...");
        Thread prepare = new Thread(this::prepare, "qrtransfer-benchmark-prepare");
        prepare.setDaemon(true);
        prepare.start();
    }

    @Override
    public void dispose() {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(dispatcher);
        super.dispose();
    }

    private void prepare() {
        try {
            int calibratedMax = calibrateMaximum();
            int initial = Math.min(config.initialPageSize, calibratedMax);
            if (initial < config.minPageSize) {
                throw new IllegalArgumentException("二维码尺寸过小，无法容纳最小测速页面 "
                        + config.minPageSize + " 字节");
            }
            pageSizer = new AdaptivePageSizer(
                    config.minPageSize, initial, calibratedMax, null, config.pageLocalRecovery);
            header = V2Frame.benchmarkHeader(sessionId, config.qrSize, config.minPageSize,
                    initial, calibratedMax);
            SwingUtilities.invokeLater(() -> {
                try {
                    showFrame(header);
                    headerVisible = true;
                    ready = true;
                    append("测速二维码已就绪，本机可识别页面范围 "
                            + config.minPageSize + "–" + calibratedMax + " 字节");
                    append("请保持本窗口激活，然后在接收端启动 benchmark-receive。");
                    append("测速只发送合成数据，不读取真实文件。");
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
        if (key == '5') {
            finishBenchmark();
            return true;
        }
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
        if (!headerVisible || completed) {
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
        currentOffset += currentPage.getPayload().length;
        currentPageNumber++;
        pageSizer.onSuccess();
        showCurrentPage();
    }

    private synchronized void rejectCurrent() {
        if (currentPage == null || completed) {
            return;
        }
        int old = pageSizer.current();
        metrics.retry();
        if (!pageSizer.onFailure()) {
            append("测速页已到最低密度 " + old + " 字节，等待接收端重试");
            return;
        }
        metrics.densityDrop();
        append("接收端请求降密：" + old + " → " + pageSizer.current() + " 字节/页");
        showCurrentPage();
    }

    private synchronized void finishBenchmark() {
        if (completed) {
            return;
        }
        completed = true;
        ready = false;
        metrics.finish();
        append("接收端已结束测速");
        append(metrics.successSummary());
        append("完整的速度和推荐配置请查看接收端报告。");
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
            append("测速已重置到文件头");
        } catch (Exception e) {
            fail(e);
        }
    }

    private void showCurrentPage() {
        try {
            while (true) {
                int size = pageSizer.current();
                byte[] payload = syntheticPayload(size, currentOffset, currentPageNumber);
                V2Frame candidate = V2Frame.data(
                        sessionId, currentPageNumber, currentOffset, payload, false);
                byte[] encoded = candidate.encode();
                if (canReadLocally(encoded)) {
                    currentPage = candidate;
                    showFrame(candidate);
                    return;
                }
                metrics.recognitionFailure();
                int old = pageSizer.current();
                if (!pageSizer.onFailure()) {
                    throw new IllegalStateException("测速页面 " + old
                            + " 字节在当前二维码尺寸下无法识别");
                }
                metrics.densityDrop();
                append("本机校准降密：" + old + " → " + pageSizer.current());
            }
        } catch (Exception e) {
            fail(e);
        }
    }

    private int calibrateMaximum() {
        int low = config.minPageSize;
        int high = config.maxPageSize;
        int best = 0;
        while (low <= high) {
            int middle = low + (high - low) / 2;
            V2Frame candidate = V2Frame.data(
                    sessionId, 0, 0, syntheticPayload(middle, 0, 0), false);
            if (canReadLocally(candidate.encode())) {
                best = middle;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return best;
    }

    private byte[] syntheticPayload(int size, long offset, int pageNumber) {
        byte[] payload = new byte[size];
        long state = sessionId ^ offset ^ ((long) pageNumber * 0x9E3779B97F4A7C15L);
        for (int i = 0; i < payload.length; i++) {
            state ^= state << 13;
            state ^= state >>> 7;
            state ^= state << 17;
            payload[i] = (byte) state;
        }
        return payload;
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
        append("测速发送失败：" + e.getMessage());
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
