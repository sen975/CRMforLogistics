package com.crmforlogistics.messagecenter.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A template change request. The trailing {@code providerScope*} fields name the CAMS space the
 * template belongs to, so an administrator reviewing a request can tell which space it will change
 * and whether that space is the enterprise API space or an owner-bound Business App space.
 */
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
        Instant executionCompletedAt,
        UUID providerScopeId,
        String providerScopeName,
        String providerScopeExternalId,
        String providerScopeType) {
    public TemplateChangeRequestResponse {
        diffs = diffs == null ? List.of() : List.copyOf(diffs);
    }

    public record FieldDiff(String field, String label, Object beforeValue, Object afterValue) { }
    public record Page(List<TemplateChangeRequestResponse> items, long total, int page, int size) {
        public Page { items = items == null ? List.of() : List.copyOf(items); }
    }
}
