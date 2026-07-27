package org.wowtools.qrtransfer.common.transfer;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V2FrameTest {
    @Test
    void roundTripsHeaderAndBinaryData() {
        V2Frame header = V2Frame.header(42L, 1234L,
                "0123456789abcdef0123456789abcdef", true);
        V2Frame decodedHeader = V2Frame.decode(header.encode());
        assertEquals(header, decodedHeader);
        assertTrue(decodedHeader.isText());

        byte[] bytes = new byte[256];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) i;
        }
        V2Frame page = V2Frame.data(42L, 7, 999L, bytes, true);
        V2Frame decodedPage = V2Frame.decode(page.encode());
        assertEquals(page, decodedPage);
        assertArrayEquals(bytes, decodedPage.getPayload());
        assertTrue(decodedPage.isEnd());
    }

    @Test
    void rejectsCorruptedFrame() {
        byte[] encoded = V2Frame.data(1L, 0, 0, new byte[]{1, 2, 3}, false).encode();
        encoded[encoded.length / 2] ^= 0x01;
        assertThrows(IllegalArgumentException.class, () -> V2Frame.decode(encoded));
    }

    @Test
    void roundTripsBenchmarkHeaderWithoutLookingLikeAFileHeader() {
        V2Frame header = V2Frame.benchmarkHeader(77L, 640, 384, 1800, 2100);

        V2Frame decoded = V2Frame.decode(header.encode());

        assertEquals(header, decoded);
        assertTrue(decoded.isBenchmarkHeader());
        assertFalse(decoded.isHeader());
        assertEquals(640, decoded.getQrSize());
        assertEquals(384, decoded.getMinPageSize());
        assertEquals(1800, decoded.getInitialPageSize());
        assertEquals(2100, decoded.getMaxPageSize());
    }

    @Test
    void rejectsInvalidBenchmarkBounds() {
        assertThrows(IllegalArgumentException.class,
                () -> V2Frame.benchmarkHeader(1L, 640, 1000, 900, 2100));
    }
}
