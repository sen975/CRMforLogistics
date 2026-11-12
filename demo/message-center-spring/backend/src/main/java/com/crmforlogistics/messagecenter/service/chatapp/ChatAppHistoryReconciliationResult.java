package com.crmforlogistics.messagecenter.service.chatapp;

import java.util.List;

public record ChatAppHistoryReconciliationResult(
        int pages,
        int scanned,
        int unchanged,
        int moved,
        int unresolved,
        int failed,
        int identitiesCreated,
        boolean dryRun,
        long durationMs,
        List<Failure> failures) {

    public ChatAppHistoryReconciliationResult {
        failures = failures == null ? List.of() : List.copyOf(failures);
    }

    public record Failure(String providerMessageId, String reason) {
    }
}
