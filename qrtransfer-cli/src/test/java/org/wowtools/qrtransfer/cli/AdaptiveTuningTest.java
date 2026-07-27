package org.wowtools.qrtransfer.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdaptiveTuningTest {
    @Test
    void acceleratedProfilesUseExpectedBounds() {
        assertEquals(1600, TransferProfile.BALANCED.initialPageSize);
        assertEquals(2100, TransferProfile.BALANCED.maxPageSize);
        assertEquals(150, TransferProfile.BALANCED.initialDelay);
        assertEquals(60, TransferProfile.BALANCED.minDelay);
        assertEquals(600, TransferProfile.BALANCED.maxDelay);
        assertEquals(1200, TransferProfile.BALANCED.frameTimeout);

        assertEquals(1800, TransferProfile.FAST.initialPageSize);
        assertEquals(2150, TransferProfile.FAST.maxPageSize);
        assertEquals(90, TransferProfile.FAST.initialDelay);
        assertEquals(30, TransferProfile.FAST.minDelay);
        assertEquals(350, TransferProfile.FAST.maxDelay);
        assertEquals(800, TransferProfile.FAST.frameTimeout);
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
    void acceleratedDelaysRecoverWithinNewBounds() {
        AdaptiveDelay balanced = new AdaptiveDelay(150, 60, 600);
        balanced.onSuccess(60);
        assertTrue(balanced.current() >= 60);
        for (int i = 0; i < 10; i++) {
            balanced.onFailure();
        }
        assertEquals(600, balanced.current());

        AdaptiveDelay fast = new AdaptiveDelay(90, 30, 350);
        fast.onSuccess(30);
        assertTrue(fast.current() >= 30);
        for (int i = 0; i < 10; i++) {
            fast.onFailure();
        }
        assertEquals(350, fast.current());
    }
}
