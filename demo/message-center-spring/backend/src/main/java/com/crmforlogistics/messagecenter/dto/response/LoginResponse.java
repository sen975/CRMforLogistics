package com.crmforlogistics.messagecenter.dto.response;

import java.util.List;

public record LoginResponse(String token, String username, List<String> roles) {}
