package org.wowtools.qrtransfer.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wowtools.qrtransfer.common.transfer.V2Frame;
import org.wowtools.qrtransfer.common.util.Md5Util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ResumeCheckpointTest {
    @TempDir
    Path tempDir;

    @Test
    void persistsVerifiedBlockAndRollsBackUncommittedTail() throws Exception {
        byte[] sourceBytes = new byte[(int) ResumeCheckpoint.DEFAULT_BLOCK_SIZE + 123];
        Arrays.fill(sourceBytes, (byte) 7);
        Path source = tempDir.resolve("source.bin");
        Files.write(source, sourceBytes);
        V2Frame header = V2Frame.header(1L, sourceBytes.length,
                Md5Util.getFileMD5(source.toFile()), false);
        Path output = tempDir.resolve("output.bin");

        ResumeCheckpoint checkpoint = ResumeCheckpoint.prepare(output, header, false);
        Files.write(checkpoint.part, sourceBytes, StandardOpenOption.TRUNCATE_EXISTING);
        checkpoint = checkpoint.commitAvailable(header, sourceBytes.length);

        ResumeCheckpoint restored = ResumeCheckpoint.prepare(output, header, false);
        assertEquals(ResumeCheckpoint.DEFAULT_BLOCK_SIZE, restored.committedOffset);
        assertEquals(123, restored.rolledBackBytes);
        assertEquals(ResumeCheckpoint.DEFAULT_BLOCK_SIZE, Files.size(restored.part));
    }

    @Test
    void refusesCheckpointForChangedSource() throws Exception {
        Path output = tempDir.resolve("changed.bin");
        V2Frame original = V2Frame.header(1L, 10,
                "00000000000000000000000000000000", false);
        ResumeCheckpoint.prepare(output, original, false);
        V2Frame changed = V2Frame.header(2L, 10,
                "11111111111111111111111111111111", false);

        assertThrows(Exception.class, () -> ResumeCheckpoint.prepare(output, changed, false));
    }
}
