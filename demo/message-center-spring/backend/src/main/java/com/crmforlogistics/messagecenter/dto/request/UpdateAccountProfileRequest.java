package com.crmforlogistics.messagecenter.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateAccountProfileRequest(@NotBlank @Size(max = 50) String displayName) {}
