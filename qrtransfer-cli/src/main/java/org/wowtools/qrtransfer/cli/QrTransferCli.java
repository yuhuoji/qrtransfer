package org.wowtools.qrtransfer.cli;

import org.wowtools.qrtransfer.common.transfer.TransferReceiver;
import org.wowtools.qrtransfer.common.transfer.Utf8Text;
import org.wowtools.qrtransfer.common.util.BinaryQRCodeUtil;
import org.wowtools.qrtransfer.common.util.QRCodeUtil;

import javax.swing.SwingUtilities;
import java.awt.AWTException;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

/** One executable entry point for adaptive and legacy QR transfers. */
public final class QrTransferCli {
    private QrTransferCli() {
    }

    public static void main(String[] args) {
        try {
            if (args.length == 0) {
                throw new IllegalArgumentException("缺少 send 或 receive 子命令");
            }
            if ("send".equals(args[0])) {
                SendOptions options = SendOptions.parse(args);
                SwingUtilities.invokeLater(() -> {
                    if (options.legacy) {
                        new LegacySenderWindow(options.input, options.text,
                                options.qrSize, options.initialPageSize).showWindow();
                    } else {
                        AdaptiveSenderWindow.Config config = new AdaptiveSenderWindow.Config(
                                options.input, options.text, options.qrSize,
                                options.minPageSize, options.initialPageSize,
                                options.maxPageSize, options.fixedPageSize);
                        new AdaptiveSenderWindow(config).showWindow();
                    }
                });
                return;
            }
            if ("receive".equals(args[0])) {
                receive(ReceiveOptions.parse(args));
                return;
            }
            throw new IllegalArgumentException("未知子命令: " + args[0]);
        } catch (Exception e) {
            System.err.println("执行失败: " + e.getMessage());
            usage();
            System.exit(2);
        }
    }

    private static void receive(ReceiveOptions options) throws Exception {
        Robot robot;
        try {
            robot = new Robot();
        } catch (AWTException e) {
            throw new IllegalStateException("无法创建屏幕读取和键盘控制器: " + e.getMessage(), e);
        }
        countdown(options.startDelaySeconds);
        if (options.legacy) {
            System.out.println("等待 legacy 文件头；请保持发送端二维码完整可见并激活发送窗口。");
            TransferReceiver.Result result = TransferReceiver.receive(
                    () -> capture(robot, false),
                    new TransferReceiver.PageController() {
                        @Override
                        public void next() {
                            press(robot, '1');
                        }

                        @Override
                        public void previous() {
                            press(robot, '2');
                        }
                    },
                    options.output,
                    options.overwrite,
                    options.legacyPageDelay,
                    options.text ? Utf8Text::validate : ignored -> { },
                    System.out::println);
            if (!result.isVerified()) {
                throw new IllegalStateException("MD5 校验失败：源 " + result.getSourceMd5()
                        + "，接收 " + result.getTargetMd5());
            }
            System.out.println("完成：已写入 " + options.output + "（"
                    + result.getBytesWritten() + " 字节，legacy）");
            return;
        }

        System.out.println("等待 adaptive V2 文件头；请保持发送端二维码完整可见并激活发送窗口。");
        AdaptiveReceiver.Result result = AdaptiveReceiver.receive(
                () -> capture(robot, true),
                new AdaptiveReceiver.Controller() {
                    @Override
                    public void acknowledge(int pageNumber) {
                        press(robot, pageNumber % 2 == 0 ? '1' : '2');
                    }

                    @Override
                    public void reject(int pageNumber) {
                        press(robot, pageNumber % 2 == 0 ? '3' : '4');
                    }
                },
                options.output,
                options.overwrite,
                options.text,
                options.initialDelay,
                options.minDelay,
                options.maxDelay,
                options.frameTimeout,
                System.out::println);
        System.out.println("完成：已写入 " + options.output + "（" + result.bytesWritten
                + " 字节，MD5 " + result.md5 + "）");
        System.out.println(result.metrics.successSummary());
    }

    private static byte[] capture(Robot robot, boolean binary) {
        Dimension size = Toolkit.getDefaultToolkit().getScreenSize();
        BufferedImage image = robot.createScreenCapture(new Rectangle(size));
        return binary ? BinaryQRCodeUtil.parse(image) : QRCodeUtil.parseQRCodeImage(image);
    }

    private static void press(Robot robot, char value) {
        int key = KeyEvent.getExtendedKeyCodeForChar(value);
        robot.keyPress(key);
        robot.keyRelease(key);
    }

    private static void countdown(long seconds) throws InterruptedException {
        if (seconds <= 0) {
            return;
        }
        System.out.println("将在 " + seconds + " 秒后开始，请切回云桌面并激活发送端窗口。");
        for (long remaining = seconds; remaining > 0; remaining--) {
            System.out.println("开始倒计时: " + remaining);
            Thread.sleep(1000);
        }
    }

    private static void usage() {
        System.err.println("发送: java -jar qrtransfer-cli-1.0-SNAPSHOT.jar send --input <文件>"
                + " [--text] [--legacy] [--profile safe|balanced|fast]"
                + " [--qr-size N] [--min-page-size N] [--initial-page-size N]"
                + " [--max-page-size N] [--fixed-page-size N]");
        System.err.println("接收: java -jar qrtransfer-cli-1.0-SNAPSHOT.jar receive --output <文件>"
                + " [--text] [--legacy] [--overwrite] [--profile safe|balanced|fast]"
                + " [--start-delay 秒] [--initial-delay ms] [--min-delay ms]"
                + " [--max-delay ms] [--frame-timeout ms] [--page-delay ms]");
    }

    static final class SendOptions {
        final Path input;
        final boolean text;
        final boolean legacy;
        final int qrSize;
        final int minPageSize;
        final int initialPageSize;
        final int maxPageSize;
        final Integer fixedPageSize;

        private SendOptions(Path input, boolean text, boolean legacy, int qrSize,
                            int minPageSize, int initialPageSize, int maxPageSize,
                            Integer fixedPageSize) {
            this.input = input;
            this.text = text;
            this.legacy = legacy;
            this.qrSize = qrSize;
            this.minPageSize = minPageSize;
            this.initialPageSize = initialPageSize;
            this.maxPageSize = maxPageSize;
            this.fixedPageSize = fixedPageSize;
        }

        static SendOptions parse(String[] args) {
            TransferProfile profile = profile(args);
            Path input = null;
            boolean text = false;
            boolean legacy = false;
            int qrSize = profile.qrSize;
            int min = profile.minPageSize;
            int initial = profile.initialPageSize;
            int max = profile.maxPageSize;
            Integer fixed = null;
            for (int i = 1; i < args.length; i++) {
                switch (args[i]) {
                    case "--input":
                        input = Path.of(value(args, ++i, "--input"));
                        break;
                    case "--text":
                        text = true;
                        break;
                    case "--legacy":
                        legacy = true;
                        break;
                    case "--profile":
                        i++;
                        break;
                    case "--qr-size":
                        qrSize = integer(value(args, ++i, "--qr-size"), "--qr-size");
                        break;
                    case "--min-page-size":
                        min = integer(value(args, ++i, "--min-page-size"), "--min-page-size");
                        break;
                    case "--initial-page-size":
                        initial = integer(value(args, ++i, "--initial-page-size"), "--initial-page-size");
                        break;
                    case "--max-page-size":
                        max = integer(value(args, ++i, "--max-page-size"), "--max-page-size");
                        break;
                    case "--fixed-page-size":
                        fixed = integer(value(args, ++i, "--fixed-page-size"), "--fixed-page-size");
                        break;
                    default:
                        throw new IllegalArgumentException("未知发送参数: " + args[i]);
                }
            }
            if (input == null || !Files.isRegularFile(input)) {
                throw new IllegalArgumentException("输入文件不存在或未指定: " + input);
            }
            if (qrSize < 128 || min < 32 || max < min || initial < min || initial > max) {
                throw new IllegalArgumentException("二维码尺寸或页面大小范围无效");
            }
            if (fixed != null && (fixed < min || fixed > max)) {
                throw new IllegalArgumentException("--fixed-page-size 必须位于页面范围内");
            }
            return new SendOptions(input, text, legacy, qrSize, min, initial, max, fixed);
        }
    }

    static final class ReceiveOptions {
        final Path output;
        final boolean text;
        final boolean legacy;
        final boolean overwrite;
        final long startDelaySeconds;
        final long initialDelay;
        final long minDelay;
        final long maxDelay;
        final long frameTimeout;
        final long legacyPageDelay;

        private ReceiveOptions(Path output, boolean text, boolean legacy, boolean overwrite,
                               long startDelaySeconds, long initialDelay, long minDelay,
                               long maxDelay, long frameTimeout, long legacyPageDelay) {
            this.output = output;
            this.text = text;
            this.legacy = legacy;
            this.overwrite = overwrite;
            this.startDelaySeconds = startDelaySeconds;
            this.initialDelay = initialDelay;
            this.minDelay = minDelay;
            this.maxDelay = maxDelay;
            this.frameTimeout = frameTimeout;
            this.legacyPageDelay = legacyPageDelay;
        }

        static ReceiveOptions parse(String[] args) {
            TransferProfile profile = profile(args);
            Path output = null;
            boolean text = false;
            boolean legacy = false;
            boolean overwrite = false;
            long startDelay = 5;
            long initialDelay = profile.initialDelay;
            long minDelay = profile.minDelay;
            long maxDelay = profile.maxDelay;
            long timeout = profile.frameTimeout;
            long legacyPageDelay = 500;
            for (int i = 1; i < args.length; i++) {
                switch (args[i]) {
                    case "--output":
                        output = Path.of(value(args, ++i, "--output"));
                        break;
                    case "--text":
                        text = true;
                        break;
                    case "--legacy":
                        legacy = true;
                        break;
                    case "--overwrite":
                        overwrite = true;
                        break;
                    case "--profile":
                        i++;
                        break;
                    case "--start-delay":
                        startDelay = number(value(args, ++i, "--start-delay"), "--start-delay");
                        break;
                    case "--initial-delay":
                        initialDelay = number(value(args, ++i, "--initial-delay"), "--initial-delay");
                        break;
                    case "--min-delay":
                        minDelay = number(value(args, ++i, "--min-delay"), "--min-delay");
                        break;
                    case "--max-delay":
                        maxDelay = number(value(args, ++i, "--max-delay"), "--max-delay");
                        break;
                    case "--frame-timeout":
                        timeout = number(value(args, ++i, "--frame-timeout"), "--frame-timeout");
                        break;
                    case "--page-delay":
                        legacyPageDelay = number(value(args, ++i, "--page-delay"), "--page-delay");
                        break;
                    default:
                        throw new IllegalArgumentException("未知接收参数: " + args[i]);
                }
            }
            if (output == null) {
                throw new IllegalArgumentException("缺少 --output");
            }
            if (startDelay < 0 || minDelay < 0 || maxDelay < minDelay
                    || initialDelay < minDelay || initialDelay > maxDelay
                    || timeout <= 0 || legacyPageDelay < 0) {
                throw new IllegalArgumentException("等待时间参数无效");
            }
            return new ReceiveOptions(output, text, legacy, overwrite, startDelay,
                    initialDelay, minDelay, maxDelay, timeout, legacyPageDelay);
        }
    }

    private static TransferProfile profile(String[] args) {
        for (int i = 1; i < args.length; i++) {
            if ("--profile".equals(args[i])) {
                return TransferProfile.parse(value(args, i + 1, "--profile"));
            }
        }
        return TransferProfile.BALANCED;
    }

    private static String value(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException("缺少 " + option + " 的值");
        }
        return args[index];
    }

    private static int integer(String value, String option) {
        long parsed = number(value, option);
        if (parsed > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(option + " 超出整数范围");
        }
        return (int) parsed;
    }

    private static long number(String value, String option) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(option + " 必须是整数");
        }
    }
}
