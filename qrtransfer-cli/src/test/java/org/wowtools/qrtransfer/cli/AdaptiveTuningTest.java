package org.wowtools.qrtransfer.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdaptiveTuningTest {
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
}
