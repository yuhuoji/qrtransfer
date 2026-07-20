package org.wowtools.qrtransfer.common.transfer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wowtools.qrtransfer.common.protobuf.QrPageProto;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransferFileReaderTest {
    @TempDir
    Path tempDir;

    @Test
    void preservesUtf8TextAcrossPages() throws Exception {
        byte[] expected = "第一行\\nemoji: 😀\\n{\"enabled\":true}".getBytes(StandardCharsets.UTF_8);
        Path input = tempDir.resolve("clipboard.txt");
        Files.write(input, expected);

        Iterator<QrPageProto.QrPagePb> pages = TransferFileReader.readPages(input.toFile(), 32, null);
        List<Byte> output = new ArrayList<>();
        int pageNumber = 0;
        while (pages.hasNext()) {
            QrPageProto.QrPagePb page = pages.next();
            assertEquals(pageNumber++, page.getPageNum());
            for (byte value : page.getBytes().toByteArray()) {
                output.add(value);
            }
            if ("end".equals(page.getMessage())) {
                break;
            }
        }
        byte[] actual = new byte[output.size()];
        for (int i = 0; i < output.size(); i++) {
            actual[i] = output.get(i);
        }
        assertArrayEquals(expected, actual);
    }

    @Test
    void emitsEndPageForEmptyText() throws Exception {
        Path input = tempDir.resolve("empty.txt");
        Files.writeString(input, "", StandardCharsets.UTF_8);

        Iterator<QrPageProto.QrPagePb> pages = TransferFileReader.readPages(input.toFile(), 32, null);
        QrPageProto.QrPagePb page = pages.next();
        assertEquals(0, page.getPageNum());
        assertEquals(0, page.getBytes().size());
        assertEquals("end", page.getMessage());
    }

    @Test
    void doesNotValidateEmptyTerminalPayload() throws Exception {
        Path input = tempDir.resolve("exact-page.txt");
        Files.writeString(input, "12345678901234567890123456789012", StandardCharsets.UTF_8);
        AtomicInteger validationCalls = new AtomicInteger();

        Iterator<QrPageProto.QrPagePb> pages = TransferFileReader.readPages(input.toFile(), 32, bytes -> {
            assertTrue(bytes.length > 0);
            validationCalls.incrementAndGet();
            return true;
        });

        QrPageProto.QrPagePb dataPage = pages.next();
        QrPageProto.QrPagePb endPage = pages.next();
        assertEquals(32, dataPage.getBytes().size());
        assertEquals(0, endPage.getBytes().size());
        assertEquals("end", endPage.getMessage());
        assertEquals(1, validationCalls.get());
    }
}
