package com.crmforlogistics.messagecenter.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ChatAppCapabilityReport(
        UUID id,
        UUID accountId,
        UUID scopeId,
        String phase,
        boolean ready,
        Instant testedAt,
        List<ActionResult> results) {

    public ChatAppCapabilityReport {
        results = results == null ? List.of() : List.copyOf(results);
    }

    public record ActionResult(
            String action,
            String status,
            String providerRequestId,
            String diagnosticCode,
            String diagnosticMessage) {
    }
}
