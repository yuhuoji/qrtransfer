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
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;

/**
 * QR codec for V2 frames. It decodes both the compatible Base64 representation
 * and the compact single-byte representation used by the speed-first profile.
 */
public final class BinaryQRCodeUtil {
    private static final int WATERMARK_THRESHOLD = 128;
    private static final String PREFIX = "QRT2:";
    private static final String COMPACT_PREFIX = "QRT2R:";
    private static final byte[] COMPACT_PREFIX_BYTES =
            COMPACT_PREFIX.getBytes(StandardCharsets.ISO_8859_1);
    private static final Map<EncodeHintType, Object> ENCODE_HINTS = Map.of(
            EncodeHintType.CHARACTER_SET, "UTF-8",
            EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.L,
            EncodeHintType.MARGIN, 2
    );
    private static final Map<EncodeHintType, Object> COMPACT_ENCODE_HINTS = Map.of(
            EncodeHintType.CHARACTER_SET, "ISO-8859-1",
            EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.L,
            EncodeHintType.MARGIN, 2
    );
    private static final Map<EncodeHintType, Object> ROBUST_ENCODE_HINTS = Map.of(
            EncodeHintType.CHARACTER_SET, "UTF-8",
            EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.H,
            EncodeHintType.MARGIN, 4
    );
    private static final Map<DecodeHintType, Object> DECODE_HINTS = Map.of(
            DecodeHintType.CHARACTER_SET, "UTF-8",
            DecodeHintType.TRY_HARDER, false
    );
    private static final Map<DecodeHintType, Object> HARD_DECODE_HINTS = Map.of(
            DecodeHintType.CHARACTER_SET, "UTF-8",
            DecodeHintType.TRY_HARDER, true
    );

    private BinaryQRCodeUtil() {
    }

    public static void generate(byte[] bytes, BufferedImage image) throws Exception {
        String content = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        generateContent(content, image, ENCODE_HINTS);
    }

    /** Generates a low-capacity control QR with stronger correction and a wider quiet zone. */
    public static void generateRobust(byte[] bytes, BufferedImage image) throws Exception {
        String content = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        generateContent(content, image, ROBUST_ENCODE_HINTS);
    }

    /**
     * Encodes one byte as one QR byte instead of expanding the frame through Base64.
     * The distinct prefix lets receivers remain compatible with the original V2 codec.
     */
    public static void generateCompact(byte[] bytes, BufferedImage image) throws Exception {
        String content = COMPACT_PREFIX + new String(bytes, StandardCharsets.ISO_8859_1);
        generateContent(content, image, COMPACT_ENCODE_HINTS);
    }

    private static void generateContent(String content, BufferedImage image,
                                        Map<EncodeHintType, Object> hints) throws Exception {
        int width = image.getWidth();
        BitMatrix matrix = new QRCodeWriter().encode(
                content, BarcodeFormat.QR_CODE, width, width, hints);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < width; y++) {
                image.setRGB(x, y, matrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF);
            }
        }
    }

    public static byte[] parse(BufferedImage image) {
        try {
            Result result;
            try {
                result = decode(image, DECODE_HINTS);
            } catch (Exception first) {
                try {
                    result = decode(image, HARD_DECODE_HINTS);
                } catch (Exception second) {
                    // Remote-desktop watermarks are often light gray. Hybrid binarization can
                    // turn those strokes into QR modules, so retry with only dark pixels kept.
                    result = decode(highContrast(image), HARD_DECODE_HINTS);
                }
            }
            String text = result.getText();
            if (text.startsWith(PREFIX)) {
                return Base64.getUrlDecoder().decode(text.substring(PREFIX.length()));
            }
            if (text.startsWith(COMPACT_PREFIX)) {
                byte[] raw = result.getRawBytes();
                if (startsWith(raw, COMPACT_PREFIX_BYTES)) {
                    return Arrays.copyOfRange(raw, COMPACT_PREFIX_BYTES.length, raw.length);
                }
                return text.substring(COMPACT_PREFIX.length())
                        .getBytes(StandardCharsets.ISO_8859_1);
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static Result decode(BufferedImage image, Map<DecodeHintType, Object> hints)
            throws Exception {
        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(
                new BufferedImageLuminanceSource(image)));
        return new MultiFormatReader().decode(bitmap, hints);
    }

    static BufferedImage highContrast(BufferedImage source) {
        BufferedImage output = new BufferedImage(
                source.getWidth(), source.getHeight(), BufferedImage.TYPE_BYTE_BINARY);
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                int rgb = source.getRGB(x, y);
                int red = (rgb >>> 16) & 0xff;
                int green = (rgb >>> 8) & 0xff;
                int blue = rgb & 0xff;
                int luminance = (299 * red + 587 * green + 114 * blue) / 1000;
                output.setRGB(x, y,
                        luminance < WATERMARK_THRESHOLD ? 0xff000000 : 0xffffffff);
            }
        }
        return output;
    }

    private static boolean startsWith(byte[] value, byte[] prefix) {
        if (value == null || value.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (value[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
