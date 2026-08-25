package com.crmforlogistics.messagecenter.service.wecom;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.crmforlogistics.messagecenter.channel.wecom.ResolvedInstallation;
import com.crmforlogistics.messagecenter.channel.wecom.WeComDirectoryGateway;
import com.crmforlogistics.messagecenter.channel.wecom.WeComExternalContactGateway;
import com.crmforlogistics.messagecenter.config.ConditionalOnWeComEnabled;
import com.crmforlogistics.messagecenter.entity.WeComPartyEntity;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.entity.ContactEntity;
import com.crmforlogistics.messagecenter.entity.ContactIdentityEntity;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.ContactIdentityMapper;
import com.crmforlogistics.messagecenter.mapper.ContactMapper;
import com.crmforlogistics.messagecenter.mapper.WeComPartyMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Optional;

/** Owns the provider profile projection used by ChatData and CRM labels. */
@Service
@ConditionalOnWeComEnabled
@ConditionalOnExpression("not '${app.wecom-suite-id:}'.isBlank()")
public class WeComPartyProfileService {
    private final WeComDirectoryGateway directory;
    private final WeComExternalContactGateway externalContacts;
    private final WeComPartyMapper parties;
    private final ChannelAccountMapper accounts;
    private final ContactIdentityMapper identities;
    private final ContactMapper contacts;

    @Autowired
    public WeComPartyProfileService(WeComDirectoryGateway directory,
                                    WeComExternalContactGateway externalContacts,
                                    WeComPartyMapper parties,
                                    ChannelAccountMapper accounts,
                                    ContactIdentityMapper identities,
                                    ContactMapper contacts) {
        this.directory = directory;
        this.externalContacts = externalContacts;
        this.parties = parties;
        this.accounts = accounts;
        this.identities = identities;
        this.contacts = contacts;
    }

    public ProfileResult syncEmployee(ResolvedInstallation installation, String userId, Duration timeout) {
        JsonNode response;
        try {
            response = directory.getMember(installation, required(userId), timeout);
        } catch (RuntimeException failure) {
            return upsert(installation, "EMPLOYEE", userId, "", "", "DEGRADED", failureCode(failure));
        }
        ProfileResult result = upsertProfile(installation, "EMPLOYEE", userId,
                response.path("name").asText(""), response.path("avatar").asText(""), "");
        refreshExistingCrmIdentity(installation, userId, result.displayName());
        return result;
    }

    public ProfileResult syncExternalContact(ResolvedInstallation installation, String externalUserId,
                                             Duration timeout) {
        JsonNode response;
        try {
            response = externalContacts.get(installation, required(externalUserId), "", timeout);
        } catch (RuntimeException failure) {
            return upsert(installation, "EXTERNAL_CONTACT", externalUserId, "", "", "DEGRADED",
                    failureCode(failure));
        }
        JsonNode profile = response.path("external_contact");
        String name = firstText(profile, "remark", "name", "nickname", "alias");
        ProfileResult result = upsertProfile(installation, "EXTERNAL_CONTACT", externalUserId, name,
                profile.path("avatar").asText(""), "");
        refreshExistingCrmIdentity(installation, externalUserId, result.displayName());
        return result;
    }

    /**
     * Directory/profile sync must also repair identities created before the profile was available.
     * The CRM contact is changed only while it still contains the provider identifier (or the
     * previous identity label), so a deliberate CRM rename is never overwritten.
     */
    private void refreshExistingCrmIdentity(ResolvedInstallation installation, String providerId,
                                            String displayName) {
        if (accounts == null || identities == null || contacts == null || displayName == null
                || displayName.isBlank() || installation == null || installation.authCorpId() == null) {
            return;
        }
        ChannelAccountEntity account = accounts.selectActiveWeComAccount(installation.authCorpId());
        if (account == null || account.getId() == null) return;
        Optional<ContactIdentityEntity> existing = identities.findByNormalizedValueInScope(
                "wecom", account.getId().toString(), providerId);
        if (existing.isEmpty()) return;

        ContactIdentityEntity identity = existing.get();
        String previousIdentityName = bounded(identity.getDisplayName(), 512);
        if (!displayName.equals(previousIdentityName)) {
            identities.updateDisplayName(identity.getId(), displayName);
        }
        ContactEntity contact = identity.getContactId() == null ? null : contacts.selectById(identity.getContactId());
        if (contact == null) return;
        String contactName = bounded(contact.getDisplayName(), 512);
        if (contactName.isBlank() || providerId.equals(contactName) || previousIdentityName.equals(contactName)) {
            if (!displayName.equals(contactName)) {
                contacts.updateDisplayName(contact.getId(), displayName);
            }
        }
    }

    /**
     * Pulls the provider directory through the permitted department -> simplelist -> get chain.
     * The simple list is discovery only; display data always comes from user/get.
     */
    public DirectorySyncResult syncDirectory(ResolvedInstallation installation, Duration timeout) {
        if (installation == null || timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("directory sync arguments are invalid");
        }
        JsonNode departments = directory.listDepartments(installation, null, timeout);
        Set<Long> departmentIds = new LinkedHashSet<>();
        JsonNode departmentList = departments.path("department");
        if (departmentList.isArray()) {
            departmentList.forEach(node -> {
                if (node.path("id").canConvertToLong() && node.path("id").asLong() > 0) {
                    departmentIds.add(node.path("id").asLong());
                }
            });
        }
        Set<String> userIds = new LinkedHashSet<>();
        for (Long departmentId : departmentIds) {
            JsonNode members = directory.listMembers(installation, departmentId, true, timeout);
            JsonNode userList = members.path("userlist");
            if (!userList.isArray()) userList = members.path("user");
            if (userList.isArray()) {
                userList.forEach(node -> {
                    String userId = node.path("userid").asText("").trim();
                    if (!userId.isBlank() && userId.length() <= 128) userIds.add(userId);
                });
            }
        }
        int ready = 0;
        int partial = 0;
        int degraded = 0;
        for (String userId : userIds) {
            ProfileResult result = syncEmployee(installation, userId, timeout);
            switch (result.profileStatus()) {
                case "READY" -> ready++;
                case "DEGRADED" -> degraded++;
                default -> partial++;
            }
        }
        return new DirectorySyncResult(departmentIds.size(), userIds.size(), ready, partial, degraded);
    }

    private ProfileResult upsertProfile(ResolvedInstallation installation, String type, String providerId,
                                        String displayName, String avatar, String errorCode) {
        String safeName = bounded(displayName, 512);
        String safeAvatar = bounded(avatar, 2048);
        String status = safeName.isBlank() && safeAvatar.isBlank() ? "PARTIAL"
                : safeName.isBlank() || safeAvatar.isBlank() ? "PARTIAL" : "READY";
        return upsert(installation, type, providerId, safeName, safeAvatar, status, errorCode);
    }

    private ProfileResult upsert(ResolvedInstallation installation, String type, String providerId,
                                 String name, String avatar, String status, String errorCode) {
        if (installation == null || installation.installationId().isBlank()) {
            throw new IllegalArgumentException("installation is invalid");
        }
        String normalizedId = required(providerId);
        WeComPartyEntity entity = parties.selectOne(new LambdaQueryWrapper<WeComPartyEntity>()
                .eq(WeComPartyEntity::getInstallationId, UUID.fromString(installation.installationId()))
                .eq(WeComPartyEntity::getPartyType, type)
                .eq(WeComPartyEntity::getProviderPartyId, normalizedId));
        Instant now = Instant.now();
        if (entity == null) {
            entity = new WeComPartyEntity();
            entity.setId(UUID.randomUUID());
            entity.setInstallationId(UUID.fromString(installation.installationId()));
            entity.setPartyType(type);
            entity.setProviderPartyId(normalizedId);
            entity.setFirstSeenAt(now);
            entity.setCreatedAt(now);
            parties.insert(entity);
        }
        entity.setDisplayName(name);
        entity.setAvatarUrl(avatar);
        entity.setProfileStatus(status);
        entity.setProfileErrorCode(errorCode);
        entity.setLastSeenAt(now);
        entity.setUpdatedAt(now);
        parties.updateById(entity);
        return new ProfileResult(entity.getId(), type, normalizedId, name, avatar, status, errorCode);
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = bounded(node.path(field).asText(""), 512);
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private static String bounded(String value, int max) {
        if (value == null) return "";
        String trimmed = value.trim();
        return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
    }

    private static String required(String value) {
        if (value == null || value.isBlank() || value.length() > 256) {
            throw new IllegalArgumentException("provider party id is invalid");
        }
        return value.trim();
    }

    private static String failureCode(RuntimeException failure) {
        return failure instanceof com.crmforlogistics.messagecenter.channel.wecom.WeComException exception
                ? exception.code() : "PROFILE_LOOKUP_FAILED";
    }

    public record ProfileResult(UUID partyId, String partyType, String providerPartyId,
                                String displayName, String avatarUrl, String profileStatus,
                                String profileErrorCode) {}

    public record DirectorySyncResult(int departments, int discovered, int ready, int partial, int degraded) {}
}
