package com.crmforlogistics.messagecenter.dto.request;

import jakarta.validation.constraints.NotNull;

public record TemplateSendPermissionRequest(
        @NotNull(message = "is required") Boolean allowSend,
        String clientRequestId) {
}
