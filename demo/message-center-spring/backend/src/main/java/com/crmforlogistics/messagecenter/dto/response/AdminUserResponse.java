package com.crmforlogistics.messagecenter.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AdminUserResponse(UUID id, String username, String displayName, String status,
                                List<String> roles, Instant createdAt) {}
