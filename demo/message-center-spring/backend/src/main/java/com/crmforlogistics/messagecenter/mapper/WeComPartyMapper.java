package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;
import com.crmforlogistics.messagecenter.entity.WeComPartyEntity;

import java.util.UUID;
import java.util.Optional;

@Mapper
public interface WeComPartyMapper extends BaseMapper<WeComPartyEntity> {
    @Select("insert into wecom_parties (id, installation_id, party_type, provider_party_id, display_name, "
            + "profile_status, first_seen_at, last_seen_at, created_at, updated_at) values "
            + "(gen_random_uuid(), #{installationId}::uuid, #{partyType}, #{providerPartyId}, #{displayName}, "
            + "'PENDING', now(), now(), now(), now()) on conflict (installation_id, party_type, provider_party_id) "
            + "do update set last_seen_at = now(), updated_at = now() returning id")
    UUID upsertObserved(@Param("installationId") UUID installationId,
                        @Param("partyType") String partyType,
                        @Param("providerPartyId") String providerPartyId,
                        @Param("displayName") String displayName);

    @Select("select * from wecom_parties where installation_id = #{installationId}::uuid "
            + "and party_type in ('EMPLOYEE', 'EXTERNAL_CONTACT') "
            + "and (profile_status <> 'READY' or coalesce(display_name, '') = '' "
            + "or coalesce(avatar_url, '') = '') "
            + "order by last_seen_at desc, id limit #{limit}")
    java.util.List<WeComPartyEntity> listProfileBackfillCandidates(
            @Param("installationId") UUID installationId, @Param("limit") int limit);

    @Select("select nullif(trim(p.avatar_url), '') from wecom_parties p "
            + "join wecom_installations i on i.id = p.installation_id "
            + "where i.suite_id = #{suiteId} and i.auth_corp_id = #{authCorpId} "
            + "and i.auth_status = 'ACTIVE' and i.deleted_at is null "
            + "and p.party_type = 'EMPLOYEE' and p.provider_party_id = #{wecomUserId} "
            + "and nullif(trim(p.avatar_url), '') is not null order by p.updated_at desc limit 1")
    Optional<String> findEmployeeAvatarUrl(@Param("suiteId") String suiteId,
                                           @Param("authCorpId") String authCorpId,
                                           @Param("wecomUserId") String wecomUserId);
}
