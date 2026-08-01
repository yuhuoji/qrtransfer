package org.wowtools.qrtransfer.common.transfer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.zip.CRC32C;

/** Versioned binary envelope used by the adaptive transfer mode. */
public final class V2Frame {
    public static final int MAGIC = 0x51525432; // QRT2
    public static final byte VERSION = 2;
    public static final byte TYPE_HEADER = 1;
    public static final byte TYPE_DATA = 2;
    public static final byte TYPE_BENCHMARK_HEADER = 3;
    public static final byte TYPE_RESUME_READY = 4;
    public static final byte TYPE_RESUME_CONFIRM = 5;
    public static final short FLAG_END = 1;
    public static final short FLAG_TEXT = 2;
    private static final int MD5_BYTES = 16;
    private static final int COMMON_BYTES = 4 + 1 + 1 + 2 + 8;
    private static final int HEADER_BYTES_WITHOUT_CRC = COMMON_BYTES + 8 + MD5_BYTES;
    private static final int DATA_BYTES_WITHOUT_PAYLOAD_OR_CRC = COMMON_BYTES + 4 + 8 + 4;
    private static final int BENCHMARK_BYTES_WITHOUT_CRC = COMMON_BYTES + 4 + 4 + 4 + 4;
    private static final int RESUME_READY_BYTES_WITHOUT_CRC = COMMON_BYTES;
    private static final int RESUME_CONFIRM_BYTES_WITHOUT_CRC = COMMON_BYTES + 8 + MD5_BYTES;

    private final byte type;
    private final short flags;
    private final long sessionId;
    private final long fileSize;
    private final byte[] md5;
    private final int pageNumber;
    private final long offset;
    private final byte[] payload;
    private final int qrSize;
    private final int minPageSize;
    private final int initialPageSize;
    private final int maxPageSize;

    private V2Frame(byte type, short flags, long sessionId, long fileSize, byte[] md5,
                    int pageNumber, long offset, byte[] payload, int qrSize,
                    int minPageSize, int initialPageSize, int maxPageSize) {
        this.type = type;
        this.flags = flags;
        this.sessionId = sessionId;
        this.fileSize = fileSize;
        this.md5 = md5 == null ? null : md5.clone();
        this.pageNumber = pageNumber;
        this.offset = offset;
        this.payload = payload == null ? null : payload.clone();
        this.qrSize = qrSize;
        this.minPageSize = minPageSize;
        this.initialPageSize = initialPageSize;
        this.maxPageSize = maxPageSize;
    }

    public static V2Frame header(long sessionId, long fileSize, String md5Hex, boolean text) {
        return new V2Frame(TYPE_HEADER, text ? FLAG_TEXT : 0, sessionId, fileSize,
                hexToBytes(md5Hex), -1, 0, null, 0, 0, 0, 0);
    }

    public static V2Frame data(long sessionId, int pageNumber, long offset, byte[] payload, boolean end) {
        return new V2Frame(TYPE_DATA, end ? FLAG_END : 0, sessionId, -1, null,
                pageNumber, offset, payload, 0, 0, 0, 0);
    }

    public static V2Frame benchmarkHeader(long sessionId, int qrSize, int minPageSize,
                                          int initialPageSize, int maxPageSize) {
        if (qrSize < 128 || minPageSize < 32 || maxPageSize < minPageSize
                || initialPageSize < minPageSize || initialPageSize > maxPageSize) {
            throw new IllegalArgumentException("测速参数无效");
        }
        return new V2Frame(TYPE_BENCHMARK_HEADER, (short) 0, sessionId, -1, null,
                -1, 0, null, qrSize, minPageSize, initialPageSize, maxPageSize);
    }

    public static V2Frame resumeReady(long sessionId) {
        return new V2Frame(TYPE_RESUME_READY, (short) 0, sessionId, -1, null,
                -1, 0, null, 0, 0, 0, 0);
    }

    public static V2Frame resumeConfirm(long sessionId, long offset, String prefixMd5Hex) {
        if (offset < 0) {
            throw new IllegalArgumentException("恢复偏移无效");
        }
        return new V2Frame(TYPE_RESUME_CONFIRM, (short) 0, sessionId, -1,
                hexToBytes(prefixMd5Hex), -1, offset, null, 0, 0, 0, 0);
    }

    public byte[] encode() {
        int bodySize;
        if (type == TYPE_HEADER) {
            bodySize = HEADER_BYTES_WITHOUT_CRC;
        } else if (type == TYPE_BENCHMARK_HEADER) {
            bodySize = BENCHMARK_BYTES_WITHOUT_CRC;
        } else if (type == TYPE_DATA) {
            bodySize = DATA_BYTES_WITHOUT_PAYLOAD_OR_CRC + payload.length;
        } else if (type == TYPE_RESUME_READY) {
            bodySize = RESUME_READY_BYTES_WITHOUT_CRC;
        } else if (type == TYPE_RESUME_CONFIRM) {
            bodySize = RESUME_CONFIRM_BYTES_WITHOUT_CRC;
        } else {
            throw new IllegalStateException("未知帧类型: " + type);
        }
        ByteBuffer buffer = ByteBuffer.allocate(bodySize + Integer.BYTES).order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(MAGIC).put(VERSION).put(type).putShort(flags).putLong(sessionId);
        if (type == TYPE_HEADER) {
            buffer.putLong(fileSize).put(md5);
        } else if (type == TYPE_BENCHMARK_HEADER) {
            buffer.putInt(qrSize).putInt(minPageSize).putInt(initialPageSize).putInt(maxPageSize);
        } else if (type == TYPE_DATA) {
            buffer.putInt(pageNumber).putLong(offset).putInt(payload.length).put(payload);
        } else if (type == TYPE_RESUME_READY) {
            // Common header is the complete payload for this control frame.
        } else if (type == TYPE_RESUME_CONFIRM) {
            buffer.putLong(offset).put(md5);
        } else {
            throw new IllegalStateException("未知帧类型: " + type);
        }
        int crcOffset = buffer.position();
        buffer.putInt(crc32c(buffer.array(), 0, crcOffset));
        return buffer.array();
    }

    public static V2Frame decode(byte[] encoded) {
        if (encoded == null || encoded.length < COMMON_BYTES + Integer.BYTES) {
            throw new IllegalArgumentException("V2 帧过短");
        }
        int expectedCrc = ByteBuffer.wrap(encoded, encoded.length - Integer.BYTES, Integer.BYTES)
                .order(ByteOrder.BIG_ENDIAN).getInt();
        int actualCrc = crc32c(encoded, 0, encoded.length - Integer.BYTES);
        if (expectedCrc != actualCrc) {
            throw new IllegalArgumentException("V2 帧 CRC32C 校验失败");
        }
        ByteBuffer buffer = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN);
        if (buffer.getInt() != MAGIC || buffer.get() != VERSION) {
            throw new IllegalArgumentException("不是受支持的 V2 帧");
        }
        byte type = buffer.get();
        short flags = buffer.getShort();
        long sessionId = buffer.getLong();
        if (type == TYPE_HEADER) {
            if (encoded.length != HEADER_BYTES_WITHOUT_CRC + Integer.BYTES) {
                throw new IllegalArgumentException("V2 文件头长度错误");
            }
            long fileSize = buffer.getLong();
            if (fileSize < 0) {
                throw new IllegalArgumentException("V2 文件大小无效");
            }
            byte[] md5 = new byte[MD5_BYTES];
            buffer.get(md5);
            return new V2Frame(type, flags, sessionId, fileSize, md5, -1, 0, null,
                    0, 0, 0, 0);
        }
        if (type == TYPE_BENCHMARK_HEADER) {
            if (encoded.length != BENCHMARK_BYTES_WITHOUT_CRC + Integer.BYTES) {
                throw new IllegalArgumentException("V2 测速头长度错误");
            }
            int qrSize = buffer.getInt();
            int minPageSize = buffer.getInt();
            int initialPageSize = buffer.getInt();
            int maxPageSize = buffer.getInt();
            return benchmarkHeader(sessionId, qrSize, minPageSize, initialPageSize, maxPageSize);
        }
        if (type == TYPE_DATA) {
            int pageNumber = buffer.getInt();
            long offset = buffer.getLong();
            int length = buffer.getInt();
            int expectedLength = DATA_BYTES_WITHOUT_PAYLOAD_OR_CRC + length + Integer.BYTES;
            if (pageNumber < 0 || offset < 0 || length < 0 || encoded.length != expectedLength) {
                throw new IllegalArgumentException("V2 数据页字段无效");
            }
            byte[] payload = new byte[length];
            buffer.get(payload);
            return new V2Frame(type, flags, sessionId, -1, null, pageNumber, offset, payload,
                    0, 0, 0, 0);
        }
        if (type == TYPE_RESUME_READY) {
            if (encoded.length != RESUME_READY_BYTES_WITHOUT_CRC + Integer.BYTES) {
                throw new IllegalArgumentException("恢复就绪帧长度错误");
            }
            return resumeReady(sessionId);
        }
        if (type == TYPE_RESUME_CONFIRM) {
            if (encoded.length != RESUME_CONFIRM_BYTES_WITHOUT_CRC + Integer.BYTES) {
                throw new IllegalArgumentException("恢复确认帧长度错误");
            }
            long offset = buffer.getLong();
            byte[] prefixMd5 = new byte[MD5_BYTES];
            buffer.get(prefixMd5);
            return new V2Frame(type, flags, sessionId, -1, prefixMd5, -1, offset, null,
                    0, 0, 0, 0);
        }
        throw new IllegalArgumentException("未知 V2 帧类型: " + type);
    }

    public byte getType() {
        return type;
    }

    public boolean isHeader() {
        return type == TYPE_HEADER;
    }

    public boolean isData() {
        return type == TYPE_DATA;
    }

    public boolean isBenchmarkHeader() {
        return type == TYPE_BENCHMARK_HEADER;
    }

    public boolean isResumeReady() {
        return type == TYPE_RESUME_READY;
    }

    public boolean isResumeConfirm() {
        return type == TYPE_RESUME_CONFIRM;
    }

    public boolean isEnd() {
        return (flags & FLAG_END) != 0;
    }

    public boolean isText() {
        return (flags & FLAG_TEXT) != 0;
    }

    public long getSessionId() {
        return sessionId;
    }

    public long getFileSize() {
        return fileSize;
    }

    public String getMd5Hex() {
        return bytesToHex(md5);
    }

    public int getPageNumber() {
        return pageNumber;
    }

    public long getOffset() {
        return offset;
    }

    public byte[] getPayload() {
        return payload == null ? null : payload.clone();
    }

    public int getQrSize() {
        return qrSize;
    }

    public int getMinPageSize() {
        return minPageSize;
    }

    public int getInitialPageSize() {
        return initialPageSize;
    }

    public int getMaxPageSize() {
        return maxPageSize;
    }

    private static int crc32c(byte[] bytes, int offset, int length) {
        CRC32C crc = new CRC32C();
        crc.update(bytes, offset, length);
        return (int) crc.getValue();
    }

    private static byte[] hexToBytes(String value) {
        if (value == null || value.length() != MD5_BYTES * 2) {
            throw new IllegalArgumentException("MD5 必须是 32 位十六进制字符串");
        }
        byte[] result = new byte[MD5_BYTES];
        for (int i = 0; i < result.length; i++) {
            int high = Character.digit(value.charAt(i * 2), 16);
            int low = Character.digit(value.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) {
                throw new IllegalArgumentException("MD5 包含非十六进制字符");
            }
            result[i] = (byte) ((high << 4) | low);
        }
        return result;
    }

    private static String bytesToHex(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(Character.forDigit((value >>> 4) & 0xF, 16));
            builder.append(Character.forDigit(value & 0xF, 16));
        }
        return builder.toString();
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof V2Frame)) {
            return false;
        }
        V2Frame that = (V2Frame) other;
        return type == that.type && flags == that.flags && sessionId == that.sessionId
                && fileSize == that.fileSize && pageNumber == that.pageNumber && offset == that.offset
                && qrSize == that.qrSize && minPageSize == that.minPageSize
                && initialPageSize == that.initialPageSize && maxPageSize == that.maxPageSize
                && Arrays.equals(md5, that.md5) && Arrays.equals(payload, that.payload);
    }

    @Override
    public int hashCode() {
        return Long.hashCode(sessionId) * 31 + pageNumber;
    }
}
