package com.crmforlogistics.messagecenter.dto.request;

public record ReviseNoteRequest(
        String note,
        long expectedVersion
) {}
