package com.crmforlogistics.messagecenter.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ContactMemoryResponse(
        Profile profile,
        List<ContactTagResponse> humanTags,
        List<AiTag> aiTags,
        String state,
        Instant lastSuccessAt,
        String lastFailureCode,
        boolean pendingInbound,
        String aiTagsNextCursor,
        boolean aiTagsHasMore
) {
    public ContactMemoryResponse {
        humanTags = List.copyOf(humanTags == null ? List.of() : humanTags);
        aiTags = List.copyOf(aiTags == null ? List.of() : aiTags);
    }

    public ContactMemoryResponse(Profile profile,
                                 List<ContactTagResponse> humanTags,
                                 List<AiTag> aiTags,
                                 String state,
                                 Instant lastSuccessAt,
                                 String lastFailureCode,
                                 boolean pendingInbound) {
        this(profile, humanTags, aiTags, state, lastSuccessAt, lastFailureCode,
                pendingInbound, null, false);
    }

    public record Profile(UUID id, long version, String content, Instant createdAt) {}

    public record AiTag(UUID id,
                        String name,
                        String category,
                        String colorToken,
                        String status,
                        java.math.BigDecimal confidence) {}
}
