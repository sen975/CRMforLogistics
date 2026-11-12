package com.crmforlogistics.messagecenter.dto.response;

import java.util.List;

public record ChannelAddressBookPageResponse(
        List<ChannelAddressBookItem> items,
        int page,
        int size,
        boolean hasMore) {
}
