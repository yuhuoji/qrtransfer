package org.wowtools.qrtransfer.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdaptiveTuningTest {
    @Test
    void acceleratedProfilesUseExpectedBounds() {
        assertEquals(1600, TransferProfile.BALANCED.initialPageSize);
        assertEquals(2100, TransferProfile.BALANCED.maxPageSize);
        assertEquals(0, TransferProfile.BALANCED.initialDelay);
        assertEquals(0, TransferProfile.BALANCED.minDelay);
        assertEquals(0, TransferProfile.BALANCED.maxDelay);
        assertEquals(1200, TransferProfile.BALANCED.frameTimeout);
        assertEquals(3, TransferProfile.BALANCED.downshiftAfterTimeouts);
        assertTrue(TransferProfile.BALANCED.pageLocalRecovery);

        assertEquals(1800, TransferProfile.FAST.initialPageSize);
        assertEquals(2150, TransferProfile.FAST.maxPageSize);
        assertEquals(0, TransferProfile.FAST.initialDelay);
        assertEquals(0, TransferProfile.FAST.minDelay);
        assertEquals(0, TransferProfile.FAST.maxDelay);
        assertEquals(800, TransferProfile.FAST.frameTimeout);
        assertEquals(3, TransferProfile.FAST.downshiftAfterTimeouts);
        assertTrue(TransferProfile.FAST.pageLocalRecovery);

        assertEquals(1, TransferProfile.SAFE.downshiftAfterTimeouts);
        assertTrue(!TransferProfile.SAFE.pageLocalRecovery);
    }

    @Test
    void increasesAfterSuccessWindowAndDecreasesOnFailure() {
        AdaptivePageSizer sizer = new AdaptivePageSizer(512, 2000, 2800, null);
        for (int i = 0; i < 4; i++) {
            sizer.onSuccess();
        }
        assertEquals(2128, sizer.current());
        assertTrue(sizer.onFailure());
        assertEquals(1489, sizer.current());
    }

    @Test
    void fastProfileCanRecoverBelowFormerMinimum() {
        AdaptivePageSizer sizer = new AdaptivePageSizer(384, 768, 2100, null);
        assertTrue(sizer.onFailure());
        assertEquals(537, sizer.current());
        assertTrue(sizer.onFailure());
        assertEquals(384, sizer.current());
    }

    @Test
    void pageLocalFailureRestoresTargetOnNextPage() {
        AdaptivePageSizer sizer = new AdaptivePageSizer(384, 1800, 2150, null, true);
        assertTrue(sizer.onFailure());
        assertEquals(1260, sizer.current());
        assertEquals(1800, sizer.target());

        sizer.onSuccess();

        assertEquals(1800, sizer.current());
        assertEquals(1800, sizer.target());
    }

    @Test
    void delayMovesWithinBounds() {
        AdaptiveDelay delay = new AdaptiveDelay(200, 80, 800);
        delay.onSuccess(100);
        assertTrue(delay.current() < 200);
        for (int i = 0; i < 10; i++) {
            delay.onFailure();
        }
        assertEquals(800, delay.current());
    }

    @Test
    void zeroDelayProfilesRemainAtZero() {
        AdaptiveDelay delay = new AdaptiveDelay(0, 0, 0);
        delay.onSuccess(100);
        assertEquals(0, delay.current());
        delay.onFailure();
        assertEquals(0, delay.current());
    }
}
