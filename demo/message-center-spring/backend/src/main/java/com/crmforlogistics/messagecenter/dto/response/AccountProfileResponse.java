package com.crmforlogistics.messagecenter.dto.response;

import java.util.List;
import java.util.UUID;

public record AccountProfileResponse(UUID id, String username, String displayName,
                                     List<String> roles, Avatar avatar) {
    public record Avatar(String source, String contentUrl, String initial, String revision) {}
}
