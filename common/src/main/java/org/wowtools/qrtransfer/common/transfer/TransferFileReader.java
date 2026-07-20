package org.wowtools.qrtransfer.common.transfer;

import com.google.protobuf.ByteString;
import org.wowtools.qrtransfer.common.pojo.FileHead;
import org.wowtools.qrtransfer.common.protobuf.QrPageProto;
import org.wowtools.qrtransfer.common.util.ByteDeque;
import org.wowtools.qrtransfer.common.util.Constant;
import org.wowtools.qrtransfer.common.util.Md5Util;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Iterator;

/** File-to-page conversion shared by desktop and command-line senders. */
public final class TransferFileReader {
    private static final int BUFFER_SIZE = 10 * 1024;
    private static final int MAX_MEMORY_SIZE = BUFFER_SIZE * 10;

    private TransferFileReader() {
    }

    public interface PageValidator {
        boolean canRead(byte[] bytes);
    }

    public static FileHead readHead(File file) {
        FileHead fileHead = new FileHead();
        fileHead.setMd5(Md5Util.getFileMD5(file));
        fileHead.setFileSize(file.length());
        return fileHead;
    }

    public static Iterator<QrPageProto.QrPagePb> readPages(File file, int initialPageSize, PageValidator validator) {
        if (initialPageSize < 32) {
            throw new IllegalArgumentException("二维码页大小必须至少为 32 字节");
        }
        try {
            return new PageIterator(new FileInputStream(file), initialPageSize, validator);
        } catch (IOException e) {
            throw new IllegalArgumentException("无法读取文件: " + file, e);
        }
    }

    private static final class PageIterator implements Iterator<QrPageProto.QrPagePb> {
        private final FileInputStream input;
        private final int initialPageSize;
        private final PageValidator validator;
        private final ByteDeque memory = new ByteDeque();
        private final byte[] buffer = new byte[BUFFER_SIZE];
        private int length = 0;
        private int currentPage = 0;
        private boolean closed;

        private PageIterator(FileInputStream input, int initialPageSize, PageValidator validator) {
            this.input = input;
            this.initialPageSize = initialPageSize;
            this.validator = validator;
        }

        @Override
        public synchronized boolean hasNext() {
            if (length != -1) {
                return true;
            }
            close();
            return memory.size() > 0 || !closed;
        }

        @Override
        public synchronized QrPageProto.QrPagePb next() {
            fillMemory();
            int pageSize = Math.min(initialPageSize, memory.size());
            byte[] bytes;
            if (validator == null) {
                bytes = readFromMemory(pageSize);
            } else {
                bytes = readValidated(pageSize);
            }
            QrPageProto.QrPagePb.Builder builder = QrPageProto.QrPagePb.newBuilder()
                    .setBytes(ByteString.copyFrom(bytes))
                    .setPageNum(currentPage++);
            if (length == -1 && memory.size() == 0) {
                builder.setMessage(Constant.Flag_End);
            }
            return builder.build();
        }

        private void fillMemory() {
            if (length == -1 || memory.size() >= MAX_MEMORY_SIZE) {
                return;
            }
            try {
                length = input.read(buffer);
                if (length > 0) {
                    for (int i = 0; i < length; i++) {
                        memory.addLast(buffer[i]);
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException("读取文件失败", e);
            }
        }

        private byte[] readValidated(int pageSize) {
            // EOF is represented by an empty protobuf page carrying the end flag.
            // The QR validator checks payload capacity, so validating zero payload
            // would incorrectly reject this otherwise valid terminal page.
            if (pageSize == 0) {
                return new byte[0];
            }
            int size = pageSize;
            while (true) {
                byte[] bytes = readFromMemory(size);
                if (validator.canRead(bytes)) {
                    return bytes;
                }
                restoreToMemory(bytes);
                if (size < 32) {
                    throw new IllegalStateException("二维码无法识别，页大小: " + size);
                }
                size /= 2;
            }
        }

        private byte[] readFromMemory(int size) {
            byte[] bytes = new byte[size];
            for (int i = 0; i < size; i++) {
                bytes[i] = memory.removeFirst();
            }
            return bytes;
        }

        private void restoreToMemory(byte[] bytes) {
            for (int i = bytes.length - 1; i >= 0; i--) {
                memory.addFirst(bytes[i]);
            }
        }

        private void close() {
            if (!closed) {
                try {
                    input.close();
                } catch (IOException e) {
                    throw new IllegalStateException("关闭文件失败", e);
                }
                closed = true;
            }
        }
    }
}
