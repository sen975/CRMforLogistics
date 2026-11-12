package com.crmforlogistics.messagecenter.dto.response;

import java.util.UUID;

public record WeComBindingResponse(UUID userId, String authCorpId, String wecomUserId,
                                   String provisioningSource, boolean bound,
                                   String wecomDisplayName, String corpName) {
    public WeComBindingResponse(UUID userId, String authCorpId, String wecomUserId,
                                String provisioningSource, boolean bound) {
        this(userId, authCorpId, wecomUserId, provisioningSource, bound, null, null);
    }
}
