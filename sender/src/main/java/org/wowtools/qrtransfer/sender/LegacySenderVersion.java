package org.wowtools.qrtransfer.sender;

/** Identifies the original-protocol sender with the busy-lock fix applied. */
public final class LegacySenderVersion {
    public static final String VERSION = "1.0.1";
    public static final String DISPLAY_NAME = "sender (legacy lock-fix v" + VERSION + ")";

    private LegacySenderVersion() {
    }
}
