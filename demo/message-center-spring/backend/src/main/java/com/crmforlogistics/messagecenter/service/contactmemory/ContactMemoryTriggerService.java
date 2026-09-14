package com.crmforlogistics.messagecenter.service.contactmemory;

import com.crmforlogistics.messagecenter.entity.ContactMemoryTriggerEventEntity;
import com.crmforlogistics.messagecenter.mapper.ContactMemoryStateMapper;
import com.crmforlogistics.messagecenter.mapper.ContactMemoryTriggerEventMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class ContactMemoryTriggerService {
    private static final Logger log = LoggerFactory.getLogger(ContactMemoryTriggerService.class);

    private final ContactMemoryStateMapper states;
    private final ContactMemoryTriggerEventMapper events;

    public ContactMemoryTriggerService(ContactMemoryStateMapper states,
                                       ContactMemoryTriggerEventMapper events) {
        this.states = states;
        this.events = events;
    }

    @Transactional
    public void markInboundPersisted(UUID contactId,
                                     UUID messageId,
                                     Long ingestSequence,
                                     Instant occurredAt,
                                     Instant receivedAt) {
        if (contactId == null || messageId == null) {
            throw new IllegalArgumentException("CONTACT_MEMORY_TRIGGER_INPUT_REQUIRED");
        }
        events.enqueue(contactId, messageId, ingestSequence, occurredAt, receivedAt);
    }

    public int replayDue(Instant now, int limit) {
        if (now == null || limit <= 0) {
            return 0;
        }
        String leaseOwner = "contact-memory-trigger-" + UUID.randomUUID();
        List<ContactMemoryTriggerEventEntity> claimed = events.claimDue(
                now, leaseOwner, now.plusSeconds(60), limit);
        for (ContactMemoryTriggerEventEntity event : claimed) {
            apply(event, now);
        }
        return claimed.size();
    }

    private void apply(ContactMemoryTriggerEventEntity event, Instant now) {
        try {
            states.markDirty(event.getContactId(), event.getOwnerUserId(), event.getReceivedAt());
            if (events.markApplied(event.getId(), event.getLeaseToken()) != 1) {
                log.warn("contact memory trigger lease lost eventId={}", event.getId());
            }
        } catch (RuntimeException exception) {
            int previous = event.getAttemptCount() == null ? 0 : event.getAttemptCount();
            int attempt = Math.min(previous + 1, 3);
            boolean terminal = attempt >= 3;
            try {
                events.markFailed(event.getId(), event.getLeaseToken(),
                        "STATE_MARK_DIRTY_FAILED", bounded(exception.getMessage()), attempt,
                        terminal ? now : now.plusSeconds(60L * attempt), terminal);
            } catch (RuntimeException failure) {
                log.warn("contact memory trigger failure update failed eventId={}", event.getId(), failure);
            }
        }
    }

    private static String bounded(String value) {
        if (value == null || value.isBlank()) {
            return "CONTACT_MEMORY_TRIGGER_FAILED";
        }
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }
}
