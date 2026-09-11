package com.crmforlogistics.messagecenter.service.contactmemory;

import com.crmforlogistics.messagecenter.mapper.ContactMapper;
import com.crmforlogistics.messagecenter.mapper.ContactMemoryStateMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class ContactMemoryTriggerService {
    private static final Logger log = LoggerFactory.getLogger(ContactMemoryTriggerService.class);

    private final ContactMapper contacts;
    private final ContactMemoryStateMapper states;

    public ContactMemoryTriggerService(ContactMapper contacts, ContactMemoryStateMapper states) {
        this.contacts = contacts;
        this.states = states;
    }

    public void markInboundPersisted(UUID contactId, Instant occurredAt) {
        if (contactId == null || occurredAt == null) {
            return;
        }
        try {
            contacts.findCreatedBy(contactId).ifPresentOrElse(
                    ownerId -> states.markDirty(contactId, ownerId, occurredAt),
                    () -> log.warn("contact memory trigger skipped: owner missing contactId={}", contactId));
        } catch (RuntimeException exception) {
            log.warn("contact memory trigger failed contactId={}", contactId, exception);
        }
    }
}
