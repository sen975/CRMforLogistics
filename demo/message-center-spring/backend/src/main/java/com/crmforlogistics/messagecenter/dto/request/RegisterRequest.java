package com.crmforlogistics.messagecenter.dto.request;

import jakarta.validation.constraints.NotBlank;

public record RegisterRequest(@NotBlank String username, String displayName, @NotBlank String password) {}
