package com.crmforlogistics.messagecenter.dto.response;

import java.util.List;

public record WeComLoginResponse(String token, String username, List<String> roles,
                                 String viewerAuthToken, int viewerExpiresIn) {
    public WeComLoginResponse(String token, String username, List<String> roles) {
        this(token, username, roles, null, 0);
    }
}
