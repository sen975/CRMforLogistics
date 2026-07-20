package com.crmforlogistics.messagecenter;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface PhoneNoteRepository {
    UUID create(PhoneNoteDraft draft, UUID actorId) throws Exception;
    List<PhoneNote> listByContact(UUID userId, UUID contactId, MessageCursor cursor, int limit) throws Exception;
}

record PhoneNoteDraft(UUID contactId, UUID companyId, UUID phoneIdentityId,
                      Instant occurredAt, String summary, String nextStep) {}

record PhoneNote(UUID id, UUID contactId, UUID companyId, UUID phoneIdentityId,
                 Instant occurredAt, String summary, String nextStep, UUID createdBy) {}
