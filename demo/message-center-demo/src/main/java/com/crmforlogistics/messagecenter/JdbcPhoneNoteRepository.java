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
                setUuid(statement, 3, draft.companyId());
                setUuid(statement, 4, draft.phoneIdentityId());
                statement.setTimestamp(5, java.sql.Timestamp.from(draft.occurredAt()));
                statement.setString(6, draft.summary().trim());
                setText(statement, 7, draft.nextStep());
                setUuid(statement, 8, actorId);
                statement.executeUpdate();
            }
            return null;
        });
        return id;
    }

    @Override
    public List<PhoneNote> listByContact(UUID userId, UUID contactId, MessageCursor cursor, int limit) throws Exception {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(contactId, "contactId");
        int safeLimit = Math.max(1, Math.min(100, limit <= 0 ? 50 : limit));
        return database.read(connection -> {
            String sql = "select n.id,n.contact_id,n.company_id,n.phone_identity_id,n.occurred_at,n.summary,n.next_step,n.created_by "
                    + "from phone_notes n where n.contact_id=? and (n.created_by=? "
                    + "or exists (select 1 from contacts c where c.id=n.contact_id and c.created_by=?) "
                    + "or exists (select 1 from contact_identities ci join conversations cv on cv.contact_identity_id=ci.id "
                    + "where ci.contact_id=n.contact_id and (cv.assigned_user_id=? "
                    + "or exists (select 1 from team_members tm where tm.team_id=cv.assigned_team_id and tm.user_id=?) "
                    + "or exists (select 1 from conversation_access_grants g where g.conversation_id=cv.id and g.user_id=? "
                    + "and g.revoked_at is null and (g.expires_at is null or g.expires_at>now())))) "
                    + "or exists (select 1 from company_contacts cc join companies co on co.id=cc.company_id "
                    + "where cc.contact_id=n.contact_id and co.owner_id=? and co.deleted_at is null)) "
                    + (cursor == null ? "" : "and (n.occurred_at < ? or (n.occurred_at = ? and n.id < ?)) ")
                    + "order by n.occurred_at desc,n.id desc limit ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                int i = 1;
                statement.setObject(i++, contactId);
                for (int count = 0; count < 6; count++) statement.setObject(i++, userId);
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

    private static void setUuid(PreparedStatement statement, int index, UUID value) throws Exception {
        if (value == null) statement.setNull(index, Types.OTHER); else statement.setObject(index, value);
    }

    private static void setText(PreparedStatement statement, int index, String value) throws Exception {
        if (value == null) statement.setNull(index, Types.VARCHAR); else statement.setString(index, value);
    }
}
