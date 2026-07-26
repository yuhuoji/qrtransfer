package org.wowtools.qrtransfer.common.util;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class BinaryQRCodeUtilTest {
    @Test
    void roundTripsEveryByteValue() throws Exception {
        byte[] input = new byte[256];
        for (int i = 0; i < input.length; i++) {
            input[i] = (byte) i;
        }
        BufferedImage image = new BufferedImage(512, 512, BufferedImage.TYPE_INT_RGB);
        BinaryQRCodeUtil.generate(input, image);
        assertArrayEquals(input, BinaryQRCodeUtil.parse(image));
    }

    @Test
    void roundTripsDenseBinaryPayloadAtRecommendedSize() throws Exception {
        byte[] input = new byte[2500];
        for (int i = 0; i < input.length; i++) {
            input[i] = (byte) (i * 31 + 17);
        }
        BufferedImage image = new BufferedImage(512, 512, BufferedImage.TYPE_INT_RGB);
        BinaryQRCodeUtil.generate(input, image);
        assertArrayEquals(input, BinaryQRCodeUtil.parse(image));
    }
}
