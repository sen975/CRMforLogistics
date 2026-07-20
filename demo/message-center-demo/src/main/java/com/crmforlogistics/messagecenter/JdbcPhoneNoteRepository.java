package com.crmforlogistics.messagecenter;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class JdbcPhoneNoteRepository implements PhoneNoteRepository {
    private final Database database;

    public JdbcPhoneNoteRepository(Database database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    @Override
    public UUID create(PhoneNoteDraft draft, UUID actorId) throws Exception {
        Objects.requireNonNull(draft, "draft");
        if (draft.contactId() == null || draft.occurredAt() == null || draft.summary() == null || draft.summary().isBlank()) {
            throw new IllegalArgumentException("Phone note is incomplete");
        }
        UUID id = UUID.randomUUID();
        database.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    insert into phone_notes (id,contact_id,company_id,phone_identity_id,occurred_at,summary,next_step,created_by)
                    values (?,?,?,?,?,?,?,?)
                    """)) {
                statement.setObject(1, id);
                statement.setObject(2, draft.contactId());
                setNullable(statement, 3, draft.companyId());
                setNullable(statement, 4, draft.phoneIdentityId());
                statement.setTimestamp(5, java.sql.Timestamp.from(draft.occurredAt()));
                statement.setString(6, draft.summary().trim());
                setNullable(statement, 7, draft.nextStep());
                setNullable(statement, 8, actorId);
                statement.executeUpdate();
            }
            return null;
        });
        return id;
    }

    @Override
    public List<PhoneNote> listByContact(UUID userId, UUID contactId, MessageCursor cursor, int limit) throws Exception {
        int safeLimit = Math.max(1, Math.min(100, limit <= 0 ? 50 : limit));
        return database.read(connection -> {
            String sql = "select n.id,n.contact_id,n.company_id,n.phone_identity_id,n.occurred_at,n.summary,n.next_step,n.created_by "
                    + "from phone_notes n where n.contact_id=? "
                    + (cursor == null ? "" : "and (n.occurred_at < ? or (n.occurred_at = ? and n.id < ?)) ")
                    + "order by n.occurred_at desc,n.id desc limit ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                int i = 1;
                statement.setObject(i++, contactId);
                if (cursor != null) {
                    statement.setTimestamp(i++, java.sql.Timestamp.from(cursor.occurredAt()));
                    statement.setTimestamp(i++, java.sql.Timestamp.from(cursor.occurredAt()));
                    statement.setObject(i++, cursor.id());
                }
                statement.setInt(i, safeLimit);
                try (ResultSet rows = statement.executeQuery()) {
                    List<PhoneNote> result = new ArrayList<>();
                    while (rows.next()) result.add(new PhoneNote(rows.getObject(1, UUID.class), rows.getObject(2, UUID.class),
                            rows.getObject(3, UUID.class), rows.getObject(4, UUID.class), rows.getTimestamp(5).toInstant(),
                            rows.getString(6), rows.getString(7), rows.getObject(8, UUID.class)));
                    return result;
                }
            }
        });
    }

    private static void setNullable(PreparedStatement statement, int index, Object value) throws Exception {
        if (value == null) statement.setNull(index, Types.OTHER); else statement.setObject(index, value);
    }
}
