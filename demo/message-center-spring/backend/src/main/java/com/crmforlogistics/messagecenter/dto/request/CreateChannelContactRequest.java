package com.crmforlogistics.messagecenter.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateChannelContactRequest(
        String channelType,
        @NotBlank @Size(max = 100) String displayName,
        @NotBlank @Size(max = 255) String address) {
}
