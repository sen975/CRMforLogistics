package com.crmforlogistics.messagecenter.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record MessageResponse(
        UUID id,
        String sourceId,
        String direction,
        String kind,
        String subject,
        String bodyText,
        String bodyHtml,
        String channelType,
        String from,
        String to,
        Instant occurredAt,
        String status,
        int ingestSequence,
        List<MessageAttachmentResponse> attachments,
        UUID sourceConversationId,
        String conversationType,
        String conversationDisplayName
) {
    public MessageResponse(UUID id,
                           String sourceId,
                           String direction,
                           String kind,
                           String subject,
                           String bodyText,
                           String bodyHtml,
                           String channelType,
                           String from,
                           String to,
                           Instant occurredAt,
                           String status,
                           int ingestSequence,
                           List<MessageAttachmentResponse> attachments) {
        this(id, sourceId, direction, kind, subject, bodyText, bodyHtml, channelType, from, to,
                occurredAt, status, ingestSequence, attachments, null, null, null);
    }

    public MessageResponse(UUID id,
                           String direction,
                           String kind,
                           String subject,
                           String bodyText,
                           String bodyHtml,
                           String channelType,
                           String from,
                           String to,
                           Instant occurredAt,
                           String status,
                           int ingestSequence,
                           List<MessageAttachmentResponse> attachments) {
        this(id, null, direction, kind, subject, bodyText, bodyHtml, channelType, from, to,
                occurredAt, status, ingestSequence, attachments, null, null, null);
    }
}
