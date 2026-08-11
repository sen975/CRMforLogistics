package com.crmforlogistics.messagecenter.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record MessageResponse(
        UUID id,
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
        List<MessageAttachmentResponse> attachments
) {}
