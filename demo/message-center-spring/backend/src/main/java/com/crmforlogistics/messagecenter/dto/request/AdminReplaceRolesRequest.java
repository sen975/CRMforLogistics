package com.crmforlogistics.messagecenter.dto.request;

import jakarta.validation.constraints.NotEmpty;
import java.util.Set;

public record AdminReplaceRolesRequest(@NotEmpty Set<String> roles) {}
