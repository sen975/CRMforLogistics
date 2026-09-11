package com.crmforlogistics.messagecenter.service.contactmemory;

import com.crmforlogistics.messagecenter.config.ContactMemoryConfig;
import com.crmforlogistics.messagecenter.entity.AiTopicEntity;
import com.crmforlogistics.messagecenter.entity.CallTranscriptRevisionEntity;
import com.crmforlogistics.messagecenter.entity.ContactAiLabelEntity;
import com.crmforlogistics.messagecenter.entity.ContactMemoryFactEntity;
import com.crmforlogistics.messagecenter.entity.ContactMemoryObservationEntity;
import com.crmforlogistics.messagecenter.entity.ContactMemoryStateEntity;
import com.crmforlogistics.messagecenter.entity.ContactProfileVersionEntity;
import com.crmforlogistics.messagecenter.entity.MessageEntity;
import com.crmforlogistics.messagecenter.mapper.ContactMapper;
import com.crmforlogistics.messagecenter.mapper.ContactMemoryMapper;
import com.crmforlogistics.messagecenter.mapper.ContactMemoryStateMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class ContactMemoryContextService {
    private final ContactMapper contacts;
    private final ContactMemoryStateMapper states;
    private final ContactMemoryMapper memory;
    private final ContactMemoryConfig config;

    public ContactMemoryContextService(ContactMapper contacts,
                                       ContactMemoryStateMapper states,
                                       ContactMemoryMapper memory) {
        this(contacts, states, memory, new ContactMemoryConfig(
                50, 4000, 50000, 20, 1000, 10, 4000,
                100, 100, 100, 500, 30));
    }

    public ContactMemoryContextService(ContactMapper contacts,
                                       ContactMemoryStateMapper states,
                                       ContactMemoryMapper memory,
                                       ContactMemoryConfig config) {
        this.contacts = contacts;
        this.states = states;
        this.memory = memory;
        this.config = config;
    }

    public ContactMemoryModels.Context load(UUID ownerUserId, UUID contactId, Instant cutoff) {
        if (ownerUserId == null || contactId == null || cutoff == null) {
            throw new ContactMemoryModels.ValidationException("OWNER_MISMATCH");
        }
        UUID actualOwner = contacts.findCreatedBy(contactId)
                .orElseThrow(() -> new ContactMemoryModels.ValidationException("OWNER_NOT_FOUND"));
        if (!ownerUserId.equals(actualOwner)) {
            throw new ContactMemoryModels.ValidationException("OWNER_MISMATCH");
        }

        ContactMemoryStateEntity state = states.findByOwnerAndContact(ownerUserId, contactId).orElse(null);
        String inputCursor = state == null ? null : state.getLastSuccessCursor();
        CursorBoundary boundary = CursorBoundary.parse(inputCursor);
        List<MessageEntity> fetchedMessages = memory.listInboundMessagesByCursor(
                ownerUserId, contactId, boundary.occurredAt(), boundary.messageId(),
                cutoff, config.maxInboundMessages());
        List<MessageEntity> inboundMessages = boundMessages(fetchedMessages);
        String outputCursor = cursorAfterFetched(inputCursor, inboundMessages);

        ContactProfileVersionEntity profile = copyProfile(memory.findCurrentProfile(ownerUserId, contactId));
        List<ContactMemoryObservationEntity> observations = memory.listActiveObservations(
                        ownerUserId, contactId, config.maxObservations())
                .stream().map(this::copyObservation).toList();
        List<ContactMemoryFactEntity> facts = memory.listActiveFacts(
                        ownerUserId, contactId, config.maxFacts())
                .stream().map(ContactMemoryContextService::copyFact).toList();
        List<ContactAiLabelEntity> labels = memory.listActiveLabels(
                        ownerUserId, contactId, config.maxLabels())
                .stream().map(ContactMemoryContextService::copyLabel).toList();
        List<AiTopicEntity> topics = memory.listStableTopics(
                        ownerUserId, contactId, config.maxTopics())
                .stream().map(topic -> copyTopic(topic, config.maxTopicChars())).toList();
        List<CallTranscriptRevisionEntity> transcripts = memory.listCallTranscripts(
                        ownerUserId, contactId, config.maxCallTranscripts())
                .stream().map(transcript -> copyTranscript(transcript, config.maxTranscriptChars())).toList();

        return new ContactMemoryModels.Context(
                contactId,
                ownerUserId,
                inboundMessages,
                profile,
                observations,
                facts,
                labels,
                new ContactMemoryModels.StableContext(profile, facts, labels, topics),
                topics,
                transcripts,
                inputCursor,
                outputCursor);
    }

    private List<MessageEntity> boundMessages(List<MessageEntity> fetched) {
        List<MessageEntity> result = new ArrayList<>();
        int totalChars = 0;
        for (MessageEntity source : fetched.stream()
                .limit(config.maxInboundMessages())
                .toList()) {
            if (source == null || source.getOccurredAt() == null
                    || source.getOccurredAt().isAfter(Instant.MAX)) {
                continue;
            }
            String body = truncate(source.getBodyText(), config.maxMessageChars());
            String subject = truncate(source.getSubject(), config.maxMessageChars());
            int messageChars = body.length() + subject.length();
            if (!result.isEmpty() && totalChars + messageChars > config.maxTotalChars()) {
                continue;
            }
            if (result.isEmpty() && messageChars > config.maxTotalChars()) {
                int bodyBudget = Math.max(0, config.maxTotalChars() - subject.length());
                body = truncate(body, bodyBudget);
            }
            MessageEntity copy = copyMessage(source);
            copy.setBodyText(body);
            copy.setSubject(subject);
            result.add(copy);
            totalChars += body.length() + subject.length();
        }
        return result;
    }

    private static String cursorAfterFetched(String inputCursor, List<MessageEntity> fetched) {
        if (fetched == null || fetched.isEmpty()) {
            return inputCursor;
        }
        MessageEntity last = fetched.stream()
                .filter(Objects::nonNull)
                .max(Comparator.comparing(MessageEntity::getOccurredAt)
                        .thenComparing(MessageEntity::getId))
                .orElse(null);
        if (last == null || last.getOccurredAt() == null || last.getId() == null) {
            return inputCursor;
        }
        return CursorBoundary.encode(last.getOccurredAt(), last.getId());
    }

    private ContactProfileVersionEntity copyProfile(ContactProfileVersionEntity source) {
        if (source == null) return null;
        ContactProfileVersionEntity copy = new ContactProfileVersionEntity();
        copy.setId(source.getId());
        copy.setContactId(source.getContactId());
        copy.setOwnerUserId(source.getOwnerUserId());
        copy.setVersion(source.getVersion());
        copy.setContent(truncate(source.getContent(), 200));
        copy.setSourceCursor(source.getSourceCursor());
        copy.setGenerationBatchId(source.getGenerationBatchId());
        copy.setModel(source.getModel());
        copy.setInputMessageCount(source.getInputMessageCount());
        copy.setEvidenceCount(source.getEvidenceCount());
        copy.setIsCurrent(source.getIsCurrent());
        copy.setCreatedAt(source.getCreatedAt());
        return copy;
    }

    private ContactMemoryObservationEntity copyObservation(ContactMemoryObservationEntity source) {
        ContactMemoryObservationEntity copy = new ContactMemoryObservationEntity();
        copy.setId(source.getId());
        copy.setContactId(source.getContactId());
        copy.setOwnerUserId(source.getOwnerUserId());
        copy.setCategory(source.getCategory());
        copy.setNormalizedKey(source.getNormalizedKey());
        copy.setObservedValue(truncate(source.getObservedValue(), config.maxObservationChars()));
        copy.setPolarity(source.getPolarity());
        copy.setConfidence(source.getConfidence());
        copy.setStatus(source.getStatus());
        copy.setSourceCursor(source.getSourceCursor());
        copy.setGenerationBatchId(source.getGenerationBatchId());
        copy.setObservedAt(source.getObservedAt());
        copy.setExpiresAt(source.getExpiresAt());
        copy.setPromotedFactId(source.getPromotedFactId());
        copy.setCreatedAt(source.getCreatedAt());
        copy.setUpdatedAt(source.getUpdatedAt());
        return copy;
    }

    private static ContactMemoryFactEntity copyFact(ContactMemoryFactEntity source) {
        ContactMemoryFactEntity copy = new ContactMemoryFactEntity();
        copy.setId(source.getId());
        copy.setContactId(source.getContactId());
        copy.setOwnerUserId(source.getOwnerUserId());
        copy.setCategory(source.getCategory());
        copy.setNormalizedKey(source.getNormalizedKey());
        copy.setNormalizedValue(source.getNormalizedValue());
        copy.setDisplayValue(source.getDisplayValue());
        copy.setPolarity(source.getPolarity());
        copy.setStatus(source.getStatus());
        copy.setConfidence(source.getConfidence());
        copy.setEvidenceCount(source.getEvidenceCount());
        copy.setFirstSeenAt(source.getFirstSeenAt());
        copy.setLastSeenAt(source.getLastSeenAt());
        copy.setLastConfirmedAt(source.getLastConfirmedAt());
        copy.setStaleAt(source.getStaleAt());
        copy.setInvalidatedAt(source.getInvalidatedAt());
        copy.setGenerationBatchId(source.getGenerationBatchId());
        copy.setCreatedAt(source.getCreatedAt());
        copy.setUpdatedAt(source.getUpdatedAt());
        return copy;
    }

    private static ContactAiLabelEntity copyLabel(ContactAiLabelEntity source) {
        ContactAiLabelEntity copy = new ContactAiLabelEntity();
        copy.setId(source.getId());
        copy.setContactId(source.getContactId());
        copy.setOwnerUserId(source.getOwnerUserId());
        copy.setCategory(source.getCategory());
        copy.setNormalizedName(source.getNormalizedName());
        copy.setDisplayName(source.getDisplayName());
        copy.setColorToken(source.getColorToken());
        copy.setStatus(source.getStatus());
        copy.setConfidence(source.getConfidence());
        copy.setFirstSeenAt(source.getFirstSeenAt());
        copy.setLastSeenAt(source.getLastSeenAt());
        copy.setLastEvidenceAt(source.getLastEvidenceAt());
        copy.setGenerationBatchId(source.getGenerationBatchId());
        copy.setCreatedAt(source.getCreatedAt());
        copy.setUpdatedAt(source.getUpdatedAt());
        return copy;
    }

    private static AiTopicEntity copyTopic(AiTopicEntity source, int maxChars) {
        AiTopicEntity copy = new AiTopicEntity();
        copy.setId(source.getId());
        copy.setContactId(source.getContactId());
        copy.setOwnerType(source.getOwnerType());
        copy.setOwnerId(source.getOwnerId());
        copy.setWecomGroupSourceConversationId(source.getWecomGroupSourceConversationId());
        copy.setOwnerLabel(source.getOwnerLabel());
        copy.setContactDisplayName(source.getContactDisplayName());
        copy.setContactRemark(source.getContactRemark());
        copy.setContactChannelType(source.getContactChannelType());
        copy.setContactChannelNickname(source.getContactChannelNickname());
        copy.setTitle(truncate(source.getTitle(), maxChars));
        copy.setAiSummary(truncate(source.getAiSummary(), maxChars));
        copy.setConfirmedSummary(truncate(source.getConfirmedSummary(), maxChars));
        copy.setStatus(source.getStatus());
        copy.setReviewOrigin(source.getReviewOrigin());
        copy.setReviewSourceContactId(source.getReviewSourceContactId());
        copy.setReviewSourceTopicId(source.getReviewSourceTopicId());
        copy.setReviewSourceTopicTitle(source.getReviewSourceTopicTitle());
        copy.setReviewOperationId(source.getReviewOperationId());
        copy.setFirstOccurredAt(source.getFirstOccurredAt());
        copy.setLastOccurredAt(source.getLastOccurredAt());
        copy.setInputFingerprint(source.getInputFingerprint());
        copy.setVersion(source.getVersion());
        copy.setCreatedAt(source.getCreatedAt());
        copy.setUpdatedAt(source.getUpdatedAt());
        return copy;
    }

    private static CallTranscriptRevisionEntity copyTranscript(CallTranscriptRevisionEntity source, int maxChars) {
        CallTranscriptRevisionEntity copy = new CallTranscriptRevisionEntity();
        copy.setId(source.getId());
        copy.setCallRecordId(source.getCallRecordId());
        copy.setText(truncate(source.getText(), maxChars));
        copy.setEditedAt(source.getEditedAt());
        copy.setEditedBy(source.getEditedBy());
        copy.setCreatedAt(source.getCreatedAt());
        return copy;
    }

    private static MessageEntity copyMessage(MessageEntity source) {
        MessageEntity copy = new MessageEntity();
        copy.setId(source.getId());
        copy.setConversationId(source.getConversationId());
        copy.setChannelAccountId(source.getChannelAccountId());
        copy.setChannelAccountVersion(source.getChannelAccountVersion());
        copy.setSourceEventId(source.getSourceEventId());
        copy.setProviderMessageId(source.getProviderMessageId());
        copy.setClientRequestId(source.getClientRequestId());
        copy.setDirection(source.getDirection());
        copy.setMessageKind(source.getMessageKind());
        copy.setSubject(source.getSubject());
        copy.setBodyText(source.getBodyText());
        copy.setBodyHtml(source.getBodyHtml());
        copy.setOccurredAt(source.getOccurredAt());
        copy.setReceivedAt(source.getReceivedAt());
        copy.setIngestSequence(source.getIngestSequence());
        copy.setCountsAsUnread(source.getCountsAsUnread());
        copy.setCurrentStatus(source.getCurrentStatus());
        copy.setCurrentStatusAt(source.getCurrentStatusAt());
        copy.setCreatedByUserId(source.getCreatedByUserId());
        copy.setMetadataJsonb(source.getMetadataJsonb());
        copy.setCreatedAt(source.getCreatedAt());
        return copy;
    }

    private static String truncate(String value, int maxChars) {
        if (value == null) return "";
        return value.length() <= maxChars ? value : value.substring(0, maxChars);
    }

    private record CursorBoundary(Instant occurredAt, UUID messageId) {
        static CursorBoundary parse(String value) {
            if (value == null || value.isBlank()) return new CursorBoundary(null, null);
            int separator = value.indexOf('|');
            if (separator <= 0 || separator == value.length() - 1) {
                throw new ContactMemoryModels.ValidationException("INVALID_CURSOR");
            }
            try {
                return new CursorBoundary(
                        Instant.parse(value.substring(0, separator)),
                        UUID.fromString(value.substring(separator + 1)));
            } catch (RuntimeException exception) {
                throw new ContactMemoryModels.ValidationException("INVALID_CURSOR");
            }
        }

        static String encode(Instant occurredAt, UUID messageId) {
            return occurredAt + "|" + messageId;
        }
    }
}
