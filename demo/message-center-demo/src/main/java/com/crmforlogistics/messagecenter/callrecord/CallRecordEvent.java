package com.crmforlogistics.messagecenter.callrecord;

import java.util.Objects;

public record CallRecordEvent(
        String callRecordId,
        String contactAnchorPointId,
        String state,
        long version) {
    public CallRecordEvent {
        Objects.requireNonNull(callRecordId, "callRecordId");
        Objects.requireNonNull(contactAnchorPointId, "contactAnchorPointId");
        Objects.requireNonNull(state, "state");
        if (version < 1) throw new IllegalArgumentException("version must be positive");
    }
}
