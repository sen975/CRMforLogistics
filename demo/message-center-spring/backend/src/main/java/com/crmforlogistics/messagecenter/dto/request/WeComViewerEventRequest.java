package com.crmforlogistics.messagecenter.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class WeComViewerEventRequest {
    @NotBlank @Size(max = 64)
    private final String eventType;
    @Size(max = 64)
    private final String eventKey;
    @Size(max = 64)
    private final String stage;
    private final Long generation;
    @Size(max = 64)
    private final String viewerSessionId;
    @Size(max = 64)
    private final String errorCategory;

    @JsonCreator
    public WeComViewerEventRequest(@JsonProperty("eventType") String eventType,
                                   @JsonProperty("eventKey") String eventKey,
                                   @JsonProperty("stage") String stage,
                                   @JsonProperty("generation") Long generation,
                                   @JsonProperty("viewerSessionId") String viewerSessionId,
                                   @JsonProperty("errorCategory") String errorCategory) {
        this.eventType = eventType;
        this.eventKey = eventKey;
        this.stage = stage;
        this.generation = generation;
        this.viewerSessionId = viewerSessionId;
        this.errorCategory = errorCategory;
    }

    @JsonAnySetter
    void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("Unknown WeCom viewer event field: " + name);
    }

    public String eventType() {
        return eventType;
    }

    public String eventKey() { return eventKey; }
    public String stage() { return stage; }
    public long generation() { return generation == null ? -1 : generation; }

    public String viewerSessionId() {
        return viewerSessionId;
    }
    public String errorCategory() { return errorCategory; }
}
