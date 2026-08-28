package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.ResolvedInstallation;
import com.crmforlogistics.messagecenter.channel.wecom.WeComInstallationService;
import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.config.ConditionalOnWeComEnabled;
import com.crmforlogistics.messagecenter.entity.WeComPartyEntity;
import com.crmforlogistics.messagecenter.entity.WeComSourceConversationEntity;
import com.crmforlogistics.messagecenter.mapper.WeComPartyMapper;
import com.crmforlogistics.messagecenter.mapper.WeComSourceConversationMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** Bounded, manually invoked recovery for metadata that predates ChatData profile hydration. */
@Service
@ConditionalOnWeComEnabled
@ConditionalOnExpression("not '${app.wecom-suite-id:}'.isBlank()")
public class WeComProfileBackfillService {
    private static final Logger log = LoggerFactory.getLogger(WeComProfileBackfillService.class);
    private static final int MAX_LIMIT = 200;
    private static final Duration LOOKUP_TIMEOUT = Duration.ofSeconds(2);

    private final AppConfig config;
    private final WeComInstallationService installations;
    private final WeComPartyMapper parties;
    private final WeComSourceConversationMapper conversations;
    private final WeComPartyProfileService profiles;
    private final WeComExternalContactService externalContacts;

    public WeComProfileBackfillService(AppConfig config, WeComInstallationService installations,
                                       WeComPartyMapper parties,
                                       WeComSourceConversationMapper conversations,
                                       WeComPartyProfileService profiles,
                                       WeComExternalContactService externalContacts) {
        this.config = config;
        this.installations = installations;
        this.parties = parties;
        this.conversations = conversations;
        this.profiles = profiles;
        this.externalContacts = externalContacts;
    }

    public BackfillResult backfill(String authCorpId, Integer requestedLimit) {
        int limit = requestedLimit == null ? MAX_LIMIT : requestedLimit;
        if (limit < 1 || limit > MAX_LIMIT) throw new IllegalArgumentException("backfill limit is invalid");
        ResolvedInstallation installation = installations.resolveInstallation(config.wecomSuiteId(), required(authCorpId));
        UUID installationId = UUID.fromString(installation.installationId());
        int partiesAttempted = 0;
        int groupsAttempted = 0;
        int groupsNamed = 0;
        int failures = 0;

        for (WeComPartyEntity party : parties.listProfileBackfillCandidates(installationId, limit)) {
            partiesAttempted++;
            try {
                if ("EMPLOYEE".equals(party.getPartyType())) {
                    profiles.syncEmployee(installation, party.getProviderPartyId(), LOOKUP_TIMEOUT);
                } else if ("EXTERNAL_CONTACT".equals(party.getPartyType())) {
                    profiles.syncExternalContact(installation, party.getProviderPartyId(), LOOKUP_TIMEOUT);
                }
            } catch (RuntimeException failure) {
                failures++;
                log.warn("WeCom profile backfill failed: type={}, error={}", party.getPartyType(),
                        failure.getClass().getSimpleName());
            }
        }

        for (WeComSourceConversationEntity group : conversations.listGroupBackfillCandidates(installationId, limit)) {
            String chatId = groupChatId(group.getProviderConversationKey());
            if (chatId == null) continue;
            groupsAttempted++;
            WeComExternalContactService.GroupMemberSnapshot snapshot = externalContacts
                    .groupMembersForSync(installation, chatId);
            if (!snapshot.available()) {
                failures++;
                continue;
            }
            for (WeComExternalContactService.GroupMember member : snapshot.members()) {
                try {
                    profiles.syncObservedProfile(installation, member.partyType(), member.providerPartyId(),
                            member.displayName(), member.avatarUrl());
                } catch (RuntimeException failure) {
                    failures++;
                    log.warn("WeCom group member profile backfill failed: type={}, error={}",
                            member.partyType(), failure.getClass().getSimpleName());
                }
            }
            if (!snapshot.displayName().isBlank()) {
                conversations.updateDisplayName(group.getId(), snapshot.displayName());
                groupsNamed++;
            }
        }
        return new BackfillResult(partiesAttempted, groupsAttempted, groupsNamed, failures);
    }

    private static String required(String value) {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException("authCorpId is invalid");
        }
        return value.trim();
    }

    private static String groupChatId(String providerConversationKey) {
        if (providerConversationKey == null || !providerConversationKey.startsWith("group:")) return null;
        String chatId = providerConversationKey.substring("group:".length());
        return chatId.isBlank() || chatId.length() > 128 ? null : chatId;
    }

    public record BackfillResult(int partiesAttempted, int groupsAttempted, int groupsNamed, int failures) {}
}
