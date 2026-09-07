package com.crmforlogistics.messagecenter.dto.request;

import jakarta.validation.constraints.NotBlank;

public record ChangePasswordRequest(@NotBlank String currentPassword,
                                    @NotBlank String newPassword,
                                    @NotBlank String confirmPassword) {}
