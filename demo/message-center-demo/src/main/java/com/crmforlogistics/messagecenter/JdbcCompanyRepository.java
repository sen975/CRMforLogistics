package com.crmforlogistics.messagecenter;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class JdbcCompanyRepository implements CompanyRepository {
    private static final int MAX_LIMIT = 100;
    private final Database database;

    public JdbcCompanyRepository(Database database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    @Override
    public List<Company> listForUser(UUID userId, CompanyQuery query) throws Exception {
        Objects.requireNonNull(userId, "userId");
        CompanyQuery safe = query == null ? new CompanyQuery("", null, null, 50) : query;
        int limit = Math.max(1, Math.min(MAX_LIMIT, safe.limit() <= 0 ? 50 : safe.limit()));
        return database.read(connection -> {
            String search = safe.search() == null ? "" : safe.search().trim();
            String sql = "select id,name,type_code,country,city,website,owner_id,remark,status from companies "
                    + "where deleted_at is null and owner_id = ? "
                    + (search.isBlank() ? "" : "and (name ilike ? or coalesce(remark,'') ilike ?) ")
                    + (safe.beforeUpdatedAt() != null && safe.beforeId() != null
                       ? "and (updated_at < ? or (updated_at = ? and id < ?)) " : "")
                    + "order by updated_at desc,id limit ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                int i = 1;
                statement.setObject(i++, userId);
                if (!search.isBlank()) {
                    statement.setString(i++, "%" + search + "%");
                    statement.setString(i++, "%" + search + "%");
                }
                if (safe.beforeUpdatedAt() != null && safe.beforeId() != null) {
                    statement.setTimestamp(i++, java.sql.Timestamp.from(safe.beforeUpdatedAt()));
                    statement.setTimestamp(i++, java.sql.Timestamp.from(safe.beforeUpdatedAt()));
                    statement.setObject(i++, safe.beforeId());
                }
                statement.setInt(i, limit);
                try (ResultSet rows = statement.executeQuery()) {
                    List<Company> result = new ArrayList<>();
                    while (rows.next()) result.add(readCompany(rows));
                    return result;
                }
            }
        });
    }

    @Override
    public Company findForUser(UUID userId, UUID companyId) throws Exception {
        return database.read(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    select id,name,type_code,country,city,website,owner_id,remark,status
                    from companies where id = ? and owner_id = ? and deleted_at is null
                    """)) {
                statement.setObject(1, companyId);
                statement.setObject(2, userId);
                try (ResultSet rows = statement.executeQuery()) {
                    return rows.next() ? readCompany(rows) : null;
                }
            }
        });
    }

    @Override
    public CompanyDetails detailsForUser(UUID userId, UUID companyId) throws Exception {
        Company company = findForUser(userId, companyId);
        if (company == null) {
            throw new IllegalArgumentException("Company not found or unauthorized");
        }
        List<CompanyContactLink> contacts = listLinkedContacts(userId, companyId);
        List<UUID> conversationIds = database.read(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    select distinct cv.id
                    from company_contacts cc
                    join contact_identities ci on ci.contact_id=cc.contact_id and ci.deleted_at is null
                    join conversations cv on cv.contact_identity_id=ci.id
                    where cc.company_id=? and (
                        cv.assigned_user_id=?
                        or exists (select 1 from team_members tm
                                   where tm.team_id=cv.assigned_team_id and tm.user_id=?)
                        or exists (select 1 from conversation_access_grants g
                                   where g.conversation_id=cv.id and g.user_id=?
                                     and g.revoked_at is null
                                     and (g.expires_at is null or g.expires_at>now())))
                    order by cv.id
                    """)) {
                statement.setObject(1, companyId);
                statement.setObject(2, userId);
                statement.setObject(3, userId);
                statement.setObject(4, userId);
                try (ResultSet rows = statement.executeQuery()) {
                    List<UUID> result = new ArrayList<>();
                    while (rows.next()) result.add(rows.getObject(1, UUID.class));
                    return result;
                }
            }
        });
        return new CompanyDetails(company, List.copyOf(contacts), List.copyOf(conversationIds));
    }

    @Override
    public UUID create(CompanyDraft draft, UUID actorId) throws Exception {
        Objects.requireNonNull(draft, "draft");
        UUID id = UUID.randomUUID();
        database.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    insert into companies (id,name,type_code,country,city,website,owner_id,remark,created_by)
                    values (?,?,?,?,?,?,?,?,?)
                    """)) {
                statement.setObject(1, id);
                statement.setString(2, text(draft.name(), "name", 255));
                setText(statement, 3, draft.typeCode());
                setText(statement, 4, draft.country());
                setText(statement, 5, draft.city());
                setText(statement, 6, draft.website());
                setUuid(statement, 7, draft.ownerId());
                setText(statement, 8, draft.remark());
                setUuid(statement, 9, actorId);
                statement.executeUpdate();
            }
            return null;
        });
        return id;
    }

    @Override
    public void update(UUID companyId, CompanyPatch patch, UUID actorId) throws Exception {
        Objects.requireNonNull(patch, "patch");
        database.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    update companies set name=?,type_code=?,country=?,city=?,website=?,owner_id=?,remark=?,status=?,updated_at=now(),version=version+1
                    where id=? and (owner_id=? or created_by=?) and deleted_at is null
                    """)) {
                statement.setString(1, text(patch.name(), "name", 255));
                setText(statement, 2, patch.typeCode());
                setText(statement, 3, patch.country());
                setText(statement, 4, patch.city());
                setText(statement, 5, patch.website());
                setUuid(statement, 6, patch.ownerId());
                setText(statement, 7, patch.remark());
                statement.setString(8, patch.status() == null || patch.status().isBlank() ? "active" : patch.status());
                statement.setObject(9, companyId);
                setUuid(statement, 10, actorId);
                setUuid(statement, 11, actorId);
                if (statement.executeUpdate() == 0) throw new IllegalArgumentException("Company not found or unauthorized");
            }
            return null;
        });
    }

    @Override
    public void linkContact(UUID companyId, UUID contactId, String relationType,
                            boolean primary, String remark, UUID actorId) throws Exception {
        database.transaction(connection -> {
            requireCompanyOwner(connection, companyId, actorId);
            try (PreparedStatement statement = connection.prepareStatement("""
                    insert into company_contacts (company_id,contact_id,relation_type,is_primary,remark,created_by)
                    values (?,?,?,?,?,?) on conflict (company_id,contact_id)
                    do update set relation_type=excluded.relation_type,is_primary=excluded.is_primary,remark=excluded.remark
                    """)) {
                statement.setObject(1, companyId);
                statement.setObject(2, contactId);
                statement.setString(3, relationType == null || relationType.isBlank() ? "contact" : relationType);
                statement.setBoolean(4, primary);
                setText(statement, 5, remark);
                setUuid(statement, 6, actorId);
                statement.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public void unlinkContact(UUID companyId, UUID contactId, UUID actorId) throws Exception {
        database.transaction(connection -> {
            requireCompanyOwner(connection, companyId, actorId);
            try (PreparedStatement statement = connection.prepareStatement(
                    "delete from company_contacts where company_id=? and contact_id=?")) {
                statement.setObject(1, companyId);
                statement.setObject(2, contactId);
                statement.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public List<CompanyContactLink> listLinkedContacts(UUID userId, UUID companyId) throws Exception {
        return database.read(connection -> {
            requireCompanyOwner(connection, companyId, userId);
            try (PreparedStatement statement = connection.prepareStatement("""
                    select cc.company_id,cc.contact_id,c.display_name,cc.relation_type,cc.is_primary,cc.remark
                    from company_contacts cc join contacts c on c.id=cc.contact_id
                    where cc.company_id=? and c.deleted_at is null order by cc.is_primary desc,c.display_name,cc.contact_id
                    """)) {
                statement.setObject(1, companyId);
                try (ResultSet rows = statement.executeQuery()) {
                    List<CompanyContactLink> result = new ArrayList<>();
                    while (rows.next()) result.add(new CompanyContactLink(rows.getObject(1, UUID.class), rows.getObject(2, UUID.class),
                            rows.getString(3), rows.getString(4), rows.getBoolean(5), rows.getString(6)));
                    return result;
                }
            }
        });
    }

    private static Company readCompany(ResultSet rows) throws Exception {
        return new Company(rows.getObject(1, UUID.class), rows.getString(2), rows.getString(3), rows.getString(4),
                rows.getString(5), rows.getString(6), rows.getObject(7, UUID.class), rows.getString(8), rows.getString(9));
    }

    private static void requireCompanyOwner(java.sql.Connection connection, UUID companyId, UUID userId) throws Exception {
        if (userId == null) return;
        try (PreparedStatement statement = connection.prepareStatement("select 1 from companies where id=? and owner_id=? and deleted_at is null")) {
            statement.setObject(1, companyId);
            statement.setObject(2, userId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new IllegalArgumentException("Company not found or unauthorized");
            }
        }
    }

    private static String text(String value, String field, int max) {
        if (value == null || value.isBlank() || value.length() > max) throw new IllegalArgumentException(field + " is invalid");
        return value.trim();
    }

    private static void setUuid(PreparedStatement statement, int index, UUID value) throws Exception {
        if (value == null) statement.setNull(index, Types.OTHER); else statement.setObject(index, value);
    }

    private static void setText(PreparedStatement statement, int index, String value) throws Exception {
        if (value == null) statement.setNull(index, Types.VARCHAR); else statement.setString(index, value);
    }
}
