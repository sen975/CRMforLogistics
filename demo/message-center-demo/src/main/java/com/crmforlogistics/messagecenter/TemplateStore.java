package com.crmforlogistics.messagecenter;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TemplateStore {
    private static final Gson GSON = new Gson();
    private static final Type TEMPLATE_LIST = new TypeToken<List<TemplateRecord>>() {}.getType();
    private static final Pattern PLACEHOLDER = Pattern.compile(
            "\\{\\{\\s*([A-Za-z0-9_.-]+)\\s*}}|\\$\\(\\s*([A-Za-z0-9_.-]+)\\s*\\)|\\(\\s*([A-Za-z][A-Za-z0-9_.-]*)\\s*\\)");

    private final Path file;
    private final AtomicMover mover;

    public TemplateStore(Path file) {
        this(file, TemplateStore::defaultMove);
    }

    TemplateStore(Path file, AtomicMover mover) {
        this.file = file;
        this.mover = mover;
    }

    Path file() {
        return file;
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
        replaceIfChanged(records);
    }

    public synchronized ReplaceResult replaceIfChanged(List<TemplateRecord> records) throws IOException {
        List<TemplateRecord> next = normalized(records);
        List<TemplateRecord> previous = normalized(readAll());
        int changedRecords = changedRecordCount(previous, next);
        if (changedRecords == 0) {
            return new ReplaceResult(false, 0, next.size());
        }
        writeAtomically(next);
        return new ReplaceResult(true, changedRecords, next.size());
    }

    private void writeAtomically(List<TemplateRecord> records) throws IOException {
        Path target = file.toAbsolutePath().normalize();
        Path parent = target.getParent();
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, target.getFileName().toString() + ".", ".tmp");
        IOException writeFailure = null;
        try {
            Files.writeString(temporary, GSON.toJson(records), StandardCharsets.UTF_8,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            mover.move(temporary, target);
        } catch (IOException exception) {
            writeFailure = exception;
            throw exception;
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupFailure) {
                if (writeFailure != null) {
                    writeFailure.addSuppressed(cleanupFailure);
                } else {
                    throw cleanupFailure;
                }
            }
        }
    }

    private static void defaultMove(Path source, Path target) throws IOException {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
    }

    private List<TemplateRecord> normalized(List<TemplateRecord> input) {
        List<TemplateRecord> result = new ArrayList<>();
        for (TemplateRecord source : input == null ? List.<TemplateRecord>of() : input) {
            TemplateRecord copy = GSON.fromJson(GSON.toJson(source), TemplateRecord.class);
            if (copy.placeholders == null || copy.placeholders.isEmpty()) {
                copy.placeholders = placeholders(copy.body == null ? "" : copy.body);
            }
            result.add(copy);
        }
        result.sort(Comparator.comparing((TemplateRecord item) -> firstNonBlank(item.templateName, ""))
                .thenComparing(item -> firstNonBlank(item.languageCode, ""))
                .thenComparing(item -> firstNonBlank(item.templateCode, "")));
        return result;
    }

    private int changedRecordCount(List<TemplateRecord> previous, List<TemplateRecord> next) {
        Map<String, String> before = semanticMap(previous);
        Map<String, String> after = semanticMap(next);
        Set<String> keys = new LinkedHashSet<>(before.keySet());
        keys.addAll(after.keySet());
        int changed = 0;
        for (String key : keys) {
            if (!Objects.equals(before.get(key), after.get(key))) {
                changed++;
            }
        }
        return changed;
    }

    private Map<String, String> semanticMap(List<TemplateRecord> records) {
        Map<String, String> result = new LinkedHashMap<>();
        for (TemplateRecord source : records) {
            TemplateRecord copy = GSON.fromJson(GSON.toJson(source), TemplateRecord.class);
            copy.updatedAt = null;
            result.put(key(copy), GSON.toJson(copy));
        }
        return result;
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

    @FunctionalInterface
    interface AtomicMover {
        void move(Path source, Path target) throws IOException;
    }

    record ReplaceResult(boolean changed, int changedRecords, int recordCount) {}
}
