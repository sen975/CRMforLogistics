package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;
import com.crmforlogistics.messagecenter.entity.WeComPartyEntity;

import java.util.UUID;

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
}
