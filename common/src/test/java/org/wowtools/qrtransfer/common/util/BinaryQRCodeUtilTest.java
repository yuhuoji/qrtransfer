package org.wowtools.qrtransfer.common.util;

import org.junit.jupiter.api.Test;
import org.wowtools.qrtransfer.common.transfer.V2Frame;

import java.awt.image.BufferedImage;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    @Test
    void compactEncodingRoundTripsDenseArbitraryBytes() throws Exception {
        Random random = new Random(20260729L);
        for (int size : new int[]{1, 256, 1600, 2600, 2700, 2750, 2800}) {
            byte[] input = new byte[size];
            random.nextBytes(input);
            BufferedImage image = new BufferedImage(768, 768, BufferedImage.TYPE_INT_RGB);
            BinaryQRCodeUtil.generateCompact(input, image);
            assertArrayEquals(input, BinaryQRCodeUtil.parse(image),
                    "紧凑编码往返失败，size=" + size);
        }
    }

    @Test
    void compactEncodingRoundTripsFastV2Page() throws Exception {
        byte[] payload = new byte[2800];
        new Random(20260730L).nextBytes(payload);
        byte[] frame = V2Frame.data(42L, 7, 19_250L, payload, false).encode();
        BufferedImage image = new BufferedImage(768, 768, BufferedImage.TYPE_INT_RGB);
        assertThrows(Exception.class, () -> BinaryQRCodeUtil.generate(frame, image),
                "2800B V2 页不应再能通过 Base64 编码塞入单个二维码");
        BinaryQRCodeUtil.generateCompact(frame, image);
        assertArrayEquals(frame, BinaryQRCodeUtil.parse(image));
    }
}
