package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.crmforlogistics.messagecenter.entity.WhatsAppAuthorizationAttemptEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.UUID;

@Mapper
public interface WhatsAppAuthorizationAttemptMapper extends BaseMapper<WhatsAppAuthorizationAttemptEntity> {
    @Insert("insert into whatsapp_authorization_attempts " +
            "(id, user_id, state_hash, status, onboarding_mode, expires_at) values " +
            "(#{id}::uuid, #{userId}::uuid, #{stateHash}, 'PENDING', #{onboardingMode}, #{expiresAt})")
    int insert(WhatsAppAuthorizationAttemptEntity entity);

    @Select("select id, user_id, state_hash, status, expires_at, consumed_at, " +
            "completed_waba_id, completed_phone_number, completed_phone_number_id, completed_account_id, " +
            "failure_stage, failure_code, onboarding_mode, account_name, account_remark, created_at " +
            "from whatsapp_authorization_attempts where id = #{id}::uuid limit 1")
    WhatsAppAuthorizationAttemptEntity findById(@Param("id") UUID id);

    @Select("select id, user_id, state_hash, status, expires_at, consumed_at, " +
            "completed_waba_id, completed_phone_number, completed_phone_number_id, completed_account_id, " +
            "failure_stage, failure_code, onboarding_mode, account_name, account_remark, created_at " +
            "from whatsapp_authorization_attempts where id = #{id}::uuid for update")
    WhatsAppAuthorizationAttemptEntity findByIdForUpdate(@Param("id") UUID id);

    @Update("update whatsapp_authorization_attempts set status = 'META_COMPLETED', completed_waba_id = #{wabaId}, " +
            "completed_phone_number_id = #{phoneNumberId}, failure_stage = null, failure_code = null " +
            "where id = #{id}::uuid and status = 'PENDING' and expires_at > #{now}")
    int advanceMetaCompleted(@Param("id") UUID id, @Param("wabaId") String wabaId,
                             @Param("phoneNumberId") String phoneNumberId, @Param("now") Instant now);

    @Update("update whatsapp_authorization_attempts set status = 'PROVIDER_SYNCED', completed_phone_number = #{phoneNumber} " +
            "where id = #{id}::uuid and status = 'META_COMPLETED'")
    int markProviderSynced(@Param("id") UUID id, @Param("phoneNumber") String phoneNumber);

    @Update("update whatsapp_authorization_attempts set status = 'PROVIDER_SYNCED' "
            + "where id = #{id}::uuid and status = 'META_COMPLETED'")
    int markScopeProviderSynced(@Param("id") UUID id);

    @Update("update whatsapp_authorization_attempts set status = 'COMPLETED', consumed_at = #{completedAt}, " +
            "completed_account_id = #{accountId}::uuid where id = #{id}::uuid and status = 'PROVIDER_SYNCED'")
    int complete(@Param("id") UUID id, @Param("accountId") UUID accountId,
                 @Param("completedAt") Instant completedAt);

    @Update("update whatsapp_authorization_attempts set status = 'COMPLETED', consumed_at = #{completedAt} "
            + "where id = #{id}::uuid and status = 'PROVIDER_SYNCED'")
    int completeScope(@Param("id") UUID id, @Param("completedAt") Instant completedAt);

    @Update("update whatsapp_authorization_attempts set status = 'FAILED', failure_stage = #{stage}, failure_code = #{code} " +
            "where id = #{id}::uuid and status in ('PENDING', 'META_COMPLETED', 'PROVIDER_SYNCED')")
    int fail(@Param("id") UUID id, @Param("stage") String stage, @Param("code") String code);

    @Update("update whatsapp_authorization_attempts set status = 'COMPLETED', consumed_at = #{consumedAt}, " +
            "completed_waba_id = #{wabaId}, completed_phone_number = #{phoneNumber} " +
            "where id = #{id}::uuid and status = 'PENDING' and expires_at > #{consumedAt}")
    int consume(@Param("id") UUID id, @Param("consumedAt") Instant consumedAt,
                @Param("wabaId") String wabaId, @Param("phoneNumber") String phoneNumber);
}
