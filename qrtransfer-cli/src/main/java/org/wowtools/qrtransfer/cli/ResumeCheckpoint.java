package org.wowtools.qrtransfer.cli;

import org.wowtools.qrtransfer.common.transfer.V2Frame;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

final class ResumeCheckpoint {
    static final long DEFAULT_BLOCK_SIZE = 1024L * 1024L;
    private static final String FORMAT_VERSION = "1";

    final Path part;
    final Path meta;
    final long blockSize;
    final long committedOffset;
    final long originalPartSize;
    final long rolledBackBytes;
    final String prefixMd5;
    private final List<String> blockMd5;

    private ResumeCheckpoint(Path part, Path meta, long blockSize, long committedOffset,
                             long originalPartSize, String prefixMd5, List<String> blockMd5) {
        this.part = part;
        this.meta = meta;
        this.blockSize = blockSize;
        this.committedOffset = committedOffset;
        this.originalPartSize = originalPartSize;
        this.rolledBackBytes = originalPartSize - committedOffset;
        this.prefixMd5 = prefixMd5;
        this.blockMd5 = blockMd5;
    }

    static ResumeCheckpoint prepare(Path output, V2Frame header, boolean restart) throws IOException {
        Path part = Path.of(output.toString() + ".part");
        Path meta = Path.of(output.toString() + ".part.meta");
        if (restart) {
            Files.deleteIfExists(part);
            Files.deleteIfExists(meta);
        }
        if (!Files.exists(part) && !Files.exists(meta)) {
            Files.createFile(part);
            ResumeCheckpoint fresh = new ResumeCheckpoint(part, meta, DEFAULT_BLOCK_SIZE,
                    0, 0, md5(part, 0, 0), new ArrayList<>());
            fresh.save(header);
            return fresh;
        }
        if (!Files.isRegularFile(part) || !Files.isRegularFile(meta)) {
            throw new IOException("断点文件不完整；请检查现场或使用 --restart");
        }
        Properties properties = load(meta);
        require(FORMAT_VERSION.equals(properties.getProperty("formatVersion")), "断点元数据版本不支持");
        require(Byte.toString(V2Frame.VERSION).equals(properties.getProperty("protocolVersion")),
                "断点协议版本不支持");
        require(Long.toString(header.getFileSize()).equals(properties.getProperty("fileSize")),
                "源文件大小与断点不匹配");
        require(header.getMd5Hex().equals(properties.getProperty("fileMd5")),
                "源文件 MD5 与断点不匹配");
        require(Boolean.toString(header.isText()).equals(properties.getProperty("text")),
                "文本模式与断点不匹配");
        long blockSize = parseLong(properties, "blockSize");
        long committed = parseLong(properties, "committedOffset");
        require(blockSize == DEFAULT_BLOCK_SIZE && committed >= 0 && committed <= header.getFileSize()
                && committed % blockSize == 0, "断点偏移无效");
        long originalSize = Files.size(part);
        require(originalSize >= committed, "断点数据短于已提交偏移");
        int blocks = Math.toIntExact(committed / blockSize);
        List<String> hashes = new ArrayList<>();
        for (int i = 0; i < blocks; i++) {
            String expected = properties.getProperty("block." + i);
            require(expected != null && expected.equals(md5(part, i * blockSize, blockSize)),
                    "断点块 " + i + " 校验失败");
            hashes.add(expected);
        }
        try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(
                part, StandardOpenOption.WRITE)) {
            channel.truncate(committed);
            channel.force(true);
        }
        return new ResumeCheckpoint(part, meta, blockSize, committed, originalSize,
                md5(part, 0, committed), hashes);
    }

    ResumeCheckpoint commitAvailable(V2Frame header, long written) throws IOException {
        long target = written / blockSize * blockSize;
        if (target <= committedOffset) {
            return this;
        }
        force(part);
        List<String> hashes = new ArrayList<>(blockMd5);
        for (long offset = committedOffset; offset < target; offset += blockSize) {
            hashes.add(md5(part, offset, blockSize));
        }
        ResumeCheckpoint updated = new ResumeCheckpoint(part, meta, blockSize, target,
                Files.size(part), prefixMd5, hashes);
        updated.save(header);
        return updated;
    }

    void deleteMeta() throws IOException {
        Files.deleteIfExists(meta);
    }

    private void save(V2Frame header) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("formatVersion", FORMAT_VERSION);
        properties.setProperty("protocolVersion", Byte.toString(V2Frame.VERSION));
        properties.setProperty("fileSize", Long.toString(header.getFileSize()));
        properties.setProperty("fileMd5", header.getMd5Hex());
        properties.setProperty("text", Boolean.toString(header.isText()));
        properties.setProperty("blockSize", Long.toString(blockSize));
        properties.setProperty("committedOffset", Long.toString(committedOffset));
        properties.setProperty("updatedAt", Long.toString(System.currentTimeMillis()));
        for (int i = 0; i < blockMd5.size(); i++) {
            properties.setProperty("block." + i, blockMd5.get(i));
        }
        Path temporary = Path.of(meta.toString() + ".tmp");
        try (OutputStream output = Files.newOutputStream(temporary,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            properties.store(output, "qrtransfer resumable checkpoint");
        }
        try {
            Files.move(temporary, meta, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, meta, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static String md5(Path path, long offset, long length) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        try (FileChannel input = FileChannel.open(path, StandardOpenOption.READ)) {
            input.position(offset);
            byte[] buffer = new byte[64 * 1024];
            long remaining = length;
            while (remaining > 0) {
                int wanted = (int) Math.min(buffer.length, remaining);
                ByteBuffer bytes = ByteBuffer.wrap(buffer, 0, wanted);
                int read = input.read(bytes);
                if (read < 0) {
                    throw new IOException("断点数据提前结束");
                }
                digest.update(buffer, 0, read);
                remaining -= read;
            }
        }
        StringBuilder value = new StringBuilder(32);
        for (byte b : digest.digest()) {
            value.append(String.format("%02x", b & 0xff));
        }
        return value.toString();
    }

    private static void force(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private static Properties load(Path path) throws IOException {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            properties.load(input);
        }
        return properties;
    }

    private static long parseLong(Properties properties, String key) throws IOException {
        try {
            return Long.parseLong(properties.getProperty(key));
        } catch (RuntimeException e) {
            throw new IOException("断点元数据字段无效: " + key, e);
        }
    }

    private static void require(boolean valid, String message) throws IOException {
        if (!valid) {
            throw new IOException(message + "；请保留现场或使用 --restart");
        }
    }
}
