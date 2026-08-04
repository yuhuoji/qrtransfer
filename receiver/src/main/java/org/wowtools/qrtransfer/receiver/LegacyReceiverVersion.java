package org.wowtools.qrtransfer.receiver;

/** Identifies the original-protocol receiver with watermark retry support. */
public final class LegacyReceiverVersion {
    public static final String VERSION = "1.0.1";
    public static final String DISPLAY_NAME = "receiver (legacy watermark-fix v" + VERSION + ")";

    private LegacyReceiverVersion() {
    }
}
