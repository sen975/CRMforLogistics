package com.crmforlogistics.messagecenter.dto.response;

import java.time.Instant;

public record PhoneRecordResponse(
        String id,
        String contactId,
        String contactAnchorPointId,
        String contactDisplayName,
        String phonePointId,
        Instant occurredAt,
        String direction,
        double durationSeconds,
        String note,
        String transcriptionState,
        String errorCode,
        String errorMessage,
        boolean errorRetryable,
        int transcriptionAttempts,
        String clientRequestId,
        long version
) {}
