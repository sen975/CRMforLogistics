package com.crmforlogistics.messagecenter;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class AccessControlService {
    private final Database database;
    private final AuditService audit;

    public AccessControlService(Database database, AuditService audit) {
        this.database = Objects.requireNonNull(database, "database");
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    public boolean canRead(UUID userId, UUID conversationId) throws Exception {
        return database.read(connection -> canRead(connection, userId, conversationId));
    }

    public boolean canSend(UUID userId, UUID conversationId) throws Exception {
        return database.read(connection -> canSend(connection, userId, conversationId));
    }

    boolean canRead(Connection connection, UUID userId, UUID conversationId) throws Exception {
        return hasAccess(connection, userId, conversationId, false);
    }

    boolean canSend(Connection connection, UUID userId, UUID conversationId) throws Exception {
        return hasAccess(connection, userId, conversationId, true);
    }

    public void requireConversationRead(UUID userId, UUID conversationId) throws Exception {
        if (!canRead(userId, conversationId)) throw denied();
    }

    public void requireConversationSend(UUID userId, UUID conversationId) throws Exception {
        database.read(connection -> {
            requireConversationSend(connection, userId, conversationId);
            return null;
        });
    }

    void requireConversationSend(Connection connection, UUID userId, UUID conversationId) throws Exception {
        if (!canSend(connection, userId, conversationId)) throw denied();
    }

    public void grant(UUID conversationId, UUID userId, UUID grantedBy, String reason) throws Exception {
        database.transaction(connection -> {
            if (!canRead(connection, grantedBy, conversationId)) throw denied();
            try (PreparedStatement statement = connection.prepareStatement("""
                    update conversation_access_grants
                    set reason=?,granted_by=?,granted_at=now(),expires_at=null
                    where conversation_id=? and user_id=? and revoked_at is null
                    """)) {
                statement.setString(1, reason);
                statement.setObject(2, grantedBy);
                statement.setObject(3, conversationId);
                statement.setObject(4, userId);
                if (statement.executeUpdate() == 0) {
                    try (PreparedStatement insert = connection.prepareStatement("""
                            insert into conversation_access_grants
                                (conversation_id,user_id,granted_by,reason)
                            values (?,?,?,?)
                            """)) {
                        insert.setObject(1, conversationId);
                        insert.setObject(2, userId);
                        insert.setObject(3, grantedBy);
                        insert.setString(4, reason);
                        insert.executeUpdate();
                    }
                }
            }
            audit.record(connection, grantedBy, "conversation.grant", "conversation", conversationId,
                    Map.of(), Map.of("grantee", userId), "success");
            return null;
        });
    }

    public ReadState markRead(UUID userId, UUID conversationId) throws Exception {
        return database.transaction(connection -> {
            if (!canRead(connection, userId, conversationId)) throw denied();
            long maxSequence;
            UUID lastMessageId = null;
            try (PreparedStatement statement = connection.prepareStatement("""
                    select ingest_sequence,id from messages
                    where conversation_id=? order by ingest_sequence desc limit 1
                    """)) {
                statement.setObject(1, conversationId);
                try (ResultSet rows = statement.executeQuery()) {
                    if (rows.next()) {
                        maxSequence = rows.getLong(1);
                        lastMessageId = rows.getObject(2, UUID.class);
                    } else maxSequence = 0;
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    insert into conversation_read_states
                        (conversation_id,user_id,last_read_sequence,last_read_message_id,last_read_at)
                    values (?,?,?,?,now()) on conflict (conversation_id,user_id)
                    do update set last_read_sequence=greatest(conversation_read_states.last_read_sequence,excluded.last_read_sequence),
                                  last_read_message_id=case when excluded.last_read_sequence>=conversation_read_states.last_read_sequence
                                                            then excluded.last_read_message_id else conversation_read_states.last_read_message_id end,
                                  last_read_at=now(),updated_at=now()
                    returning last_read_sequence,last_read_message_id,last_read_at
                    """)) {
                statement.setObject(1, conversationId);
                statement.setObject(2, userId);
                statement.setLong(3, maxSequence);
                statement.setObject(4, lastMessageId);
                try (ResultSet rows = statement.executeQuery()) {
                    rows.next();
                    ReadState state = new ReadState(conversationId, userId, rows.getLong(1),
                            rows.getObject(2, UUID.class), rows.getTimestamp(3).toInstant());
                    audit.record(connection, userId, "conversation.read", "conversation", conversationId,
                            Map.of(), Map.of("lastReadSequence", state.lastReadSequence()), "success");
                    return state;
                }
            }
        });
    }

    public long unreadCount(UUID userId, UUID conversationId) throws Exception {
        return database.read(connection -> {
            if (!canRead(connection, userId, conversationId)) throw denied();
            try (PreparedStatement statement = connection.prepareStatement("""
                    select count(*) from messages m
                    where m.conversation_id=? and m.direction='inbound' and m.counts_as_unread
                      and m.ingest_sequence>coalesce((select last_read_sequence from conversation_read_states
                                                      where conversation_id=? and user_id=?),0)
                    """)) {
                statement.setObject(1, conversationId);
                statement.setObject(2, conversationId);
                statement.setObject(3, userId);
                try (ResultSet rows = statement.executeQuery()) {
                    rows.next();
                    return rows.getLong(1);
                }
            }
        });
    }

    private static boolean hasAccess(Connection connection, UUID userId, UUID conversationId,
                                     boolean sending) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                select exists(
                    select 1 from users u join conversations cv on cv.id=?
                    where u.id=? and u.status='active' and u.deleted_at is null
                      and (?=false or cv.status='open')
                      and (cv.assigned_user_id=u.id
                           or exists (select 1 from teams t
                                      where t.id=cv.assigned_team_id and t.status='active' and t.supervisor_id=u.id)
                           or exists (select 1 from conversation_access_grants g
                                      where g.conversation_id=cv.id and g.user_id=u.id
                                        and g.revoked_at is null
                                        and (g.expires_at is null or g.expires_at>now())))
                )
                """)) {
            statement.setObject(1, conversationId);
            statement.setObject(2, userId);
            statement.setBoolean(3, sending);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getBoolean(1);
            }
        }
    }

    private static AccessDeniedException denied() {
        return new AccessDeniedException();
    }

    public static final class AccessDeniedException extends Exception {
        AccessDeniedException() { super("Conversation access denied"); }
    }
}

record ReadState(UUID conversationId, UUID userId, long lastReadSequence,
                 UUID lastReadMessageId, Instant lastReadAt) {}
