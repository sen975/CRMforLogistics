package com.crmforlogistics.messagecenter.service.wecom;

import java.util.UUID;

public record WeComApiActor(UUID userId, String traceId) {
    public WeComApiActor {
        if (userId == null || traceId == null || !traceId.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("WeCom API actor is invalid");
        }
    }
}
