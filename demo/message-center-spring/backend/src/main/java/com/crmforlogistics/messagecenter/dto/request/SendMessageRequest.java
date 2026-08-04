package com.crmforlogistics.messagecenter.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import java.util.UUID;

public record SendMessageRequest(
        @NotNull UUID contactId,
        @NotBlank String body,
        String channelType,
        String templateCode,
        String language,
        Map<String, String> templateParams
) {}
