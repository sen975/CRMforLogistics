package com.crmforlogistics.messagecenter;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.sql.Types;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public final class JdbcContactRepository implements ContactRepository {
    private static final int MAX_LIMIT = 100;
    private final Database database;

    public JdbcContactRepository(Database database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    @Override
    public List<UnifiedContact> listForUser(UUID userId, ContactQuery query) throws Exception {
        Objects.requireNonNull(userId, "userId");
        ContactQuery safe = query == null ? new ContactQuery("", null, null, 50) : query;
        int limit = Math.max(1, Math.min(MAX_LIMIT, safe.limit() <= 0 ? 50 : safe.limit()));
        return database.read(connection -> {
            String search = safe.search() == null ? "" : safe.search().trim();
            StringBuilder sql = new StringBuilder("""
                    select visible.id,visible.display_name,visible.remark
                    from (
                      select c.id,c.display_name,c.remark,
                             coalesce((select max(m.occurred_at)
                                       from contact_identities ci
                                       join conversations cv on cv.contact_identity_id=ci.id
                                       join messages m on m.conversation_id=cv.id
                                       where ci.contact_id=c.id and (
                                           cv.assigned_user_id=?
                                           or exists (select 1 from team_members tm
                                                      where tm.team_id=cv.assigned_team_id and tm.user_id=?)
                                           or exists (select 1 from conversation_access_grants g
                                                      where g.conversation_id=cv.id and g.user_id=?
                                                        and g.revoked_at is null
                                                        and (g.expires_at is null or g.expires_at>now())))),
                                      c.updated_at) as sort_at
                      from contacts c
                      where c.deleted_at is null and c.status <> 'merged'
                        and (c.created_by = ? or exists (
                             select 1 from contact_identities ci
                             join conversations cv on cv.contact_identity_id = ci.id
                             where ci.contact_id = c.id
                               and (cv.assigned_user_id = ? or exists (
                                    select 1 from team_members tm
                                    where tm.team_id = cv.assigned_team_id and tm.user_id = ?)
                                   or exists (select 1 from conversation_access_grants g
                                              where g.conversation_id = cv.id and g.user_id = ?
                                                and g.revoked_at is null
                                                and (g.expires_at is null or g.expires_at > now())))))
                    """);
            if (!search.isBlank()) {
                sql.append(" and (c.display_name ilike ? or coalesce(c.remark, '') ilike ?)");
            }
            sql.append(") visible where 1=1");
            if (safe.beforeLastMessageAt() != null && safe.beforeId() != null) {
                sql.append(" and (visible.sort_at < ? or (visible.sort_at = ? and visible.id < ?))");
            }
            sql.append(" order by visible.sort_at desc,visible.id desc limit ?");
            try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                int i = 1;
                for (int count = 0; count < 7; count++) statement.setObject(i++, userId);
                if (!search.isBlank()) {
                    statement.setString(i++, "%" + search + "%");
                    statement.setString(i++, "%" + search + "%");
                }
                if (safe.beforeLastMessageAt() != null && safe.beforeId() != null) {
                    statement.setTimestamp(i++, Timestamp.from(safe.beforeLastMessageAt()));
                    statement.setTimestamp(i++, Timestamp.from(safe.beforeLastMessageAt()));
                    statement.setObject(i++, safe.beforeId());
                }
                statement.setInt(i, limit);
                try (ResultSet rows = statement.executeQuery()) {
                    List<UnifiedContact> result = new ArrayList<>();
                    while (rows.next()) {
                        UnifiedContact contact = new UnifiedContact();
                        contact.id = rows.getObject(1, UUID.class).toString();
                        contact.displayName = rows.getString(2);
                        contact.remark = rows.getString(3);
                        loadProjection(connection, contact, userId);
                        result.add(contact);
                    }
                    return result;
                }
            }
        });
    }

    @Override
    public UnifiedContact findForUser(UUID userId, UUID contactId) throws Exception {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(contactId, "contactId");
        return database.read(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    select c.id,c.display_name,c.remark
                    from contacts c
                    where c.id=? and c.deleted_at is null and c.status<>'merged'
                      and (c.created_by=? or exists (
                           select 1 from contact_identities ci
                           join conversations cv on cv.contact_identity_id=ci.id
                           where ci.contact_id=c.id and (
                               cv.assigned_user_id=?
                               or exists (select 1 from team_members tm
                                          where tm.team_id=cv.assigned_team_id and tm.user_id=?)
                               or exists (select 1 from conversation_access_grants g
                                          where g.conversation_id=cv.id and g.user_id=?
                                            and g.revoked_at is null
                                            and (g.expires_at is null or g.expires_at>now())))))
                    """)) {
                statement.setObject(1, contactId);
                for (int i = 2; i <= 5; i++) statement.setObject(i, userId);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) return null;
                    UnifiedContact contact = new UnifiedContact();
                    contact.id = rows.getObject(1, UUID.class).toString();
                    contact.displayName = rows.getString(2);
                    contact.remark = rows.getString(3);
                    loadProjection(connection, contact, userId);
                    return contact;
                }
            }
        });
    }

    private static void loadProjection(java.sql.Connection connection, UnifiedContact contact,
                                       UUID userId) throws Exception {
        UUID contactId = UUID.fromString(contact.id);
        LinkedHashSet<String> channels = new LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                select id,channel_type,identity_value,display_name
                from contact_identities
                where contact_id=? and deleted_at is null
                order by is_primary desc,created_at,id
                """)) {
            statement.setObject(1, contactId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String channel = rows.getString(2);
                    channels.add(channel);
                    contact.points.add(new ContactPoint(rows.getObject(1, UUID.class).toString(),
                            channel, channel, rows.getString(3),
                            ContactPointUtil.firstNonBlank(rows.getString(4), rows.getString(3))));
                }
            }
        }
        contact.channels.addAll(channels);
        try (PreparedStatement statement = connection.prepareStatement("""
                select t.name from contact_taggings ct
                join contact_tags t on t.id=ct.tag_id
                where ct.contact_id=? and t.status='active'
                order by lower(t.name),t.id
                """)) {
            statement.setObject(1, contactId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) contact.tags.add(rows.getString(1));
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                select m.body_text,m.occurred_at,m.direction,ca.channel_type,count(*) over()
                from contact_identities ci
                join conversations cv on cv.contact_identity_id=ci.id
                join messages m on m.conversation_id=cv.id
                join channel_accounts ca on ca.id=m.channel_account_id
                where ci.contact_id=? and (
                    cv.assigned_user_id=?
                    or exists (select 1 from team_members tm
                               where tm.team_id=cv.assigned_team_id and tm.user_id=?)
                    or exists (select 1 from conversation_access_grants g
                               where g.conversation_id=cv.id and g.user_id=?
                                 and g.revoked_at is null
                                 and (g.expires_at is null or g.expires_at>now())))
                order by m.occurred_at desc,m.id desc limit 1
                """)) {
            statement.setObject(1, contactId);
            statement.setObject(2, userId);
            statement.setObject(3, userId);
            statement.setObject(4, userId);
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next()) {
                    contact.lastText = rows.getString(1);
                    contact.lastTime = rows.getTimestamp(2).toInstant().toString();
                    contact.lastDirection = rows.getString(3);
                    contact.lastChannel = rows.getString(4);
                    contact.messageCount = rows.getInt(5);
                }
            }
        }
    }

    @Override
    public UUID create(String displayName, UUID actorId) throws Exception {
        String name = requireText(displayName, "displayName", 100);
        UUID id = UUID.randomUUID();
        database.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "insert into contacts (id, display_name, created_by) values (?, ?, ?)")) {
                statement.setObject(1, id);
                statement.setString(2, name);
                if (actorId == null) statement.setNull(3, Types.OTHER); else statement.setObject(3, actorId);
                statement.executeUpdate();
            }
            return null;
        });
        return id;
    }

    @Override
    public UUID attachIdentity(UUID contactId, ContactIdentityDraft identity) throws Exception {
        Objects.requireNonNull(contactId, "contactId");
        Objects.requireNonNull(identity, "identity");
        String type = normalizeType(identity.channelType());
        String scope = identity.identityScope() == null || identity.identityScope().isBlank()
                ? "global" : requireText(identity.identityScope(), "identityScope", 255);
        String value = requireText(identity.identityValue(), "identityValue", 255);
        String normalized = normalizeIdentity(type, value);
        UUID id = UUID.randomUUID();
        database.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    insert into contact_identities
                        (id, contact_id, channel_type, identity_scope, identity_value,
                         normalized_value, display_name)
                    values (?, ?, ?, ?, ?, ?, ?)
                    """)) {
                statement.setObject(1, id);
                statement.setObject(2, contactId);
                statement.setString(3, type);
                statement.setString(4, scope);
                statement.setString(5, value);
                statement.setString(6, normalized);
                if (identity.displayName() == null || identity.displayName().isBlank()) {
                    statement.setNull(7, Types.VARCHAR);
                } else {
                    statement.setString(7, identity.displayName().trim());
                }
                statement.executeUpdate();
            }
            return null;
        });
        return id;
    }

    @Override
    public ContactIdentity findIdentity(UUID identityId) throws Exception {
        Objects.requireNonNull(identityId, "identityId");
        return database.read(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    select id, contact_id, channel_type, identity_scope, identity_value,
                           normalized_value, display_name
                    from contact_identities where id = ? and deleted_at is null
                    """)) {
                statement.setObject(1, identityId);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) throw new IllegalArgumentException("Contact identity not found");
                    return new ContactIdentity(rows.getObject(1, UUID.class), rows.getObject(2, UUID.class),
                            rows.getString(3), rows.getString(4), rows.getString(5), rows.getString(6), rows.getString(7));
                }
            }
        });
    }

    @Override
    public void merge(UUID targetContactId, UUID sourceContactId, UUID actorId) throws Exception {
        if (Objects.equals(targetContactId, sourceContactId)) throw new IllegalArgumentException("Contacts must differ");
        database.transaction(connection -> {
            lockContact(connection, targetContactId);
            lockContact(connection, sourceContactId);
            try (PreparedStatement statement = connection.prepareStatement(
                    "update contact_identities set contact_id = ?, updated_at = now(), version = version + 1 where contact_id = ?")) {
                statement.setObject(1, targetContactId);
                statement.setObject(2, sourceContactId);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    update contacts set status = 'merged', merged_to_id = ?, updated_at = now(), version = version + 1
                    where id = ?
                    """)) {
                statement.setObject(1, targetContactId);
                statement.setObject(2, sourceContactId);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    insert into contact_taggings (contact_id, tag_id)
                    select ?, tag_id from contact_taggings where contact_id = ?
                    on conflict do nothing
                    """)) {
                statement.setObject(1, targetContactId);
                statement.setObject(2, sourceContactId);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "delete from contact_taggings where contact_id = ?")) {
                statement.setObject(1, sourceContactId);
                statement.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public UUID splitIdentity(UUID identityId, String displayName, UUID actorId) throws Exception {
        Objects.requireNonNull(identityId, "identityId");
        String name = requireText(displayName, "displayName", 100);
        UUID newContact = UUID.randomUUID();
        database.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "select id from contact_identities where id = ? and deleted_at is null for update")) {
                statement.setObject(1, identityId);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) throw new IllegalArgumentException("Contact identity not found");
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "insert into contacts (id, display_name, created_by) values (?, ?, ?)")) {
                statement.setObject(1, newContact);
                statement.setString(2, name);
                if (actorId == null) statement.setNull(3, Types.OTHER); else statement.setObject(3, actorId);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "update contact_identities set contact_id = ?, updated_at = now(), version = version + 1 where id = ?")) {
                statement.setObject(1, newContact);
                statement.setObject(2, identityId);
                statement.executeUpdate();
            }
            return null;
        });
        return newContact;
    }

    @Override
    public void updateProfile(UUID contactId, String displayName, String remark,
                              List<String> tags, UUID actorId) throws Exception {
        Objects.requireNonNull(contactId, "contactId");
        String name = requireText(displayName, "displayName", 100);
        database.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "update contacts set display_name = ?, remark = ?, updated_at = now(), version = version + 1 where id = ?")) {
                statement.setString(1, name);
                if (remark == null) statement.setNull(2, Types.VARCHAR); else statement.setString(2, remark);
                statement.setObject(3, contactId);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("delete from contact_taggings where contact_id = ?")) {
                statement.setObject(1, contactId);
                statement.executeUpdate();
            }
            if (tags != null) {
                for (String raw : tags) {
                    if (raw == null || raw.isBlank()) continue;
                    String tag = requireText(raw.trim(), "tag", 100);
                    UUID tagId = UUID.randomUUID();
                    try (PreparedStatement statement = connection.prepareStatement("""
                            insert into contact_tags (id, name) values (?, ?)
                            on conflict ((lower(name))) do update set name = excluded.name
                            """)) {
                        statement.setObject(1, tagId);
                        statement.setString(2, tag);
                        statement.executeUpdate();
                    }
                    try (PreparedStatement statement = connection.prepareStatement(
                            "select id from contact_tags where lower(name) = lower(?)")) {
                        statement.setString(1, tag);
                        try (ResultSet rows = statement.executeQuery()) {
                            if (rows.next()) {
                                try (PreparedStatement link = connection.prepareStatement(
                                        "insert into contact_taggings (contact_id, tag_id) values (?, ?) on conflict do nothing")) {
                                    link.setObject(1, contactId);
                                    link.setObject(2, rows.getObject(1, UUID.class));
                                    link.executeUpdate();
                                }
                            }
                        }
                    }
                }
            }
            return null;
        });
    }

    private static void lockContact(java.sql.Connection connection, UUID id) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("select id from contacts where id = ? for update")) {
            statement.setObject(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new IllegalArgumentException("Contact not found");
            }
        }
    }

    private static String normalizeType(String value) {
        String type = requireText(value, "channelType", 30).toLowerCase(Locale.ROOT);
        if (!List.of("email", "whatsapp", "wecom", "phone").contains(type)) throw new IllegalArgumentException("Unsupported identity channel");
        return type;
    }

    private static String normalizeIdentity(String type, String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).trim();
        if ("email".equals(type)) return normalized.toLowerCase(Locale.ROOT);
        return normalized.replaceAll("[\\s()-]", "").toLowerCase(Locale.ROOT);
    }

    private static String requireText(String value, String field, int max) {
        if (value == null || value.isBlank() || value.length() > max) throw new IllegalArgumentException(field + " is invalid");
        return value.trim();
    }
}
