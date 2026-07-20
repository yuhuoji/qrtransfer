package org.wowtools.qrtransfer.clipboardsender;

import org.wowtools.qrtransfer.common.pojo.FileHead;
import org.wowtools.qrtransfer.common.protobuf.QrPageProto;
import org.wowtools.qrtransfer.common.transfer.TransferFileReader;
import org.wowtools.qrtransfer.common.transfer.Utf8Text;
import org.wowtools.qrtransfer.common.util.Constant;
import org.wowtools.qrtransfer.common.util.QRCodeUtil;

import javax.swing.JFrame;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Canvas;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Command-line entry point that displays an explicit UTF-8 text file as QR pages. */
public final class ClipboardSender {
    private ClipboardSender() {
    }

    public static void main(String[] args) {
        try {
            Options options = Options.parse(args);
            Utf8Text.validate(options.input);
            SwingUtilities.invokeAndWait(() -> new SenderWindow(options).showWindow());
        } catch (Exception e) {
            System.err.println("发送失败: " + e.getMessage());
            System.err.println("用法: java -jar clipboard-sender-1.0-SNAPSHOT.jar --input <UTF-8文本文件> [--qr-size 256] [--page-size 1600]");
            System.exit(2);
        }
    }

    private static final class SenderWindow extends JFrame {
        private static final int PAGE_CACHE_SIZE = 100;
        private final Options options;
        private final QrCanvas canvas;
        private final BufferedImage testImage;
        private final JTextArea log = new JTextArea();
        private final AtomicBoolean busy = new AtomicBoolean();
        private Iterator<QrPageProto.QrPagePb> pages;
        private final List<QrPageProto.QrPagePb> cache = new ArrayList<>();
        private int cursor = -1;

        private SenderWindow(Options options) {
            super("clipboard-sender");
            this.options = options;
            this.canvas = new QrCanvas(options.qrSize);
            this.testImage = new BufferedImage(options.qrSize, options.qrSize, BufferedImage.TYPE_INT_RGB);
        }

        private void showWindow() {
            setDefaultCloseOperation(EXIT_ON_CLOSE);
            setLayout(new BorderLayout(12, 12));
            add(canvas, BorderLayout.WEST);
            log.setEditable(false);
            log.setLineWrap(true);
            log.addKeyListener(new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent event) {
                    if (event.getKeyChar() == Constant.Key_NextPage) {
                        nextPage();
                    } else if (event.getKeyChar() == Constant.Key_BeforePage) {
                        previousPage();
                    }
                }
            });
            add(new JScrollPane(log), BorderLayout.CENTER);
            setSize(Math.max(options.qrSize + 460, 760), Math.max(options.qrSize + 80, 460));
            setLocationByPlatform(true);
            setVisible(true);
            log.requestFocusInWindow();
            start();
        }

        private void start() {
            try {
                File input = options.input.toFile();
                FileHead header = TransferFileReader.readHead(input);
                showQr(header.toByte());
                pages = TransferFileReader.readPages(input, options.pageSize, this::isReadableQr);
                append("已加载: " + input.getName() + "，大小: " + header.getFileSizeStr());
                append("点击日志区域保持焦点；接收端将使用 1/2 键翻页。");
            } catch (Exception e) {
                append("初始化失败: " + e.getMessage());
                throw new IllegalStateException(e);
            }
        }

        private boolean isReadableQr(byte[] bytes) {
            try {
                QRCodeUtil.generateQRCodeImage(bytes, testImage);
                return QRCodeUtil.parseQRCodeImage(testImage) != null;
            } catch (Exception e) {
                return false;
            }
        }

        private void nextPage() {
            if (busy.getAndSet(true)) {
                append("正在生成二维码，忽略重复翻页");
                return;
            }
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
                if (cache.size() > PAGE_CACHE_SIZE) {
                    cache.remove(0);
                    cursor--;
                }
                showPage(page);
            } finally {
                busy.set(false);
            }
        }

        private void previousPage() {
            if (busy.getAndSet(true)) {
                append("正在生成二维码，忽略重复翻页");
                return;
            }
            try {
                if (cursor <= 0) {
                    append("无法返回上一页；最多保留最近 " + PAGE_CACHE_SIZE + " 页");
                    return;
                }
                cursor--;
                showPage(cache.get(cursor));
            } finally {
                busy.set(false);
            }
        }

        private void showPage(QrPageProto.QrPagePb page) {
            try {
                showQr(page.toByteArray());
                append("页面 " + page.getPageNum() + "，" + page.getBytes().size() + " 字节" +
                        (Constant.Flag_End.equals(page.getMessage()) ? "（末页）" : ""));
            } catch (Exception e) {
                throw new IllegalStateException("生成二维码失败", e);
            }
        }

        private void showQr(byte[] bytes) throws Exception {
            QRCodeUtil.generateQRCodeImage(bytes, canvas.image);
            canvas.repaint();
        }

        private void append(String message) {
            log.append(message + System.lineSeparator());
            log.setCaretPosition(log.getDocument().getLength());
        }
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

    private static final class Options {
        private final Path input;
        private final int qrSize;
        private final int pageSize;

        private Options(Path input, int qrSize, int pageSize) {
            this.input = input;
            this.qrSize = qrSize;
            this.pageSize = pageSize;
        }

        private static Options parse(String[] args) {
            Path input = null;
            int qrSize = 256;
            int pageSize = 1600;
            for (int i = 0; i < args.length; i++) {
                String argument = args[i];
                if ("--input".equals(argument)) {
                    input = Path.of(value(args, ++i, argument));
                } else if ("--qr-size".equals(argument)) {
                    qrSize = positiveInt(value(args, ++i, argument), argument);
                } else if ("--page-size".equals(argument)) {
                    pageSize = positiveInt(value(args, ++i, argument), argument);
                } else {
                    throw new IllegalArgumentException("未知参数: " + argument);
                }
            }
            if (input == null) {
                throw new IllegalArgumentException("缺少 --input");
            }
            if (!Files.isRegularFile(input)) {
                throw new IllegalArgumentException("输入文件不存在: " + input);
            }
            if (qrSize < 128 || pageSize < 32) {
                throw new IllegalArgumentException("--qr-size 至少为 128，--page-size 至少为 32");
            }
            return new Options(input, qrSize, pageSize);
        }

        private static String value(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException("缺少 " + option + " 的值");
            }
            return args[index];
        }

        private static int positiveInt(String value, String option) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(option + " 必须是整数");
            }
        }
    }
}
