package com.crmforlogistics.messagecenter.service.chatapp;

import com.aliyun.sdk.service.cams20200606.models.ListChatappMessageResponseBody;
import com.crmforlogistics.messagecenter.entity.ChannelEventEntity;
import com.crmforlogistics.messagecenter.mapper.ChannelEventMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class ChatAppPollingFailureInboxService {
    private final ChannelEventMapper eventMapper;
    private final ObjectMapper objectMapper;

    public ChatAppPollingFailureInboxService(ChannelEventMapper eventMapper, ObjectMapper objectMapper) {
        this.eventMapper = eventMapper;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID accountId, ListChatappMessageResponseBody.Data row, String code) {
        try {
            String providerId = firstNonBlank(row.getMessageId(), row.getUniqueMessageId());
            String payload = objectMapper.writeValueAsString(row);
            Instant now = Instant.now();
            ChannelEventEntity event = new ChannelEventEntity();
            event.setId(UUID.randomUUID());
            event.setChannelAccountId(accountId);
            event.setProviderEventId("poll-failure:" + providerId);
            event.setEventType("chatapp_polling_projection_failure");
            event.setOccurredAt(now);
            event.setReceivedAt(now);
            event.setPayloadJsonb(payload);
            event.setPayloadHash(sha256(payload));
            event.setProcessingStatus("received");
            event.setAttemptCount(0);
            event.setNextAttemptAt(now);
            event.setLastErrorCode(code);
            event.setLastErrorMessage(code);
            event.setTraceId(UUID.randomUUID().toString());
            eventMapper.insertIgnore(event);
        } catch (Exception error) {
            throw new IllegalStateException("CHATAPP_POLLING_FAILURE_INBOX_WRITE_FAILED", error);
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return UUID.randomUUID().toString();
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
