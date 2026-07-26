package org.wowtools.qrtransfer.cli;

import org.wowtools.qrtransfer.common.pojo.FileHead;
import org.wowtools.qrtransfer.common.protobuf.QrPageProto;
import org.wowtools.qrtransfer.common.transfer.TransferFileReader;
import org.wowtools.qrtransfer.common.transfer.Utf8Text;
import org.wowtools.qrtransfer.common.util.QRCodeUtil;

import javax.swing.JFrame;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import java.awt.BorderLayout;
import java.awt.Canvas;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.KeyboardFocusManager;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Legacy-compatible sender for arbitrary bytes. */
final class LegacySenderWindow extends JFrame {
    private static final int CACHE_SIZE = 100;
    private final Path input;
    private final boolean text;
    private final QrCanvas canvas;
    private final BufferedImage testImage;
    private final int pageSize;
    private final JTextArea log = new JTextArea();
    private final List<QrPageProto.QrPagePb> cache = new ArrayList<>();
    private final java.awt.KeyEventDispatcher dispatcher = this::dispatchKey;
    private Iterator<QrPageProto.QrPagePb> pages;
    private int cursor = -1;

    LegacySenderWindow(Path input, boolean text, int qrSize, int pageSize) {
        super("qrtransfer send（legacy）");
        this.input = input;
        this.text = text;
        this.pageSize = pageSize;
        this.canvas = new QrCanvas(qrSize);
        this.testImage = new BufferedImage(qrSize, qrSize, BufferedImage.TYPE_INT_RGB);
    }

    void showWindow() {
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLayout(new BorderLayout(12, 12));
        add(canvas, BorderLayout.WEST);
        log.setEditable(false);
        add(new JScrollPane(log), BorderLayout.CENTER);
        setSize(Math.max(canvas.image.getWidth() + 500, 820),
                Math.max(canvas.image.getHeight() + 90, 480));
        setLocationByPlatform(true);
        setVisible(true);
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(dispatcher);
        try {
            if (text) {
                Utf8Text.validate(input);
            }
            FileHead header = TransferFileReader.readHead(input.toFile());
            showQr(header.toByte());
            pages = TransferFileReader.readPages(input.toFile(), pageSize, this::isReadable);
            append("legacy 文件头已显示：" + input.getFileName() + "，"
                    + header.getFileSizeStr() + "，MD5 " + header.getMd5());
        } catch (Exception e) {
            append("发送失败：" + e.getMessage());
        }
    }

    @Override
    public void dispose() {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(dispatcher);
        super.dispose();
    }

    private boolean dispatchKey(KeyEvent event) {
        if (event.getID() != KeyEvent.KEY_PRESSED || !isActive() || pages == null) {
            return false;
        }
        if (event.getKeyChar() == '1') {
            nextPage();
            return true;
        }
        if (event.getKeyChar() == '2') {
            previousPage();
            return true;
        }
        return false;
    }

    private void nextPage() {
        try {
            if (cursor + 1 < cache.size()) {
                cursor++;
                showPage(cache.get(cursor));
                return;
            }
            if (!pages.hasNext()) {
                append("已到末页");
                return;
            }
            QrPageProto.QrPagePb page = pages.next();
            cache.add(page);
            cursor++;
            if (cache.size() > CACHE_SIZE) {
                cache.remove(0);
                cursor--;
            }
            showPage(page);
        } catch (Exception e) {
            append("翻页失败：" + e.getMessage());
        }
    }

    private void previousPage() {
        try {
            if (cursor <= 0) {
                append("无法继续回退；仅保留最近 " + CACHE_SIZE + " 页");
                return;
            }
            cursor--;
            showPage(cache.get(cursor));
        } catch (Exception e) {
            append("回退失败：" + e.getMessage());
        }
    }

    private void showPage(QrPageProto.QrPagePb page) throws Exception {
        showQr(page.toByteArray());
        append("页面 " + page.getPageNum() + "，" + page.getBytes().size() + " 字节"
                + ("end".equals(page.getMessage()) ? "（末页）" : ""));
    }

    private boolean isReadable(byte[] encoded) {
        try {
            QRCodeUtil.generateQRCodeImage(encoded, testImage);
            return QRCodeUtil.parseQRCodeImage(testImage) != null;
        } catch (Exception e) {
            return false;
        }
    }

    private void showQr(byte[] encoded) throws Exception {
        QRCodeUtil.generateQRCodeImage(encoded, canvas.image);
        canvas.repaint();
    }

    private void append(String message) {
        log.append(message + System.lineSeparator());
        log.setCaretPosition(log.getDocument().getLength());
    }

    private static final class QrCanvas extends Canvas {
        private final BufferedImage image;

        private QrCanvas(int size) {
            image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
            setPreferredSize(new Dimension(size + 20, size + 20));
        }

        @Override
        public void paint(Graphics graphics) {
            graphics.drawImage(image, 10, 10, this);
        }
    }
}
