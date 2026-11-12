package com.crmforlogistics.messagecenter.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public final class WeComViewerSessionRequest {
    @Size(max = 32)
    private final String targetType;

    private final UUID targetId;

    @Size(max = 256)
    private final String contactPointId;

    @NotNull
    @Size(min = 1, max = 15)
    private final List<@NotBlank @Size(max = 256) String> messageIds;

    @JsonCreator
    public WeComViewerSessionRequest(@JsonProperty("targetType") String targetType,
                                     @JsonProperty("targetId") UUID targetId,
                                     @JsonProperty("contactPointId") String contactPointId,
                                     @JsonProperty("messageIds") List<String> messageIds) {
        this.targetType = targetType;
        this.targetId = targetId;
        this.contactPointId = contactPointId;
        this.messageIds = messageIds == null ? null : List.copyOf(messageIds);
    }

    @JsonAnySetter
    void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("Unknown WeCom viewer session field: " + name);
    }

    public String targetType() { return targetType; }
    public UUID targetId() { return targetId; }
    public String contactPointId() { return contactPointId; }

    public List<String> messageIds() {
        return messageIds;
    }

}
