package com.crmforlogistics.messagecenter;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/** 只读审计文件目录快照；扫描不会创建、删除或恢复任何文件。 */
final class AuditFileCatalog {
    private static final long GZIP_WORKING_BUFFER_BYTES = 64L * 1024L;

    enum Kind { CURRENT, ARCHIVE, ROTATING, TEMPORARY, UNKNOWN }

    record AuditFileEntry(Path path, Kind kind, LocalDate utcDate, long sequence,
                          long bytes, String issueCode) {
        AuditFileEntry {
            path = path.toAbsolutePath().normalize();
            if (bytes < 0) throw new IllegalArgumentException("bytes must not be negative");
        }
    }

    record AuditFileSnapshot(List<AuditFileEntry> current, List<AuditFileEntry> archives,
                             List<AuditFileEntry> recovery, List<AuditFileEntry> unknown,
                             long totalBytes, Set<String> issueCodes) {
        AuditFileSnapshot {
            current = List.copyOf(current);
            archives = List.copyOf(archives);
            recovery = List.copyOf(recovery);
            unknown = List.copyOf(unknown);
            issueCodes = Set.copyOf(issueCodes);
        }

        List<AuditFileEntry> all() {
            List<AuditFileEntry> result = new ArrayList<>();
            result.addAll(current);
            result.addAll(archives);
            result.addAll(recovery);
            result.addAll(unknown);
            return List.copyOf(result);
        }
    }

    private AuditFileCatalog() {
    }

    static AuditFileSnapshot scanStable(AuditFileSettings settings, Clock clock)
            throws AuditStorageException {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(clock, "clock");
        Path current = settings.file();
        Path parent = current.getParent();
        String fileName = current.getFileName().toString();
        String stem = fileName.substring(0, fileName.length() - ".jsonl".length());
        Pattern archivePattern = Pattern.compile(Pattern.quote(stem)
                + "\\.(\\d{4}-\\d{2}-\\d{2})\\.(\\d+)\\.jsonl\\.gz");
        Pattern rotatingPattern = Pattern.compile(Pattern.quote(stem)
                + "\\.(\\d{4}-\\d{2}-\\d{2})\\.(\\d+)\\.jsonl\\.rotating");
        Pattern tempPattern = Pattern.compile(Pattern.quote(stem)
                + "\\.(\\d{4}-\\d{2}-\\d{2})\\.(\\d+)\\.jsonl\\.gz\\.tmp");
        List<AuditFileEntry> currentEntries = new ArrayList<>();
        List<AuditFileEntry> archives = new ArrayList<>();
        List<AuditFileEntry> recovery = new ArrayList<>();
        List<AuditFileEntry> unknown = new ArrayList<>();
        Set<String> issues = new TreeSet<>();
        try {
            if (Files.exists(current)) {
                long bytes = Files.size(current);
                String issue = validateCurrent(current, bytes, settings.fileMaxBytes());
                if (issue != null) issues.add(issue);
                currentEntries.add(new AuditFileEntry(
                        current, Kind.CURRENT, null, 0, bytes, issue));
            }
            if (Files.exists(parent)) {
                try (var paths = Files.list(parent)) {
                    for (Path path : paths.sorted(Comparator.comparing(Path::toString)).toList()) {
                        if (path.equals(current)) continue;
                        String name = path.getFileName().toString();
                        if (name.endsWith(".lock")) continue;
                        long bytes = Files.isRegularFile(path) ? Files.size(path) : 0L;
                        Matcher matcher = archivePattern.matcher(name);
                        if (matcher.matches()) {
                            LocalDate date = parseDate(matcher.group(1));
                            long sequence = parseSequence(matcher.group(2));
                            if (date == null || sequence < 1) {
                                addUnknown(unknown, issues, path, bytes,
                                        "AUDIT_ARCHIVE_INVALID_NAME");
                            } else {
                                String issue = validateGzip(
                                        path, settings.streamMaxBytes());
                                if (issue != null) issues.add(issue);
                                archives.add(new AuditFileEntry(path, Kind.ARCHIVE,
                                        date, sequence, bytes, issue));
                            }
                        } else if ((matcher = rotatingPattern.matcher(name)).matches()) {
                            LocalDate date = parseDate(matcher.group(1));
                            long sequence = parseSequence(matcher.group(2));
                            if (date == null || sequence < 1) {
                                addUnknown(unknown, issues, path, bytes,
                                        "AUDIT_RECOVERY_INVALID_NAME");
                            } else {
                                String issue = "AUDIT_RECOVERY_PENDING";
                                issues.add(issue);
                                recovery.add(new AuditFileEntry(path, Kind.ROTATING,
                                        date, sequence, bytes, issue));
                            }
                        } else if ((matcher = tempPattern.matcher(name)).matches()) {
                            LocalDate date = parseDate(matcher.group(1));
                            long sequence = parseSequence(matcher.group(2));
                            if (date == null || sequence < 1) {
                                addUnknown(unknown, issues, path, bytes,
                                        "AUDIT_RECOVERY_INVALID_NAME");
                            } else {
                                String issue = validateGzip(path, settings.streamMaxBytes());
                                if (issue == null) issue = "AUDIT_RECOVERY_PENDING";
                                issues.add(issue);
                                recovery.add(new AuditFileEntry(path, Kind.TEMPORARY,
                                        date, sequence, bytes, issue));
                            }
                        } else if (name.startsWith(stem + ".")) {
                            addUnknown(unknown, issues, path, bytes, "AUDIT_UNKNOWN_FILE");
                        }
                    }
                }
            }
        } catch (IOException exception) {
            throw new AuditStorageException("AUDIT_CATALOG_SCAN_FAILED", "catalog", exception);
        }
        long total = 0;
        for (AuditFileEntry entry : currentEntries) total += entry.bytes();
        for (AuditFileEntry entry : archives) total += entry.bytes();
        for (AuditFileEntry entry : recovery) total += entry.bytes();
        for (AuditFileEntry entry : unknown) total += entry.bytes();
        return new AuditFileSnapshot(
                currentEntries, archives, recovery, unknown, total, issues);
    }

    static long gzipWorkingBufferBytes() {
        return GZIP_WORKING_BUFFER_BYTES;
    }

    private static void addUnknown(List<AuditFileEntry> unknown, Set<String> issues,
                                   Path path, long bytes, String issue) {
        issues.add(issue);
        unknown.add(new AuditFileEntry(path, Kind.UNKNOWN, null, 0, bytes, issue));
    }

    private static LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private static long parseSequence(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    private static String validateCurrent(Path path, long bytes, long maxBytes) {
        if (bytes == 0) return null;
        if (bytes > maxBytes || bytes > Integer.MAX_VALUE) {
            return "AUDIT_CURRENT_CORRUPTED";
        }
        try {
            byte[] content = Files.readAllBytes(path);
            if (content.length == 0 || content[content.length - 1] != '\n') {
                return "AUDIT_CURRENT_CORRUPTED";
            }
            int start = 0;
            for (int index = 0; index < content.length; index++) {
                if (content[index] != '\n') continue;
                for (int position = start; position < index; position++) {
                    if (content[position] == '\r') return "AUDIT_CURRENT_CORRUPTED";
                }
                CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(content, start, index - start));
                String json = decoded.toString();
                JsonElement parsed = JsonParser.parseString(json);
                if (!parsed.isJsonObject()
                        || !parsed.getAsJsonObject().has("occurredAt")
                        || !parsed.getAsJsonObject().get("occurredAt").isJsonPrimitive()
                        || !parsed.getAsJsonObject().get("occurredAt")
                        .getAsJsonPrimitive().isString()
                        || !parsed.getAsJsonObject().get("occurredAt")
                        .getAsString().endsWith("Z")) {
                    return "AUDIT_CURRENT_CORRUPTED";
                }
                Instant.parse(parsed.getAsJsonObject().get("occurredAt").getAsString());
                start = index + 1;
            }
            return null;
        } catch (Exception exception) {
            return "AUDIT_CURRENT_CORRUPTED";
        }
    }

    private static String validateGzip(Path path, long maxBytes) {
        try (InputStream input = new GZIPInputStream(Files.newInputStream(path))) {
            byte[] buffer = new byte[8_192];
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            long total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) return "AUDIT_ARCHIVE_TOO_LARGE";
                for (int index = 0; index < read; index++) {
                    byte value = buffer[index];
                    if (value == '\n') {
                        if (!validJsonLine(line.toByteArray())) {
                            return "AUDIT_ARCHIVE_CORRUPTED";
                        }
                        line.reset();
                    } else {
                        if (value == '\r') return "AUDIT_ARCHIVE_CORRUPTED";
                        line.write(value);
                    }
                }
            }
            return line.size() == 0 ? null : "AUDIT_ARCHIVE_CORRUPTED";
        } catch (Exception exception) {
            return "AUDIT_ARCHIVE_CORRUPTED";
        }
    }

    private static boolean validJsonLine(byte[] bytes) {
        if (bytes.length == 0) return false;
        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            JsonElement parsed = JsonParser.parseString(decoded.toString());
            if (!parsed.isJsonObject()
                    || !parsed.getAsJsonObject().has("occurredAt")
                    || !parsed.getAsJsonObject().get("occurredAt").isJsonPrimitive()
                    || !parsed.getAsJsonObject().get("occurredAt")
                    .getAsJsonPrimitive().isString()) {
                return false;
            }
            String occurredAt = parsed.getAsJsonObject().get("occurredAt").getAsString();
            if (!occurredAt.endsWith("Z")) return false;
            Instant.parse(occurredAt);
            return true;
        } catch (Exception exception) {
            return false;
        }
    }
}
