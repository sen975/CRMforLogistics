package com.crmforlogistics.messagecenter;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface CompanyRepository {
    List<Company> listForUser(UUID userId, CompanyQuery query) throws Exception;
    Company findForUser(UUID userId, UUID companyId) throws Exception;
    CompanyDetails detailsForUser(UUID userId, UUID companyId) throws Exception;
    UUID create(CompanyDraft draft, UUID actorId) throws Exception;
    void update(UUID companyId, CompanyPatch patch, UUID actorId) throws Exception;
    void linkContact(UUID companyId, UUID contactId, String relationType,
                     boolean primary, String remark, UUID actorId) throws Exception;
    void unlinkContact(UUID companyId, UUID contactId, UUID actorId) throws Exception;
    List<CompanyContactLink> listLinkedContacts(UUID userId, UUID companyId) throws Exception;
}

record CompanyQuery(String search, Instant beforeUpdatedAt, UUID beforeId, int limit) {}

record CompanyDraft(String name, String typeCode, String country, String city,
                    String website, UUID ownerId, String remark) {}

record CompanyPatch(String name, String typeCode, String country, String city,
                    String website, UUID ownerId, String remark, String status) {}

record Company(UUID id, String name, String typeCode, String country, String city,
               String website, UUID ownerId, String remark, String status) {}

record CompanyContactLink(UUID companyId, UUID contactId, String contactName,
                          String relationType, boolean primary, String remark) {}

record CompanyDetails(Company company, List<CompanyContactLink> contacts,
                      List<UUID> conversationIds) {}
