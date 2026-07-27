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
        assertEquals(2150, options.maxPageSize);
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
}
