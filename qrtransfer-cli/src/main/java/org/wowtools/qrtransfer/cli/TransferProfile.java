package org.wowtools.qrtransfer.cli;

enum TransferProfile {
    SAFE("safe", 512, 512, 1000, 1600, 300, 200, 1200, 2500, 1, false),
    BALANCED("balanced", 512, 512, 1600, 2100, 0, 0, 0, 1200, 3, true),
    FAST("fast", 640, 384, 1800, 2150, 0, 0, 0, 800, 3, true);

    final String cliName;
    final int qrSize;
    final int minPageSize;
    final int initialPageSize;
    final int maxPageSize;
    final long initialDelay;
    final long minDelay;
    final long maxDelay;
    final long frameTimeout;
    final int downshiftAfterTimeouts;
    final boolean pageLocalRecovery;

    TransferProfile(String cliName, int qrSize, int minPageSize, int initialPageSize, int maxPageSize,
                    long initialDelay, long minDelay, long maxDelay, long frameTimeout,
                    int downshiftAfterTimeouts, boolean pageLocalRecovery) {
        this.cliName = cliName;
        this.qrSize = qrSize;
        this.minPageSize = minPageSize;
        this.initialPageSize = initialPageSize;
        this.maxPageSize = maxPageSize;
        this.initialDelay = initialDelay;
        this.minDelay = minDelay;
        this.maxDelay = maxDelay;
        this.frameTimeout = frameTimeout;
        this.downshiftAfterTimeouts = downshiftAfterTimeouts;
        this.pageLocalRecovery = pageLocalRecovery;
    }

    static TransferProfile parse(String value) {
        for (TransferProfile profile : values()) {
            if (profile.cliName.equalsIgnoreCase(value)) {
                return profile;
            }
        }
        throw new IllegalArgumentException("未知 profile: " + value + "，可选 safe|balanced|fast");
    }
}
