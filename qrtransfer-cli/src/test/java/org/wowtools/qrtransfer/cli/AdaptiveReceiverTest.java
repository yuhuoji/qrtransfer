package org.wowtools.qrtransfer.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wowtools.qrtransfer.common.transfer.V2Frame;
import org.wowtools.qrtransfer.common.util.Md5Util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
        RecordingController controller = new RecordingController();
        AdaptiveReceiver.Result result = AdaptiveReceiver.receive(frames::removeFirst, controller, output,
                false, false, 0, 0, 0, 1000, 3, ignored -> { });

        assertArrayEquals(expected, Files.readAllBytes(output));
        assertEquals(2, result.metrics.pages());
        assertEquals(List.of(0, 0, 1), controller.acknowledgedPages);
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
                false, false, 0, 0, 0, 1000, 3, ignored -> { }));
        assertFalse(Files.exists(output));
    }

    @Test
    void repeatedPreviousPageIsReacknowledgedWithoutDensityReduction() throws Exception {
        byte[] expected = {7, 8};
        Path source = tempDir.resolve("duplicate-source.bin");
        Files.write(source, expected);
        long session = 5L;
        byte[] page0 = V2Frame.data(session, 0, 0, new byte[]{7}, false).encode();
        ArrayDeque<byte[]> frames = new ArrayDeque<>();
        frames.add(V2Frame.header(session, expected.length,
                Md5Util.getFileMD5(source.toFile()), false).encode());
        frames.add(page0);
        frames.add(page0);
        frames.add(V2Frame.data(session, 1, 1, new byte[]{8}, true).encode());
        RecordingController controller = new RecordingController();

        AdaptiveReceiver.receive(frames::removeFirst, controller,
                tempDir.resolve("duplicate-output.bin"),
                false, false, 0, 0, 0, 0, 3, ignored -> { });

        assertEquals(List.of(), controller.rejectedPages);
        assertEquals(List.of(0, 0, 0, 1), controller.acknowledgedPages);
    }

    @Test
    void firstTwoTimeoutsDoNotRequestDensityReduction() throws Exception {
        TimeoutScenario scenario = timeoutScenario(2);
        RecordingController controller = new RecordingController();

        AdaptiveReceiver.Result result = AdaptiveReceiver.receive(
                scenario::next, controller, tempDir.resolve("two-timeouts.bin"),
                false, false, 0, 0, 0, 0, 3, ignored -> { });

        assertEquals(List.of(), controller.rejectedPages);
        assertEquals(2, result.metrics.timeouts());
    }

    @Test
    void thirdTimeoutRequestsOneDensityReduction() throws Exception {
        TimeoutScenario scenario = timeoutScenario(3);
        RecordingController controller = new RecordingController();

        AdaptiveReceiver.Result result = AdaptiveReceiver.receive(
                scenario::next, controller, tempDir.resolve("three-timeouts.bin"),
                false, false, 0, 0, 0, 0, 3, ignored -> { });

        assertEquals(List.of(0), controller.rejectedPages);
        assertEquals(3, result.metrics.timeouts());
        assertEquals(1, result.metrics.retries());
    }

    private TimeoutScenario timeoutScenario(int timeoutCount) throws Exception {
        byte[] payload = {42};
        Path source = tempDir.resolve("timeout-source-" + timeoutCount + ".bin");
        Files.write(source, payload);
        long session = 100 + timeoutCount;
        List<byte[]> frames = new ArrayList<>();
        frames.add(V2Frame.header(session, payload.length,
                Md5Util.getFileMD5(source.toFile()), false).encode());
        for (int i = 0; i < timeoutCount; i++) {
            frames.add(null);
        }
        frames.add(V2Frame.data(session, 0, 0, payload, true).encode());
        return new TimeoutScenario(frames);
    }

    private static final class TimeoutScenario {
        private final List<byte[]> frames;
        private final AtomicInteger cursor = new AtomicInteger();

        private TimeoutScenario(List<byte[]> frames) {
            this.frames = frames;
        }

        private byte[] next() {
            return frames.get(cursor.getAndIncrement());
        }
    }

    private static final class NoopController implements AdaptiveReceiver.Controller {
        @Override
        public void acknowledge(int pageNumber) {
        }

        @Override
        public void reject(int pageNumber) {
        }
    }

    private static final class RecordingController implements AdaptiveReceiver.Controller {
        private final List<Integer> acknowledgedPages = new ArrayList<>();
        private final List<Integer> rejectedPages = new ArrayList<>();

        @Override
        public void acknowledge(int pageNumber) {
            acknowledgedPages.add(pageNumber);
        }

        @Override
        public void reject(int pageNumber) {
            rejectedPages.add(pageNumber);
        }
    }
}
