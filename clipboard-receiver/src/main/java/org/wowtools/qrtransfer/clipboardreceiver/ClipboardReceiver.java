package org.wowtools.qrtransfer.clipboardreceiver;

import org.wowtools.qrtransfer.common.transfer.TransferReceiver;
import org.wowtools.qrtransfer.common.transfer.Utf8Text;
import org.wowtools.qrtransfer.common.util.Constant;
import org.wowtools.qrtransfer.common.util.QRCodeUtil;

import java.awt.AWTException;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

/** Command-line receiver for explicit UTF-8 clipboard bridge files. */
public final class ClipboardReceiver {
    private ClipboardReceiver() {
    }

    public static void main(String[] args) {
        try {
            Options options = Options.parse(args);
            Robot robot = new Robot();
            int nextKey = KeyEvent.getExtendedKeyCodeForChar(Constant.Key_NextPage);
            int previousKey = KeyEvent.getExtendedKeyCodeForChar(Constant.Key_BeforePage);
            System.out.println("等待二维码文件头。请保持发送端二维码可见，并让发送端日志区域获得键盘焦点。");
            TransferReceiver.Result result = TransferReceiver.receive(
                    () -> capture(robot),
                    new TransferReceiver.PageController() {
                        @Override
                        public void next() {
                            press(robot, nextKey);
                        }

                        @Override
                        public void previous() {
                            press(robot, previousKey);
                        }
                    },
                    options.output,
                    options.overwrite,
                    options.pageDelay,
                    Utf8Text::validate,
                    System.out::println);
            if (!result.isVerified()) {
                System.err.println("校验失败：源 MD5=" + result.getSourceMd5() + "，接收 MD5=" + result.getTargetMd5());
                System.exit(3);
            }
            System.out.println("完成：已写入 " + options.output + "（" + result.getBytesWritten() + " 字节）");
        } catch (AWTException e) {
            System.err.println("无法创建屏幕控制器: " + e.getMessage());
            System.exit(2);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("接收已中断");
            System.exit(4);
        } catch (Exception e) {
            System.err.println("接收失败: " + e.getMessage());
            System.err.println("用法: java -jar clipboard-receiver-1.0-SNAPSHOT.jar --output <文本文件> [--page-delay 500] [--overwrite]");
            System.exit(2);
        }
    }

    private static byte[] capture(Robot robot) {
        Dimension size = Toolkit.getDefaultToolkit().getScreenSize();
        BufferedImage image = robot.createScreenCapture(new Rectangle(size));
        return QRCodeUtil.parseQRCodeImage(image);
    }

    private static void press(Robot robot, int key) {
        robot.keyPress(key);
        robot.keyRelease(key);
    }

    private static final class Options {
        private final Path output;
        private final long pageDelay;
        private final boolean overwrite;

        private Options(Path output, long pageDelay, boolean overwrite) {
            this.output = output;
            this.pageDelay = pageDelay;
            this.overwrite = overwrite;
        }

        private static Options parse(String[] args) {
            Path output = null;
            long pageDelay = 500;
            boolean overwrite = false;
            for (int i = 0; i < args.length; i++) {
                String argument = args[i];
                if ("--output".equals(argument)) {
                    output = Path.of(value(args, ++i, argument));
                } else if ("--page-delay".equals(argument)) {
                    try {
                        pageDelay = Long.parseLong(value(args, ++i, argument));
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("--page-delay 必须是整数");
                    }
                } else if ("--overwrite".equals(argument)) {
                    overwrite = true;
                } else {
                    throw new IllegalArgumentException("未知参数: " + argument);
                }
            }
            if (output == null) {
                throw new IllegalArgumentException("缺少 --output");
            }
            if (pageDelay < 0) {
                throw new IllegalArgumentException("--page-delay 不能小于 0");
            }
            return new Options(output, pageDelay, overwrite);
        }

        private static String value(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException("缺少 " + option + " 的值");
            }
            return args[index];
        }
    }
}
