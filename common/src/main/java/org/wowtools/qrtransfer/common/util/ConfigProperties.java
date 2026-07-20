package org.wowtools.qrtransfer.common.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Reads an UTF-8 properties file from the current working directory first,
 * then falls back to the copy packaged in the application JAR.
 */
public final class ConfigProperties {
    private final Properties properties = new Properties();

    public ConfigProperties(Class<?> anchor, String name) {
        Path external = Path.of(name);
        try {
            if (Files.isRegularFile(external)) {
                try (Reader reader = Files.newBufferedReader(external, StandardCharsets.UTF_8)) {
                    properties.load(reader);
                }
                return;
            }
            try (InputStream in = anchor.getResourceAsStream("/" + name)) {
                if (in == null) {
                    throw new IllegalArgumentException("找不到配置文件: " + name);
                }
                try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                    properties.load(reader);
                }
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("读取配置文件失败: " + name, e);
        }
    }

    public String getRequired(String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺少配置项: " + key);
        }
        return value.trim();
    }

    public String get(String key, String defaultValue) {
        String value = properties.getProperty(key);
        return value == null ? defaultValue : value.trim();
    }
}
