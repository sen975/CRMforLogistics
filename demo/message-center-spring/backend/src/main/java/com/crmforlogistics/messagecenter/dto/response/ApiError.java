package com.crmforlogistics.messagecenter.dto.response;

import java.util.Map;

public record ApiError(String code, String message, String traceId, Map<String, String> fieldErrors) {
    public static ApiError of(String code, String message) {
        return new ApiError(code, message, java.util.UUID.randomUUID().toString(), Map.of());
    }
}
