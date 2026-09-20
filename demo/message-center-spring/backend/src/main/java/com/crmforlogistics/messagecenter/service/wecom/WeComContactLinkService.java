package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.WeComException;
import com.crmforlogistics.messagecenter.config.ConditionalOnWeComEnabled;
import com.crmforlogistics.messagecenter.dto.response.WeComExternalContactLinkResponse;
import com.crmforlogistics.messagecenter.mapper.WeComSourceParticipantMapper;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/**
 * Resolves the WeCom external contacts shown by the P0 console to the CRM contacts the
 * current account may open. This service never calls 企业微信 and never creates a contact:
 * an external contact that has no CRM contact yet is reported as a link without contactId.
 */
@Service
@ConditionalOnWeComEnabled
public class WeComContactLinkService {
    private static final int MAX_EXTERNAL_USER_IDS = 100;
    private static final int MAX_EXTERNAL_USER_ID_LENGTH = 128;

    private final WeComSourceParticipantMapper participants;

    public WeComContactLinkService(WeComSourceParticipantMapper participants) {
        this.participants = participants;
    }

    public List<WeComExternalContactLinkResponse> resolve(UUID userId, List<String> requestedIds) {
        if (userId == null) {
            throw new WeComException("WECOM_CONTACT_LINK_UNAUTHORIZED", 401, "缺少当前账号上下文");
        }
        List<String> externalUserIds = normalize(requestedIds);
        if (externalUserIds.isEmpty()) {
            return List.of();
        }
        LinkedHashMap<String, WeComExternalContactLinkResponse> byExternalUserId = new LinkedHashMap<>();
        for (WeComSourceParticipantMapper.WeComExternalContactLinkRow row
                : participants.resolveExternalContactLinks(userId, externalUserIds)) {
            byExternalUserId.putIfAbsent(row.providerPartyId(), new WeComExternalContactLinkResponse(
                    row.providerPartyId(), row.contactId(), row.identityId(), row.contactAccessible()));
        }
        return List.copyOf(byExternalUserId.values());
    }

    private static List<String> normalize(List<String> requestedIds) {
        if (requestedIds == null || requestedIds.isEmpty()) {
            return List.of();
        }
        LinkedHashMap<String, Boolean> distinct = new LinkedHashMap<>();
        for (String raw : requestedIds) {
            if (raw == null) continue;
            for (String candidate : raw.split(",")) {
                String value = candidate.trim();
                if (value.isEmpty()) continue;
                if (value.length() > MAX_EXTERNAL_USER_ID_LENGTH) {
                    throw new WeComException("WECOM_CONTACT_LINK_ID_INVALID", 400,
                            "企业微信外部联系人标识长度超出限制");
                }
                distinct.putIfAbsent(value, Boolean.TRUE);
                if (distinct.size() > MAX_EXTERNAL_USER_IDS) {
                    throw new WeComException("WECOM_CONTACT_LINK_LIMIT_EXCEEDED", 400,
                            "一次最多解析 " + MAX_EXTERNAL_USER_IDS + " 个企业微信外部联系人");
                }
            }
        }
        return List.copyOf(distinct.keySet());
    }
}
