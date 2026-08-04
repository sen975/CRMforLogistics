package com.crmforlogistics.messagecenter.service.auth;

import java.util.UUID;

public record BootstrapResult(boolean created, UUID userId, String code) {}
