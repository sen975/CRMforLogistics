package com.crmforlogistics.messagecenter.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TemplateChangeRequestResponse(
        UUID id,
        UUID templateId,
        String templateDisplayName,
        long baseVersion,
        String changeType,
        String status,
        List<FieldDiff> diffs,
        String requestedByDisplayName,
        String reviewedByDisplayName,
        String reviewReason,
        String executionErrorCode,
        String executionErrorMessage,
        String providerRequestId,
        Instant createdAt,
        Instant reviewedAt,
        Instant executionCompletedAt) {
    public TemplateChangeRequestResponse {
        diffs = diffs == null ? List.of() : List.copyOf(diffs);
    }

    public record FieldDiff(String field, String label, Object beforeValue, Object afterValue) { }
    public record Page(List<TemplateChangeRequestResponse> items, long total, int page, int size) {
        public Page { items = items == null ? List.of() : List.copyOf(items); }
    }
}
