package com.crmforlogistics.messagecenter;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TemplateStore {
    private static final Gson GSON = new Gson();
    private static final Type TEMPLATE_LIST = new TypeToken<List<TemplateRecord>>() {}.getType();
    private static final Pattern PLACEHOLDER = Pattern.compile(
            "\\{\\{\\s*([A-Za-z0-9_.-]+)\\s*}}|\\$\\(\\s*([A-Za-z0-9_.-]+)\\s*\\)|\\(\\s*([A-Za-z][A-Za-z0-9_.-]*)\\s*\\)");

    private final Path file;

    public TemplateStore(Path file) {
        this.file = file;
    }

    public List<TemplateRecord> readAll() throws IOException {
        if (file == null || !Files.exists(file)) {
            return new ArrayList<>();
        }
        String json = Files.readString(file, StandardCharsets.UTF_8);
        if (json.isBlank()) {
            return new ArrayList<>();
        }
        List<TemplateRecord> records = GSON.fromJson(json, TEMPLATE_LIST);
        if (records == null) {
            return new ArrayList<>();
        }
        for (TemplateRecord record : records) {
            if ((record.placeholders == null || record.placeholders.isEmpty())
                    && record.body != null && !record.body.isBlank()) {
                record.placeholders = placeholders(record.body);
            }
        }
        return records;
    }

    public synchronized void saveAll(List<TemplateRecord> records) throws IOException {
        if (file == null) {
            return;
        }
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        List<TemplateRecord> safeRecords = records == null ? new ArrayList<>() : new ArrayList<>(records);
        safeRecords.sort(Comparator
                .comparing((TemplateRecord item) -> firstNonBlank(item.templateName, ""))
                .thenComparing(item -> firstNonBlank(item.languageCode, ""))
                .thenComparing(item -> firstNonBlank(item.templateCode, "")));
        Files.writeString(file, GSON.toJson(safeRecords), StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
    }

    public TemplateRecord find(String code, String language) {
        if (code == null || code.isBlank()) {
            return null;
        }
        try {
            String normalizedCode = normalize(code);
            String normalizedLanguage = normalize(language);
            TemplateRecord fallback = null;
            for (TemplateRecord record : readAll()) {
                if (!normalizedCode.equals(normalize(record.templateCode))) {
                    continue;
                }
                if (fallback == null) {
                    fallback = record;
                }
                if (normalizedLanguage.isBlank()
                        || normalizedLanguage.equals(normalize(record.languageCode))
                        || normalize(record.languageCode).isBlank()) {
                    return record;
                }
            }
            return fallback;
        } catch (IOException ignored) {
            return null;
        }
    }

    public String key(TemplateRecord record) {
        if (record == null) {
            return "|";
        }
        return normalize(record.templateCode) + "|" + normalize(record.languageCode);
    }

    public String render(String code, String language, String paramsJson, String fallbackName) {
        TemplateRecord record = find(code, language);
        Map<String, String> params = parseParams(paramsJson);
        if (record != null && record.body != null && !record.body.isBlank()) {
            Matcher matcher = PLACEHOLDER.matcher(record.body);
            StringBuffer output = new StringBuffer();
            while (matcher.find()) {
                String key = firstNonBlank(matcher.group(1), matcher.group(2), matcher.group(3));
                String value = params.getOrDefault(key, matcher.group());
                matcher.appendReplacement(output, Matcher.quoteReplacement(value));
            }
            matcher.appendTail(output);
            return output.toString();
        }
        if (params.isEmpty()) {
            return firstNonBlank(fallbackName, code, "template message");
        }
        return "Template message " + firstNonBlank(fallbackName, code, "") + ": " + String.join(" / ", params.values());
    }

    public static Map<String, String> parseParams(String json) {
        Map<String, String> result = new LinkedHashMap<>();
        if (json == null || json.isBlank()) {
            return result;
        }
        try {
            JsonElement element = JsonParser.parseString(json);
            if (!element.isJsonObject()) {
                return result;
            }
            JsonObject object = element.getAsJsonObject();
            for (String key : object.keySet()) {
                JsonElement value = object.get(key);
                result.put(key, value == null || value.isJsonNull() ? "" : scalar(value));
            }
        } catch (RuntimeException ignored) {
        }
        return result;
    }

    private static List<String> placeholders(String body) {
        List<String> result = new ArrayList<>();
        Matcher matcher = PLACEHOLDER.matcher(body);
        while (matcher.find()) {
            String key = firstNonBlank(matcher.group(1), matcher.group(2), matcher.group(3));
            if (!key.isBlank() && !result.contains(key)) {
                result.add(key);
            }
        }
        return result;
    }

    private static String scalar(JsonElement value) {
        return value.isJsonPrimitive() ? value.getAsString() : value.toString();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    public static class TemplateRecord {
        public String templateCode;
        public String templateName;
        public String languageCode;
        public String body;
        public List<String> placeholders = new ArrayList<>();
        public String raw;
        public String updatedAt;
    }
}
