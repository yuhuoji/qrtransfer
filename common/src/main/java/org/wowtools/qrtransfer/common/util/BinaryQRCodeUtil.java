package org.wowtools.qrtransfer.common.util;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.Result;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * QR codec for V2 frames. ISO-8859-1 maps every Java character to exactly one
 * byte, avoiding the legacy Base64 expansion while keeping ZXing in byte mode.
 */
public final class BinaryQRCodeUtil {
    private static final Map<EncodeHintType, Object> ENCODE_HINTS = Map.of(
            EncodeHintType.CHARACTER_SET, StandardCharsets.ISO_8859_1.name(),
            EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.L,
            EncodeHintType.MARGIN, 2
    );
    private static final Map<DecodeHintType, Object> DECODE_HINTS = Map.of(
            DecodeHintType.CHARACTER_SET, StandardCharsets.ISO_8859_1.name(),
            DecodeHintType.TRY_HARDER, false
    );

    private BinaryQRCodeUtil() {
    }

    public static void generate(byte[] bytes, BufferedImage image) throws Exception {
        String content = new String(bytes, StandardCharsets.ISO_8859_1);
        int width = image.getWidth();
        BitMatrix matrix = new QRCodeWriter().encode(
                content, BarcodeFormat.QR_CODE, width, width, ENCODE_HINTS);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < width; y++) {
                image.setRGB(x, y, matrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF);
            }
        }
    }

    public static byte[] parse(BufferedImage image) {
        try {
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(
                    new BufferedImageLuminanceSource(image)));
            Result result = new MultiFormatReader().decode(bitmap, DECODE_HINTS);
            return result.getText().getBytes(StandardCharsets.ISO_8859_1);
        } catch (Exception e) {
            return null;
        }
    }
}
