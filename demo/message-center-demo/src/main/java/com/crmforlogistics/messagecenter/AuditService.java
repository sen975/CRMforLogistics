package com.crmforlogistics.messagecenter;

import com.google.gson.Gson;

import java.sql.PreparedStatement;
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class AuditService {
    private static final Gson GSON = new Gson();
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
        source.forEach((key, value) -> result.put(key, sensitive(key) ? "[REDACTED]" : safeValue(value)));
        return result;
    }

    private static Object safeValue(Object value) {
        if (value == null || value instanceof Number || value instanceof Boolean || value instanceof UUID) return value;
        String text = String.valueOf(value);
        return text.length() <= 500 ? text : text.substring(0, 500);
    }

    private static boolean sensitive(String key) {
        String normalized = key == null ? "" : key.toLowerCase(Locale.ROOT);
        return normalized.contains("password") || normalized.contains("secret")
                || normalized.contains("token") || normalized.contains("authorization")
                || normalized.contains("credential") || normalized.contains("encrypted");
    }

    private static String required(String value, String field, int max) {
        if (value == null || value.isBlank() || value.length() > max) throw new IllegalArgumentException(field + " is invalid");
        return value;
    }

    private static void setUuid(PreparedStatement statement, int index, UUID value) throws Exception {
        if (value == null) statement.setNull(index, Types.OTHER); else statement.setObject(index, value);
    }
}
