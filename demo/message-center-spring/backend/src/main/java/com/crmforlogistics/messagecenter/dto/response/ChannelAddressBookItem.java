package com.crmforlogistics.messagecenter.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ChannelAddressBookItem(
        UUID contactId,
        UUID identityId,
        String displayName,
        String remark,
        String channelType,
        String address,
        String channelDisplayName,
        List<String> additionalChannelTypes,
        String source,
        Instant lastContactAt,
        boolean hasActivity,
        boolean canDelete,
        List<String> matchedTags) {

    public ChannelAddressBookItem withMatchedTags(List<String> tags) {
        return new ChannelAddressBookItem(contactId, identityId, displayName, remark, channelType,
                address, channelDisplayName, additionalChannelTypes, source, lastContactAt,
                hasActivity, canDelete, tags == null ? List.of() : tags);
    }
}
