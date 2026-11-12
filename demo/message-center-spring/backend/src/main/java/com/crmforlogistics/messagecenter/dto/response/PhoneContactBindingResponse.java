package com.crmforlogistics.messagecenter.dto.response;

public record PhoneContactBindingResponse(
        String contactId,
        String phonePointId,
        String displayName
) {}
