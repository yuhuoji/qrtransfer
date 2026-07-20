package org.wowtools.qrtransfer.common.transfer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Strict UTF-8 validation for clipboard bridge payloads. */
public final class Utf8Text {
    private Utf8Text() {
    }

    public static void validate(Path file) throws IOException {
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(Files.readAllBytes(file)));
        } catch (CharacterCodingException e) {
            throw new IOException("文件不是有效 UTF-8 文本: " + file, e);
        }
    }
}
