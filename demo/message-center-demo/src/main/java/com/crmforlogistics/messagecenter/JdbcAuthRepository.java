package com.crmforlogistics.messagecenter;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.text.Normalizer;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class JdbcAuthRepository {
    private final Database database;

    public JdbcAuthRepository(Database database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    public UUID createUser(UserDraft draft, String passwordHash) throws Exception {
        return database.transaction(connection -> insertUser(connection, draft, passwordHash));
    }

    public Optional<AuthUser> findUser(String username) throws Exception {
        String normalized = normalizeUsername(username);
        return database.read(connection -> findUser(connection, normalized));
    }

    public BootstrapResult bootstrapAdmin(UserDraft draft, String passwordHash) throws Exception {
        return database.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("select pg_advisory_xact_lock(?)")) {
                statement.setLong(1, 6_648_201L);
                statement.executeQuery();
            }
            try (PreparedStatement statement = connection.prepareStatement("select count(*) from users")) {
                try (ResultSet rows = statement.executeQuery()) {
                    rows.next();
                    if (rows.getLong(1) > 0) return new BootstrapResult(false, null, "USERS_EXIST");
                }
            }
            UUID userId = insertUser(connection, draft, passwordHash);
            return new BootstrapResult(true, userId, "CREATED");
        });
    }

    public void createSession(UUID userId, byte[] tokenHash, Instant issuedAt, Instant expiresAt) throws Exception {
        database.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    insert into user_sessions (user_id,token_hash,issued_at,expires_at,last_seen_at)
                    values (?,?,?,?,?)
                    """)) {
                statement.setObject(1, userId);
                statement.setBytes(2, tokenHash);
                statement.setTimestamp(3, Timestamp.from(issuedAt));
                statement.setTimestamp(4, Timestamp.from(expiresAt));
                statement.setTimestamp(5, Timestamp.from(issuedAt));
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "update users set last_login_at=?,updated_at=? where id=?")) {
                statement.setTimestamp(1, Timestamp.from(issuedAt));
                statement.setTimestamp(2, Timestamp.from(issuedAt));
                statement.setObject(3, userId);
                statement.executeUpdate();
            }
            return null;
        });
    }

    public Optional<AuthenticatedSession> findSession(byte[] tokenHash, Instant now) throws Exception {
        return database.read(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    select s.id,s.user_id,s.expires_at
                    from user_sessions s join users u on u.id=s.user_id
                    where s.token_hash=? and s.revoked_at is null and s.expires_at>?
                      and u.status='active' and u.deleted_at is null
                    """)) {
                statement.setBytes(1, tokenHash);
                statement.setTimestamp(2, Timestamp.from(now));
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) return Optional.empty();
                    return Optional.of(new AuthenticatedSession(rows.getObject(1, UUID.class),
                            rows.getObject(2, UUID.class), rows.getTimestamp(3).toInstant()));
                }
            }
        });
    }

    public void revokeSession(byte[] tokenHash, Instant now) throws Exception {
        database.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "update user_sessions set revoked_at=? where token_hash=? and revoked_at is null")) {
                statement.setTimestamp(1, Timestamp.from(now));
                statement.setBytes(2, tokenHash);
                statement.executeUpdate();
            }
            return null;
        });
    }

    private static Optional<AuthUser> findUser(Connection connection, String normalized) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                select id,username,password_hash,display_name,status
                from users where username_normalized=? and deleted_at is null
                """)) {
            statement.setString(1, normalized);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                UUID userId = rows.getObject(1, UUID.class);
                Set<String> roles = new LinkedHashSet<>();
                try (PreparedStatement roleStatement = connection.prepareStatement("""
                        select r.code from user_roles ur join roles r on r.id=ur.role_id
                        where ur.user_id=? order by r.code
                        """)) {
                    roleStatement.setObject(1, userId);
                    try (ResultSet roleRows = roleStatement.executeQuery()) {
                        while (roleRows.next()) roles.add(roleRows.getString(1));
                    }
                }
                return Optional.of(new AuthUser(userId, rows.getString(2), rows.getString(3),
                        rows.getString(4), rows.getString(5), Set.copyOf(roles)));
            }
        }
    }

    private static UUID insertUser(Connection connection, UserDraft draft, String passwordHash) throws Exception {
        Objects.requireNonNull(draft, "draft");
        UUID userId = UUID.randomUUID();
        String normalized = normalizeUsername(draft.username());
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into users (id,username,username_normalized,password_hash,display_name,status)
                values (?,?,?,?,?,?)
                """)) {
            statement.setObject(1, userId);
            statement.setString(2, draft.username().trim());
            statement.setString(3, normalized);
            statement.setString(4, Objects.requireNonNull(passwordHash, "passwordHash"));
            statement.setString(5, draft.displayName());
            statement.setString(6, draft.status());
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into user_roles (user_id,role_id)
                select ?,id from roles where code=?
                """)) {
            statement.setObject(1, userId);
            statement.setString(2, draft.role());
            if (statement.executeUpdate() != 1) throw new IllegalArgumentException("Role is invalid");
        }
        return userId;
    }

    static String normalizeUsername(String username) {
        if (username == null || username.isBlank() || username.length() > 100) throw new IllegalArgumentException("Username is invalid");
        return Normalizer.normalize(username, Normalizer.Form.NFKC).trim().toLowerCase(Locale.ROOT);
    }
}

record UserDraft(String username, String displayName, String status, String role) {}
record AuthUser(UUID id, String username, String passwordHash, String displayName,
                String status, Set<String> roles) {}
record BootstrapResult(boolean created, UUID userId, String code) {}
