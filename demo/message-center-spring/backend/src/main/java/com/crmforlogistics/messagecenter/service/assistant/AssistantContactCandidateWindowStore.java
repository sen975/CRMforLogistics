package com.crmforlogistics.messagecenter.service.assistant;

import com.crmforlogistics.messagecenter.config.ConditionalOnAssistantEnabled;
import com.crmforlogistics.messagecenter.mapper.AssistantContactCandidateWindowMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Only persists bounded references; contact fields and access are refreshed by the owner on every read. */
@Component
@ConditionalOnAssistantEnabled
public class AssistantContactCandidateWindowStore {
    private static final Logger log = LoggerFactory.getLogger(AssistantContactCandidateWindowStore.class);
    private static final Duration TTL = Duration.ofMinutes(30);
    private static final int MAX_JSON_BYTES = 2048;

    private final AssistantContactCandidateWindowMapper mapper;
    private final ContactCandidateProvider contacts;
    private final ObjectMapper json;
    private final Clock clock;

    public AssistantContactCandidateWindowStore(AssistantContactCandidateWindowMapper mapper,
                                                ContactCandidateProvider contacts, ObjectMapper json, Clock clock) {
        this.mapper = mapper;
        this.contacts = contacts;
        this.json = json;
        this.clock = clock;
    }

    public void remember(UUID userId, UUID conversationId, ContactCandidates candidates) {
        if (userId == null || conversationId == null || candidates == null) return;
        List<String> refs = candidates.items().stream().map(ContactCandidates.Item::id).toList();
        if (!valid(refs)) return;
        try {
            String serialized = json.writeValueAsString(refs);
            if (serialized.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_JSON_BYTES) return;
            Instant now = clock.instant();
            mapper.upsert(userId, conversationId, serialized, now, now.plus(TTL));
        } catch (Exception e) {
            log.warn("event=assistant.contact_candidates_save_failed userId={} conversationId={}",
                    userId, conversationId, e);
        }
    }

    public ContactCandidates restore(UUID userId, UUID conversationId) {
        if (userId == null || conversationId == null) return null;
        try {
            String serialized = mapper.findFresh(userId, conversationId, clock.instant());
            if (serialized == null || serialized.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_JSON_BYTES) {
                return null;
            }
            List<String> refs = json.readValue(serialized, new TypeReference<List<String>>() {});
            if (!valid(refs)) return null;
            ContactCandidates refreshed = contacts.refresh(userId, refs);
            return refreshed.items().isEmpty() ? null : refreshed;
        } catch (Exception e) {
            log.warn("event=assistant.contact_candidates_restore_failed userId={} conversationId={}",
                    userId, conversationId, e);
            return null;
        }
    }

    @Scheduled(fixedDelay = 3_600_000)
    public void deleteExpired() {
        try {
            mapper.deleteExpired(clock.instant());
        } catch (RuntimeException e) {
            log.warn("event=assistant.contact_candidates_cleanup_failed", e);
        }
    }

    private static boolean valid(List<String> refs) {
        return refs != null && !refs.isEmpty() && refs.size() <= ContactCandidateProvider.LIMIT
                && refs.stream().allMatch(ref -> ContactCandidates.targetOf(ref) != null)
                && refs.stream().distinct().count() == refs.size();
    }
}
