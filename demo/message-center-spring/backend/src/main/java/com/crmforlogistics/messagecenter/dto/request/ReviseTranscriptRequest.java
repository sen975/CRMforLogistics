package com.crmforlogistics.messagecenter.dto.request;

import jakarta.validation.constraints.NotBlank;

public record ReviseTranscriptRequest(
        @NotBlank String text,
        long expectedVersion
) {}
