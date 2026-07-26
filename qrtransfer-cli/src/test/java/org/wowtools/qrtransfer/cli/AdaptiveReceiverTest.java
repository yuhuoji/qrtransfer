package org.wowtools.qrtransfer.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wowtools.qrtransfer.common.transfer.V2Frame;
import org.wowtools.qrtransfer.common.util.Md5Util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AdaptiveReceiverTest {
    @TempDir
    Path tempDir;

    @Test
    void receivesArbitraryBinaryFile() throws Exception {
        byte[] expected = new byte[4096];
        for (int i = 0; i < expected.length; i++) {
            expected[i] = (byte) (i * 31);
        }
        Path source = tempDir.resolve("source.zip");
        Files.write(source, expected);
        long session = 99L;
        ArrayDeque<byte[]> frames = new ArrayDeque<>();
        frames.add(V2Frame.header(session, expected.length,
                Md5Util.getFileMD5(source.toFile()), false).encode());
        frames.add(V2Frame.data(session, 0, 0,
                Arrays.copyOfRange(expected, 0, 2000), false).encode());
        frames.add(V2Frame.data(session, 1, 2000,
                Arrays.copyOfRange(expected, 2000, expected.length), true).encode());

        Path output = tempDir.resolve("received.zip");
        AdaptiveReceiver.receive(frames::removeFirst, new NoopController(), output,
                false, false, 0, 0, 0, 1000, ignored -> { });

        assertArrayEquals(expected, Files.readAllBytes(output));
    }

    @Test
    void refusesMismatchedMd5AndDoesNotPublishOutput() throws Exception {
        byte[] expected = {1, 2, 3};
        ArrayDeque<byte[]> frames = new ArrayDeque<>();
        frames.add(V2Frame.header(1L, expected.length,
                "00000000000000000000000000000000", false).encode());
        frames.add(V2Frame.data(1L, 0, 0, expected, true).encode());
        Path output = tempDir.resolve("bad.bin");

        assertThrows(Exception.class, () -> AdaptiveReceiver.receive(
                frames::removeFirst, new NoopController(), output,
                false, false, 0, 0, 0, 1000, ignored -> { }));
        assertFalse(Files.exists(output));
    }

    private static final class NoopController implements AdaptiveReceiver.Controller {
        @Override
        public void acknowledge(int pageNumber) {
        }

        @Override
        public void reject(int pageNumber) {
        }
    }
}
