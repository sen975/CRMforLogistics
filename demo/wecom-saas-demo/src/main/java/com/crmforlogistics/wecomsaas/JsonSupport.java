package com.crmforlogistics.wecomsaas;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class JsonSupport {
    public static final Gson GSON = new GsonBuilder().create();

    private JsonSupport() {
    }

    public static <T> List<T> readJsonl(Path path, Class<T> type) throws IOException {
        List<T> records = new ArrayList<>();
        if (!Files.exists(path)) {
            return records;
        }
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                records.add(GSON.fromJson(line, type));
            }
        }
        return records;
    }

    public static void appendJsonl(Path path, Object value) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(path, GSON.toJson(value) + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    public static Map<String, Object> map(String... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("map requires key/value pairs");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < keyValues.length; index += 2) {
            result.put(keyValues[index], keyValues[index + 1]);
        }
        return result;
    }

    public static Map<String, Object> map(String key, Object value, Object... rest) {
        if (rest.length % 2 != 0) {
            throw new IllegalArgumentException("map requires key/value pairs");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(key, value);
        for (int index = 0; index < rest.length; index += 2) {
            result.put(String.valueOf(rest[index]), rest[index + 1]);
        }
        return result;
    }
}
