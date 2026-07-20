package com.crmforlogistics.messagecenter;

import java.util.List;
import java.util.UUID;

public interface ContactRepository {
    List<UnifiedContact> listForUser(UUID userId, ContactQuery query) throws Exception;
    UUID create(String displayName, UUID actorId) throws Exception;
    UUID attachIdentity(UUID contactId, ContactIdentityDraft identity) throws Exception;
    ContactIdentity findIdentity(UUID identityId) throws Exception;
    void merge(UUID targetContactId, UUID sourceContactId, UUID actorId) throws Exception;
    UUID splitIdentity(UUID identityId, String displayName, UUID actorId) throws Exception;
    void updateProfile(UUID contactId, String displayName, String remark,
                       List<String> tags, UUID actorId) throws Exception;
}

record ContactQuery(String search, java.time.Instant beforeLastMessageAt, UUID beforeId, int limit) {}

record ContactIdentityDraft(String channelType, String identityScope,
                            String identityValue, String displayName) {}

record ContactIdentity(UUID id, UUID contactId, String channelType, String identityScope,
                       String identityValue, String normalizedValue, String displayName) {}
