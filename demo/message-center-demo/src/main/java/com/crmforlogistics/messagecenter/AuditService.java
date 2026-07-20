package com.crmforlogistics.messagecenter;

import com.google.gson.Gson;

import java.lang.reflect.Array;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class AuditService {
    private static final Gson GSON = new Gson();
    private static final int MAX_DEPTH = 8;
    private static final int MAX_ENTRIES = 100;
    private static final int MAX_TEXT_LENGTH = 500;
    private static final int MAX_KEY_LENGTH = 100;
    private static final int MAX_TOTAL_NODES = 1_000;
    private static final int MAX_TOTAL_TEXT_LENGTH = 20_000;
    private final Database database;

    public AuditService(Database database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    public void record(UUID actorUserId, String action, String resourceType, UUID resourceId,
                       Map<String, ?> before, Map<String, ?> after, String result) throws Exception {
        database.transaction(connection -> {
            record(connection, actorUserId, action, resourceType, resourceId, before, after, result);
            return null;
        });
    }

    void record(java.sql.Connection connection, UUID actorUserId, String action,
                String resourceType, UUID resourceId, Map<String, ?> before,
                Map<String, ?> after, String result) throws Exception {
        if (!java.util.List.of("success", "denied", "failed").contains(result)) {
            throw new IllegalArgumentException("Audit result is invalid");
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into audit_logs
                    (actor_user_id,action,resource_type,resource_id,before_summary_jsonb,after_summary_jsonb,result)
                values (?,?,?,?,?::jsonb,?::jsonb,?)
                """)) {
            setUuid(statement, 1, actorUserId);
            statement.setString(2, required(action, "action", 100));
            statement.setString(3, required(resourceType, "resourceType", 100));
            setUuid(statement, 4, resourceId);
            statement.setString(5, GSON.toJson(redact(before)));
            statement.setString(6, GSON.toJson(redact(after)));
            statement.setString(7, result);
            statement.executeUpdate();
        }
    }

    private static Map<String, Object> redact(Map<String, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (source == null) return result;
        RedactionBudget budget = new RedactionBudget();
        int count = 0;
        for (Map.Entry<String, ?> entry : source.entrySet()) {
            if (count++ >= MAX_ENTRIES || !budget.consumeNode()) {
                result.put("[TRUNCATED]", "[TRUNCATED]");
                break;
            }
            String key = safeKey(entry.getKey());
            result.put(key, sensitive(entry.getKey()) ? "[REDACTED]" : safeValue(entry.getValue(), 1, budget));
        }
        return result;
    }

    private static Object safeValue(Object value, int depth, RedactionBudget budget) {
        if (value == null || value instanceof Number || value instanceof Boolean || value instanceof UUID) return value;
        if (depth > MAX_DEPTH || !budget.consumeNode()) return "[TRUNCATED]";
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> nested = new LinkedHashMap<>();
            int count = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (count++ >= MAX_ENTRIES || budget.exhausted()) {
                    nested.put("[TRUNCATED]", "[TRUNCATED]");
                    break;
                }
                String key = String.valueOf(entry.getKey());
                nested.put(safeKey(key), sensitive(key) ? "[REDACTED]"
                        : safeValue(entry.getValue(), depth + 1, budget));
            }
            return nested;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> nested = new ArrayList<>();
            for (Object item : iterable) {
                if (nested.size() >= MAX_ENTRIES || budget.exhausted()) {
                    nested.add("[TRUNCATED]");
                    break;
                }
                nested.add(safeValue(item, depth + 1, budget));
            }
            return nested;
        }
        if (value.getClass().isArray()) {
            List<Object> nested = new ArrayList<>();
            int length = Math.min(Array.getLength(value), MAX_ENTRIES);
            for (int index = 0; index < length; index++) {
                if (budget.exhausted()) {
                    nested.add("[TRUNCATED]");
                    break;
                }
                nested.add(safeValue(Array.get(value, index), depth + 1, budget));
            }
            if (Array.getLength(value) > MAX_ENTRIES) nested.add("[TRUNCATED]");
            return nested;
        }
        String text = String.valueOf(value);
        int permitted = Math.min(MAX_TEXT_LENGTH, budget.remainingText());
        if (permitted <= 0) return "[TRUNCATED]";
        int length = Math.min(text.length(), permitted);
        budget.consumeText(length);
        return length == text.length() ? text : text.substring(0, length);
    }

    private static boolean sensitive(String key) {
        if (key != null && key.length() > MAX_KEY_LENGTH) return true;
        String normalized = key == null ? "" : key.toLowerCase(Locale.ROOT);
        return normalized.contains("password") || normalized.contains("secret")
                || normalized.contains("token") || normalized.contains("authorization")
                || normalized.contains("credential") || normalized.contains("encrypted");
    }

    private static String safeKey(String key) {
        if (key == null) return "null";
        return key.length() <= MAX_KEY_LENGTH ? key : "[TRUNCATED_KEY]";
    }

    private static final class RedactionBudget {
        private int nodes;
        private int textLength;

        boolean consumeNode() {
            if (nodes >= MAX_TOTAL_NODES) return false;
            nodes++;
            return true;
        }

        void consumeText(int length) {
            textLength += length;
        }

        int remainingText() {
            return Math.max(0, MAX_TOTAL_TEXT_LENGTH - textLength);
        }

        boolean exhausted() {
            return nodes >= MAX_TOTAL_NODES || textLength >= MAX_TOTAL_TEXT_LENGTH;
        }
    }

    private static String required(String value, String field, int max) {
        if (value == null || value.isBlank() || value.length() > max) throw new IllegalArgumentException(field + " is invalid");
        return value;
    }

    private static void setUuid(PreparedStatement statement, int index, UUID value) throws Exception {
        if (value == null) statement.setNull(index, Types.OTHER); else statement.setObject(index, value);
    }
}
