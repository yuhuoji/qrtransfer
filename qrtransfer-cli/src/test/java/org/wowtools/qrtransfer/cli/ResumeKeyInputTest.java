package org.wowtools.qrtransfer.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResumeKeyInputTest {
    @Test
    void ignoresRepeatedBeginKeyUntilCheckpointInputIsArmed() {
        AdaptiveSenderWindow.ResumeKeyInput input = new AdaptiveSenderWindow.ResumeKeyInput();

        assertFalse(input.append('6'));
        assertFalse(input.append('6'));
        assertTrue(input.isEmpty());

        input.arm();
        assertTrue(input.append('4'));
        assertTrue(input.append('6'));
        assertEquals("46", input.value());

        input.reset();
        assertFalse(input.append('6'));
        assertTrue(input.isEmpty());
    }
}
