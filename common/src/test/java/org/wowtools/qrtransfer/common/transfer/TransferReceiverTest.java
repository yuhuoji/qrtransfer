package org.wowtools.qrtransfer.common.transfer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wowtools.qrtransfer.common.pojo.FileHead;
import org.wowtools.qrtransfer.common.protobuf.QrPageProto;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransferReceiverTest {
    @TempDir
    Path tempDir;

    @Test
    void writesVerifiedTextAndIgnoresDuplicatePage() throws Exception {
        byte[] expected = "多行 JSON\\n{\"name\":\"二维码\"}\\n😀".getBytes(StandardCharsets.UTF_8);
        Path input = tempDir.resolve("input.txt");
        Files.write(input, expected);
        FileHead head = TransferFileReader.readHead(input.toFile());
        List<byte[]> frames = frames(head, input, true);
        Path output = tempDir.resolve("output.txt");

        TransferReceiver.Result result = TransferReceiver.receive(
                new QueueReader(frames), new NoopController(), output, false, 0,
                Utf8Text::validate, ignored -> { });

        assertTrue(result.isVerified());
        assertArrayEquals(expected, Files.readAllBytes(output));
    }

    @Test
    void doesNotPublishChecksumMismatch() throws Exception {
        Path input = tempDir.resolve("input.txt");
        Files.writeString(input, "hello", StandardCharsets.UTF_8);
        FileHead head = TransferFileReader.readHead(input.toFile());
        head.setMd5("not-the-source-md5");
        List<byte[]> frames = frames(head, input, false);
        Path output = tempDir.resolve("output.txt");

        TransferReceiver.Result result = TransferReceiver.receive(
                new QueueReader(frames), new NoopController(), output, false, 0,
                Utf8Text::validate, ignored -> { });

        assertFalse(result.isVerified());
        assertFalse(Files.exists(output));
    }

    private static List<byte[]> frames(FileHead head, Path input, boolean duplicateFirstPage) {
        List<byte[]> frames = new ArrayList<>();
        frames.add(head.toByte());
        frames.add(head.toByte());
        Iterator<QrPageProto.QrPagePb> pages = TransferFileReader.readPages(input.toFile(), 32, null);
        boolean duplicated = false;
        while (pages.hasNext()) {
            QrPageProto.QrPagePb page = pages.next();
            frames.add(page.toByteArray());
            if (duplicateFirstPage && !duplicated) {
                frames.add(page.toByteArray());
                duplicated = true;
            }
            if ("end".equals(page.getMessage())) {
                break;
            }
        }
        return frames;
    }

    private static final class QueueReader implements TransferReceiver.QrReader {
        private final ArrayDeque<byte[]> frames;

        private QueueReader(List<byte[]> frames) {
            this.frames = new ArrayDeque<>(frames);
        }

        @Override
        public byte[] read() {
            return frames.removeFirst();
        }
    }

    private static final class NoopController implements TransferReceiver.PageController {
        @Override
        public void next() {
        }

        @Override
        public void previous() {
        }
    }
}
