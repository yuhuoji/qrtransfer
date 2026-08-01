package org.wowtools.qrtransfer.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QrTransferCliOptionsTest {
    @TempDir
    Path tempDir;

    @Test
    void arbitraryFileIsDefaultAndExplicitValuesOverrideProfile() throws Exception {
        Path input = tempDir.resolve("archive.zip");
        Files.write(input, new byte[]{0, 1, 2});
        QrTransferCli.SendOptions options = QrTransferCli.SendOptions.parse(new String[]{
                "send", "--input", input.toString(), "--profile", "fast",
                "--qr-size", "700", "--initial-page-size", "2000"
        });
        assertFalse(options.text);
        assertEquals(700, options.qrSize);
        assertEquals(384, options.minPageSize);
        assertEquals(2000, options.initialPageSize);
        assertEquals(2800, options.maxPageSize);
        assertTrue(options.pageLocalRecovery);
        assertTrue(options.compactEncoding);
    }

    @Test
    void parsesTextAndLegacyReceiveFlags() {
        QrTransferCli.ReceiveOptions options = QrTransferCli.ReceiveOptions.parse(new String[]{
                "receive", "--output", tempDir.resolve("out.md").toString(),
                "--text", "--legacy", "--overwrite", "--page-delay", "600"
        });
        assertTrue(options.text);
        assertTrue(options.legacy);
        assertTrue(options.overwrite);
        assertEquals(600, options.legacyPageDelay);
        assertEquals(3, options.downshiftAfterTimeouts);
    }

    @Test
    void rejectsInvalidAdaptiveBounds() throws Exception {
        Path input = tempDir.resolve("input.bin");
        Files.write(input, new byte[]{1});
        assertThrows(IllegalArgumentException.class, () -> QrTransferCli.SendOptions.parse(new String[]{
                "send", "--input", input.toString(),
                "--min-page-size", "2000", "--max-page-size", "1000"
        }));
    }

    @Test
    void parsesBenchmarkDefaultsAndOverrides() {
        QrTransferCli.BenchmarkSendOptions send =
                QrTransferCli.BenchmarkSendOptions.parse(new String[]{"benchmark-send"});
        assertEquals(768, send.qrSize);
        assertEquals(384, send.minPageSize);
        assertEquals(2700, send.initialPageSize);
        assertEquals(2800, send.maxPageSize);
        assertTrue(send.pageLocalRecovery);
        assertTrue(send.compactEncoding);

        QrTransferCli.BenchmarkReceiveOptions receive =
                QrTransferCli.BenchmarkReceiveOptions.parse(new String[]{
                        "benchmark-receive", "--duration", "240", "--start-delay", "3"
                });
        assertEquals(240, receive.durationSeconds);
        assertEquals(3, receive.startDelaySeconds);
        assertEquals(0, receive.initialDelay);
        assertEquals(0, receive.minDelay);
        assertEquals(3, receive.downshiftAfterTimeouts);
    }

    @Test
    void parsesExplicitDelayAndTimeoutThresholdOverrides() {
        QrTransferCli.ReceiveOptions receive = QrTransferCli.ReceiveOptions.parse(new String[]{
                "receive", "--output", tempDir.resolve("custom.bin").toString(),
                "--profile", "fast", "--initial-delay", "20", "--min-delay", "10",
                "--max-delay", "100", "--downshift-after-timeouts", "5"
        });
        assertEquals(20, receive.initialDelay);
        assertEquals(10, receive.minDelay);
        assertEquals(100, receive.maxDelay);
        assertEquals(5, receive.downshiftAfterTimeouts);
    }

    @Test
    void rejectsInvalidBenchmarkDurationAndUnknownOption() {
        assertThrows(IllegalArgumentException.class,
                () -> QrTransferCli.BenchmarkReceiveOptions.parse(new String[]{
                        "benchmark-receive", "--duration", "29"
                }));
        assertThrows(IllegalArgumentException.class,
                () -> QrTransferCli.BenchmarkSendOptions.parse(new String[]{
                        "benchmark-send", "--unknown"
                }));
    }

    @Test
    void parsesResumeAndRestartAndRejectsLegacyCombination() throws Exception {
        Path input = tempDir.resolve("resume.zip");
        Files.write(input, new byte[]{1});
        QrTransferCli.SendOptions send = QrTransferCli.SendOptions.parse(new String[]{
                "send", "--input", input.toString(), "--profile", "fast", "--resume"
        });
        assertTrue(send.resume);

        QrTransferCli.ReceiveOptions receive = QrTransferCli.ReceiveOptions.parse(new String[]{
                "receive", "--output", tempDir.resolve("resume.out").toString(),
                "--resume", "--restart"
        });
        assertTrue(receive.resume);
        assertTrue(receive.restart);

        assertThrows(IllegalArgumentException.class, () -> QrTransferCli.SendOptions.parse(
                new String[]{"send", "--input", input.toString(), "--legacy", "--resume"}));
        assertThrows(IllegalArgumentException.class, () -> QrTransferCli.ReceiveOptions.parse(
                new String[]{"receive", "--output", "out", "--restart"}));
    }
}
