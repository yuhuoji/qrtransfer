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
import java.util.Base64;
import java.util.Map;

/**
 * QR codec for V2 frames. A distinct prefix plus URL-safe Base64 keeps arbitrary
 * binary payloads stable across ZXing's text-oriented writer and decoder.
 */
public final class BinaryQRCodeUtil {
    private static final String PREFIX = "QRT2:";
    private static final Map<EncodeHintType, Object> ENCODE_HINTS = Map.of(
            EncodeHintType.CHARACTER_SET, "UTF-8",
            EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.L,
            EncodeHintType.MARGIN, 2
    );
    private static final Map<DecodeHintType, Object> DECODE_HINTS = Map.of(
            DecodeHintType.CHARACTER_SET, "UTF-8",
            DecodeHintType.TRY_HARDER, false
    );

    private BinaryQRCodeUtil() {
    }

    public static void generate(byte[] bytes, BufferedImage image) throws Exception {
        String content = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
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
            String text = result.getText();
            if (!text.startsWith(PREFIX)) {
                return null;
            }
            return Base64.getUrlDecoder().decode(text.substring(PREFIX.length()));
        } catch (Exception e) {
            return null;
        }
    }
}
