package org.wowtools.qrtransfer.common.util;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.Random;

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
        byte[] input = new byte[2000];
        for (int i = 0; i < input.length; i++) {
            input[i] = (byte) (i * 31 + 17);
        }
        BufferedImage image = new BufferedImage(512, 512, BufferedImage.TYPE_INT_RGB);
        BinaryQRCodeUtil.generate(input, image);
        assertArrayEquals(input, BinaryQRCodeUtil.parse(image));
    }

    @Test
    void roundTripsRandomBinaryPayloads() throws Exception {
        Random random = new Random(20260726L);
        for (int size : new int[]{1, 127, 512, 1000, 1600, 2000}) {
            for (int sample = 0; sample < 5; sample++) {
                byte[] input = new byte[size];
                random.nextBytes(input);
                BufferedImage image = new BufferedImage(512, 512, BufferedImage.TYPE_INT_RGB);
                BinaryQRCodeUtil.generate(input, image);
                assertArrayEquals(input, BinaryQRCodeUtil.parse(image),
                        "随机二进制往返失败，size=" + size + "，sample=" + sample);
            }
        }
    }
}
