package org.wowtools.qrtransfer.common.util;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class QRCodeUtilTest {
    @Test
    void highContrastFallbackReadsLegacyQrThroughLightWatermark() throws Exception {
        byte[] input = new byte[900];
        for (int i = 0; i < input.length; i++) {
            input[i] = (byte) (i * 31 + 17);
        }
        BufferedImage image = new BufferedImage(512, 512, BufferedImage.TYPE_INT_RGB);
        QRCodeUtil.generateQRCodeImage(input, image);

        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setColor(new Color(64, 64, 64, 64));
        for (int position = -250; position < 700; position += 48) {
            graphics.drawLine(position, 0, position + 500, 512);
        }
        graphics.dispose();

        assertArrayEquals(input, QRCodeUtil.parseQRCodeImageHighContrast(image));
    }
}
