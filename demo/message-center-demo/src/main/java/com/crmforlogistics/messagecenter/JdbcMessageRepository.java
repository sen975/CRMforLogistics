package com.crmforlogistics.messagecenter;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class JdbcMessageRepository implements MessageRepository {
    private static final int MAX_LIMIT = 100;
    private static final Map<String, Integer> STATUS_RANK = Map.of(
            "pending", 0,
            "processing", 1,
            "submission_unknown", 2,
            "submitted", 3,
            "sent", 4,
            "delivered", 5,
            "read", 6,
            "failed", 7,
            "cancelled", 7);

    private final Database database;

    public JdbcMessageRepository(Database database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    @Override
    public UUID getOrCreateConversation(UUID accountId, UUID identityId) throws Exception {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(identityId, "identityId");
        return database.transaction(connection -> {
            UUID existing = findConversation(connection, accountId, identityId);
            if (existing != null) return existing;
            UUID id = UUID.randomUUID();
            try (PreparedStatement statement = connection.prepareStatement("""
                    insert into conversations (id, channel_account_id, contact_identity_id)
                    values (?, ?, ?) on conflict (channel_account_id, contact_identity_id) do nothing
                    """)) {
                statement.setObject(1, id);
                statement.setObject(2, accountId);
                statement.setObject(3, identityId);
                statement.executeUpdate();
            }
            UUID stored = findConversation(connection, accountId, identityId);
            if (stored == null) throw new IllegalStateException("Conversation upsert failed");
            return stored;
        });
    }

    @Override
    public MessageWriteResult insert(MessageDraft draft) throws Exception {
        validateDraft(draft);
        return database.transaction(connection -> {
            ConversationState conversation = lockConversation(connection, draft.conversationId(), draft.channelAccountId());
            UUID existing = findIdempotentMessage(connection, draft);
            if (existing != null) return new MessageWriteResult(existing, false);

            long sequence;
            try (PreparedStatement statement = connection.prepareStatement("""
                    update conversations
                    set next_ingest_sequence = next_ingest_sequence + 1, version = version + 1
                    where id = ? returning next_ingest_sequence
                    """)) {
                statement.setObject(1, draft.conversationId());
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) throw new IllegalArgumentException("Conversation not found");
                    sequence = rows.getLong(1);
                }
            }

            UUID messageId = UUID.randomUUID();
            String initialStatus = "outbound".equals(draft.direction()) ? "pending" : "delivered";
            try (PreparedStatement statement = connection.prepareStatement("""
                    insert into messages
                        (id,conversation_id,channel_account_id,source_event_id,provider_message_id,
                         client_request_id,direction,message_kind,subject,body_text,body_html,
                         occurred_at,ingest_sequence,counts_as_unread,current_status,current_status_at,
                         created_by_user_id)
                    values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) on conflict do nothing
                    returning id
                    """)) {
                statement.setObject(1, messageId);
                statement.setObject(2, draft.conversationId());
                statement.setObject(3, draft.channelAccountId());
                setUuid(statement, 4, draft.sourceEventId());
                setText(statement, 5, draft.providerMessageId());
                setText(statement, 6, draft.clientRequestId());
                statement.setString(7, draft.direction());
                statement.setString(8, draft.messageKind());
                setText(statement, 9, draft.subject());
                setText(statement, 10, draft.bodyText());
                setText(statement, 11, draft.bodyHtml());
                statement.setTimestamp(12, Timestamp.from(draft.occurredAt()));
                statement.setLong(13, sequence);
                statement.setBoolean(14, draft.countsAsUnread());
                statement.setString(15, initialStatus);
                statement.setTimestamp(16, Timestamp.from(draft.occurredAt()));
                setUuid(statement, 17, draft.createdByUserId());
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        UUID raced = findIdempotentMessage(connection, draft);
                        if (raced == null) throw new IllegalStateException("Message insert conflicted without an idempotent row");
                        return new MessageWriteResult(raced, false);
                    }
                }
            }

            try (PreparedStatement statement = connection.prepareStatement("""
                    insert into message_participants
                        (message_id,participant_role,contact_identity_id)
                    values (?, ?, ?)
                    """)) {
                statement.setObject(1, messageId);
                statement.setString(2, "inbound".equals(draft.direction()) ? "sender" : "to");
                statement.setObject(3, conversation.identityId());
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    insert into message_status_events (message_id,status,occurred_at)
                    values (?, ?, ?)
                    """)) {
                statement.setObject(1, messageId);
                statement.setString(2, initialStatus);
                statement.setTimestamp(3, Timestamp.from(draft.occurredAt()));
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    update conversations set last_message_id=?,last_message_at=?,updated_at=now()
                    where id=? and (last_message_at is null or last_message_at <= ?)
                    """)) {
                statement.setObject(1, messageId);
                statement.setTimestamp(2, Timestamp.from(draft.occurredAt()));
                statement.setObject(3, draft.conversationId());
                statement.setTimestamp(4, Timestamp.from(draft.occurredAt()));
                statement.executeUpdate();
            }
            return new MessageWriteResult(messageId, true);
        });
    }

    @Override
    public void appendStatus(UUID messageId, MessageStatusEvent event) throws Exception {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(event, "event");
        if (!STATUS_RANK.containsKey(event.status()) || event.occurredAt() == null) {
            throw new IllegalArgumentException("Message status event is invalid");
        }
        database.transaction(connection -> {
            int inserted;
            try (PreparedStatement statement = connection.prepareStatement("""
                    insert into message_status_events
                        (message_id,status,occurred_at,provider_event_id,reason_code,reason_message)
                    values (?,?,?,?,?,?) on conflict do nothing
                    """)) {
                statement.setObject(1, messageId);
                statement.setString(2, event.status());
                statement.setTimestamp(3, Timestamp.from(event.occurredAt()));
                setText(statement, 4, event.providerEventId());
                setText(statement, 5, event.reasonCode());
                setText(statement, 6, event.reasonMessage());
                inserted = statement.executeUpdate();
            }
            if (inserted == 0) return null;
            String currentStatus;
            Instant currentAt;
            try (PreparedStatement statement = connection.prepareStatement(
                    "select current_status,current_status_at from messages where id=? for update")) {
                statement.setObject(1, messageId);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) throw new IllegalArgumentException("Message not found");
                    currentStatus = rows.getString(1);
                    currentAt = rows.getTimestamp(2).toInstant();
                }
            }
            boolean monotonic = !event.occurredAt().isBefore(currentAt)
                    && STATUS_RANK.get(event.status()) >= STATUS_RANK.getOrDefault(currentStatus, -1);
            if (monotonic) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "update messages set current_status=?,current_status_at=? where id=?")) {
                    statement.setString(1, event.status());
                    statement.setTimestamp(2, Timestamp.from(event.occurredAt()));
                    statement.setObject(3, messageId);
                    statement.executeUpdate();
                }
            }
            return null;
        });
    }

    @Override
    public List<UnifiedMessage> thread(UUID userId, UUID conversationId, MessageCursor cursor, int limit) throws Exception {
        return queryMessages(userId, conversationId, null, cursor, limit);
    }

    @Override
    public List<UnifiedMessage> unifiedTimeline(UUID userId, UUID contactId, MessageCursor cursor, int limit) throws Exception {
        return queryMessages(userId, null, contactId, cursor, limit);
    }

    @Override
    public int unifiedTimelineCount(UUID userId, UUID contactId) throws Exception {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(contactId, "contactId");
        return database.read(connection -> {
            String sql = """
                    select count(*)
                    from messages m
                    join conversations cv on cv.id=m.conversation_id
                    join contact_identities ci on ci.id=cv.contact_identity_id
                    where ci.contact_id=? and
                    """ + authorizedSql("cv");
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                int i = 1;
                statement.setObject(i++, contactId);
                i = bindAuthorization(statement, i, userId);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) return 0;
                    return rows.getInt(1);
                }
            }
        });
    }

    @Override
    public Optional<UnifiedMessage> findAuthorized(UUID userId, UUID messageId) throws Exception {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(messageId, "messageId");
        return database.read(connection -> {
            String sql = baseSelect() + " where m.id=? and " + authorizedSql("cv");
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setObject(1, userId);
                statement.setObject(2, messageId);
                bindAuthorization(statement, 3, userId);
                try (ResultSet rows = statement.executeQuery()) {
                    return rows.next() ? Optional.of(readMessage(rows)) : Optional.empty();
                }
            }
        });
    }

    private List<UnifiedMessage> queryMessages(UUID userId, UUID conversationId, UUID contactId,
                                               MessageCursor cursor, int limit) throws Exception {
        Objects.requireNonNull(userId, "userId");
        int safeLimit = Math.max(1, Math.min(MAX_LIMIT, limit <= 0 ? 50 : limit));
        return database.read(connection -> {
            StringBuilder sql = new StringBuilder(baseSelect()).append(" where ");
            if (conversationId != null) sql.append("m.conversation_id=?");
            else sql.append("ci.contact_id=?");
            sql.append(" and ").append(authorizedSql("cv"));
            if (cursor != null) sql.append(" and (m.occurred_at < ? or (m.occurred_at = ? and m.id < ?))");
            sql.append(" order by m.occurred_at desc,m.id desc limit ?");
            try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                int i = 1;
                statement.setObject(i++, userId);
                statement.setObject(i++, conversationId != null ? conversationId : contactId);
                i = bindAuthorization(statement, i, userId);
                if (cursor != null) {
                    statement.setTimestamp(i++, Timestamp.from(cursor.occurredAt()));
                    statement.setTimestamp(i++, Timestamp.from(cursor.occurredAt()));
                    statement.setObject(i++, cursor.id());
                }
                statement.setInt(i, safeLimit);
                try (ResultSet rows = statement.executeQuery()) {
                    List<UnifiedMessage> result = new ArrayList<>();
                    while (rows.next()) result.add(readMessage(rows));
                    java.util.Collections.reverse(result);
                    return result;
                }
            }
        });
    }

    private static String baseSelect() {
        return """
                select m.id,m.provider_message_id,ca.channel_type,ci.id,m.direction,m.occurred_at,
                       m.subject,m.body_text,m.current_status,m.current_status_at,m.message_kind,
                       m.counts_as_unread,m.ingest_sequence,
                       coalesce((select crs.last_read_sequence from conversation_read_states crs
                                 where crs.conversation_id=m.conversation_id and crs.user_id=?),0)
                from messages m
                join conversations cv on cv.id=m.conversation_id
                join channel_accounts ca on ca.id=m.channel_account_id
                join contact_identities ci on ci.id=cv.contact_identity_id
                """;
    }

    private static String authorizedSql(String alias) {
        return "(" + alias + ".assigned_user_id=? or exists (select 1 from team_members tm where tm.team_id="
                + alias + ".assigned_team_id and tm.user_id=?) or exists (select 1 from conversation_access_grants g where g.conversation_id="
                + alias + ".id and g.user_id=? and g.revoked_at is null and (g.expires_at is null or g.expires_at>now())))";
    }

    private static int bindAuthorization(PreparedStatement statement, int start, UUID userId) throws Exception {
        statement.setObject(start++, userId);
        statement.setObject(start++, userId);
        statement.setObject(start++, userId);
        return start;
    }

    private static UnifiedMessage readMessage(ResultSet rows) throws Exception {
        UnifiedMessage message = new UnifiedMessage();
        message.id = rows.getObject(1, UUID.class).toString();
        message.sourceId = rows.getString(2);
        message.channel = rows.getString(3);
        message.contactPointId = rows.getObject(4, UUID.class).toString();
        message.direction = rows.getString(5);
        message.timestamp = rows.getTimestamp(6).toInstant().toString();
        message.title = rows.getString(7);
        message.text = rows.getString(8);
        message.bodyText = rows.getString(8);
        message.status = rows.getString(9);
        message.statusTimestamp = rows.getTimestamp(10).toInstant().toString();
        message.mediaType = rows.getString(11);
        message.countsAsUnread = rows.getBoolean(12);
        message.ingestSequence = rows.getLong(13);
        long lastReadSequence = rows.getLong(14);
        message.unread = "inbound".equals(message.direction)
                && message.countsAsUnread
                && message.ingestSequence > lastReadSequence;
        return message;
    }

    private static ConversationState lockConversation(java.sql.Connection connection, UUID conversationId,
                                                       UUID accountId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "select contact_identity_id from conversations where id=? and channel_account_id=? for update")) {
            statement.setObject(1, conversationId);
            statement.setObject(2, accountId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new IllegalArgumentException("Conversation/account mismatch");
                return new ConversationState(rows.getObject(1, UUID.class));
            }
        }
    }

    private static UUID findConversation(java.sql.Connection connection, UUID accountId, UUID identityId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "select id from conversations where channel_account_id=? and contact_identity_id=?")) {
            statement.setObject(1, accountId);
            statement.setObject(2, identityId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getObject(1, UUID.class) : null;
            }
        }
    }

    private static UUID findIdempotentMessage(java.sql.Connection connection, MessageDraft draft) throws Exception {
        if (draft.providerMessageId() != null && !draft.providerMessageId().isBlank()) {
            UUID found = findByKey(connection, "provider_message_id", draft.channelAccountId(), draft.providerMessageId());
            if (found != null) return found;
        }
        if (draft.clientRequestId() != null && !draft.clientRequestId().isBlank()) {
            return findByKey(connection, "client_request_id", draft.channelAccountId(), draft.clientRequestId());
        }
        return null;
    }

    private static UUID findByKey(java.sql.Connection connection, String column, UUID accountId, String value) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "select id from messages where channel_account_id=? and " + column + "=?")) {
            statement.setObject(1, accountId);
            statement.setString(2, value);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getObject(1, UUID.class) : null;
            }
        }
    }

    private static void validateDraft(MessageDraft draft) {
        Objects.requireNonNull(draft, "draft");
        if (draft.conversationId() == null || draft.channelAccountId() == null || draft.occurredAt() == null
                || !List.of("inbound", "outbound", "system").contains(draft.direction())
                || !List.of("text", "template", "image", "video", "document", "email", "system").contains(draft.messageKind())) {
            throw new IllegalArgumentException("Message draft is invalid");
        }
    }

    private static void setUuid(PreparedStatement statement, int index, UUID value) throws Exception {
        if (value == null) statement.setNull(index, Types.OTHER); else statement.setObject(index, value);
    }

    private static void setText(PreparedStatement statement, int index, String value) throws Exception {
        if (value == null) statement.setNull(index, Types.VARCHAR); else statement.setString(index, value);
    }

    private record ConversationState(UUID identityId) {}
}
