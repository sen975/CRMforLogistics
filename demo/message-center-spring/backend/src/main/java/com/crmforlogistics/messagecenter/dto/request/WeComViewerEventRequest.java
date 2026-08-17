package com.crmforlogistics.messagecenter.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class WeComViewerEventRequest {
    @NotBlank
    @Size(max = 64)
    private final String eventType;

    @NotBlank
    @Size(max = 64)
    private final String viewerSessionId;

    @JsonCreator
    public WeComViewerEventRequest(@JsonProperty("eventType") String eventType,
                                   @JsonProperty("viewerSessionId") String viewerSessionId) {
        this.eventType = eventType;
        this.viewerSessionId = viewerSessionId;
    }

    @JsonAnySetter
    void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("Unknown WeCom viewer event field: " + name);
    }

    public String eventType() {
        return eventType;
    }

    public String viewerSessionId() {
        return viewerSessionId;
    }
}
