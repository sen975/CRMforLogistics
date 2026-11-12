package com.crmforlogistics.messagecenter.dto.request;

import jakarta.validation.constraints.NotBlank;

public record AdminResetPasswordRequest(@NotBlank String newPassword,
                                        @NotBlank String confirmPassword) {}
