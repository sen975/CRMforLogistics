package com.crmforlogistics.messagecenter.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = false)
public record CreateChannelAccountRequest(
        @NotBlank @Pattern(regexp = "(?i)chatapp|whatsapp|email") String channelType,
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Size(max = 255) String accountIdentifier,
        @Size(max = 20) Map<@NotBlank @Size(max = 50) String,
                @NotBlank @Size(max = 4096) String> credentials) {
}
