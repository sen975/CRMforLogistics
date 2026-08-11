package com.crmforlogistics.messagecenter.dto.request;

public record BindPhoneContactRequest(
        String contactId,
        String contactName,
        String phoneNumber
) {}
