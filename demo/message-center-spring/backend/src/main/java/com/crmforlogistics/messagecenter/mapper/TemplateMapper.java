package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.crmforlogistics.messagecenter.entity.TemplateEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.Optional;

@Mapper
public interface TemplateMapper extends BaseMapper<TemplateEntity> {

    @Select("select * from message_templates where provider_scope_id = #{scopeId}::uuid "
            + "and provider_template_id = #{code} and language_code = #{language} "
            + "and deleted_at is null order by updated_at desc, id")
    Optional<TemplateEntity> findBySharedIdentity(@Param("scopeId") UUID scopeId,
                                                   @Param("code") String code,
                                                   @Param("language") String language);

    @Select("select * from message_templates where id = #{templateId}::uuid for update")
    Optional<TemplateEntity> findSharedForUpdate(@Param("templateId") UUID templateId);

    @Select("select * from message_templates where provider_scope_id = #{scopeId}::uuid "
            + "and deleted_at is null order by updated_at desc, id")
    List<TemplateEntity> findScopeTemplates(@Param("scopeId") UUID scopeId);

    @Select("select template.* from message_templates template join channel_accounts account "
            + "on account.id = template.channel_account_id "
            + "where account.provider_scope_id = #{scopeId}::uuid and template.deleted_at is null "
            + "order by template.updated_at desc nulls last, template.id")
    List<TemplateEntity> findTemplatesForScopeMigration(@Param("scopeId") UUID scopeId);

    @Update("update message_templates set provider_scope_id = #{scopeId}::uuid, updated_at = #{at} "
            + "where id = #{templateId}::uuid and (provider_scope_id is null or provider_scope_id = #{scopeId}::uuid)")
    int assignProviderScope(@Param("templateId") UUID templateId, @Param("scopeId") UUID scopeId,
                            @Param("at") Instant at);

    @Select("select * from message_templates where provider_scope_id = #{scopeId}::uuid "
            + "and upper(status) = 'APPROVED' and allow_send = true and deleted_at is null "
            + "order by updated_at desc, id")
    List<TemplateEntity> findScopeSendable(@Param("scopeId") UUID scopeId);

    @Update("update message_templates set deleted_at = #{at}, allow_send = false, "
            + "updated_at = #{at}, version = version + 1 where id = #{id}::uuid and deleted_at is null")
    int retireDuplicate(@Param("id") UUID id, @Param("at") Instant at);

    @Insert("insert into message_templates (id, channel_account_id, provider_scope_id, created_by_user_id, "
            + "provider_template_id, language_code, name, remark, body, status, category, template_type, "
            + "components_jsonb, examples_jsonb, message_send_ttl_seconds, allow_send, provider_audit_status, "
            + "rejection_reason, provider_updated_at, metadata_jsonb, last_synced_at, created_at, updated_at, deleted_at, version) "
            + "values (#{id}::uuid, #{channelAccountId}::uuid, #{providerScopeId}::uuid, #{createdByUserId}::uuid, "
            + "#{providerTemplateId}, #{languageCode}, #{name}, #{remark}, #{body}, #{status}, #{category}, #{templateType}, "
            + "cast(#{componentsJsonb} as jsonb), cast(#{examplesJsonb} as jsonb), #{messageSendTtlSeconds}, #{allowSend}, "
            + "#{providerAuditStatus}, #{rejectionReason}, #{providerUpdatedAt}, cast(#{metadataJsonb} as jsonb), "
            + "#{lastSyncedAt}, #{createdAt}, #{updatedAt}, #{deletedAt}, coalesce(#{version}, 0)) "
            + "on conflict (provider_scope_id, provider_template_id, language_code) "
            + "where provider_scope_id is not null do update set "
            + "name = excluded.name, body = coalesce(nullif(excluded.body, ''), message_templates.body), "
            + "status = excluded.status, category = excluded.category, components_jsonb = excluded.components_jsonb, "
            + "examples_jsonb = excluded.examples_jsonb, allow_send = excluded.allow_send, "
            + "provider_audit_status = excluded.provider_audit_status, rejection_reason = excluded.rejection_reason, "
            + "provider_updated_at = excluded.provider_updated_at, last_synced_at = excluded.last_synced_at, "
            + "updated_at = excluded.updated_at, deleted_at = excluded.deleted_at, version = message_templates.version + 1")
    int upsertShared(TemplateEntity entity);

    @Select("select id, channel_account_id, provider_template_id, language_code, name, remark, body, status, "
            + "category, template_type, components_jsonb, examples_jsonb, message_send_ttl_seconds, allow_send, "
            + "desired_allow_send, permission_sync_status, permission_sync_attempt_count, "
            + "permission_sync_next_attempt_at, permission_sync_error_code, permission_sync_error_message, "
            + "provider_audit_status, rejection_reason, quality_score, provider_updated_at, metadata_jsonb, "
            + "last_synced_at, created_at, updated_at, deleted_at, version "
            + "from message_templates "
            + "where channel_account_id = #{channelAccountId}::uuid "
            + "and upper(status) = 'APPROVED' "
            + "and allow_send = true "
            + "and deleted_at is null "
            + "order by updated_at desc")
    List<TemplateEntity> findSendableForChannelAccount(@Param("channelAccountId") UUID channelAccountId);

    @Select("select id, channel_account_id, provider_template_id, language_code, name, remark, body, status, "
            + "category, template_type, components_jsonb, examples_jsonb, message_send_ttl_seconds, allow_send, "
            + "desired_allow_send, permission_sync_status, permission_sync_attempt_count, "
            + "permission_sync_next_attempt_at, permission_sync_error_code, permission_sync_error_message, "
            + "provider_audit_status, rejection_reason, quality_score, provider_updated_at, metadata_jsonb, "
            + "last_synced_at, created_at, updated_at, deleted_at, version "
            + "from message_templates "
            + "where channel_account_id = #{channelAccountId}::uuid "
            + "and provider_template_id = #{providerTemplateId} "
            + "and language_code = #{languageCode} "
            + "and upper(status) = 'APPROVED' "
            + "and allow_send = true "
            + "and deleted_at is null "
            + "order by updated_at desc limit 1")
    Optional<TemplateEntity> findForSend(@Param("channelAccountId") UUID channelAccountId,
                                         @Param("providerTemplateId") String providerTemplateId,
                                         @Param("languageCode") String languageCode);

    @Select("select id, channel_account_id, provider_template_id, language_code, name, remark, body, status, "
            + "category, template_type, components_jsonb, examples_jsonb, message_send_ttl_seconds, allow_send, "
            + "desired_allow_send, permission_sync_status, permission_sync_attempt_count, "
            + "permission_sync_next_attempt_at, permission_sync_error_code, permission_sync_error_message, "
            + "provider_audit_status, rejection_reason, quality_score, provider_updated_at, metadata_jsonb, "
            + "last_synced_at, created_at, updated_at, deleted_at, version "
            + "from message_templates "
            + "where channel_account_id = #{channelAccountId}::uuid "
            + "and provider_template_id = #{providerTemplateId} "
            + "and language_code = #{languageCode} "
            + "order by updated_at desc limit 1")
    Optional<TemplateEntity> findForDisplay(@Param("channelAccountId") UUID channelAccountId,
                                            @Param("providerTemplateId") String providerTemplateId,
                                            @Param("languageCode") String languageCode);

    @Select("select id, channel_account_id, provider_template_id, language_code, name, remark, body, status, "
            + "category, template_type, components_jsonb, examples_jsonb, message_send_ttl_seconds, allow_send, "
            + "desired_allow_send, permission_sync_status, permission_sync_attempt_count, "
            + "permission_sync_next_attempt_at, permission_sync_error_code, permission_sync_error_message, "
            + "provider_audit_status, rejection_reason, quality_score, provider_updated_at, metadata_jsonb, "
            + "last_synced_at, created_at, updated_at, deleted_at, version "
            + "from message_templates "
            + "where channel_account_id = #{channelAccountId}::uuid "
            + "and provider_template_id = #{providerTemplateId} "
            + "and language_code = #{languageCode} "
            + "for update")
    Optional<TemplateEntity> findForUpdate(@Param("channelAccountId") UUID channelAccountId,
                                           @Param("providerTemplateId") String providerTemplateId,
                                           @Param("languageCode") String languageCode);

    @Update("update message_templates set remark = #{remark}, updated_at = #{updatedAt} where id = #{id}::uuid")
    int updateRemark(@Param("id") UUID id, @Param("remark") String remark, @Param("updatedAt") Instant updatedAt);

    @Select("select id, channel_account_id, provider_template_id, language_code, name, remark, body, status, "
            + "category, template_type, components_jsonb, examples_jsonb, message_send_ttl_seconds, allow_send, "
            + "desired_allow_send, permission_sync_status, permission_sync_attempt_count, "
            + "permission_sync_next_attempt_at, permission_sync_error_code, permission_sync_error_message, "
            + "provider_audit_status, rejection_reason, quality_score, provider_updated_at, metadata_jsonb, "
            + "last_synced_at, created_at, updated_at, deleted_at, version "
            + "from message_templates where deleted_at is null "
            + "and channel_account_id = #{channelAccountId}::uuid "
            + "and upper(status) = 'APPROVED' and desired_allow_send <> allow_send "
            + "and (permission_sync_next_attempt_at is null or permission_sync_next_attempt_at <= #{now}) "
            + "and (permission_sync_status <> 'PENDING' "
            + "or updated_at <= #{now}::timestamptz - interval '10 minutes') "
            + "order by updated_at, id limit #{limit}")
    List<TemplateEntity> findPermissionReconciliationCandidates(
            @Param("channelAccountId") UUID channelAccountId,
            @Param("now") Instant now,
            @Param("limit") int limit);

    @Update("update message_templates set permission_sync_status = 'PENDING', "
            + "permission_sync_next_attempt_at = null, permission_sync_error_code = null, "
            + "permission_sync_error_message = null, updated_at = #{now}, version = version + 1 "
            + "where id = #{id}::uuid and version = #{expectedVersion} and deleted_at is null "
            + "and upper(status) = 'APPROVED' and desired_allow_send <> allow_send "
            + "and (permission_sync_next_attempt_at is null or permission_sync_next_attempt_at <= #{now}) "
            + "and (permission_sync_status <> 'PENDING' "
            + "or updated_at <= #{now}::timestamptz - interval '10 minutes')")
    int markPermissionPending(@Param("id") UUID id,
                              @Param("expectedVersion") long expectedVersion,
                              @Param("now") Instant now);

    @Update("update message_templates set allow_send = #{actualAllowSend}, "
            + "permission_sync_status = case when desired_allow_send = #{desiredAllowSend} "
            + "and #{actualAllowSend} = #{desiredAllowSend} then 'IDLE' else 'FAILED' end, "
            + "permission_sync_attempt_count = case when #{actualAllowSend} = #{desiredAllowSend} "
            + "then 0 else permission_sync_attempt_count end, "
            + "permission_sync_next_attempt_at = case when #{actualAllowSend} = #{desiredAllowSend} "
            + "then null else #{now}::timestamptz + interval '1 minute' end, "
            + "permission_sync_error_code = case when #{actualAllowSend} = #{desiredAllowSend} "
            + "then null else 'TEMPLATE_PERMISSION_NOT_CONFIRMED' end, "
            + "permission_sync_error_message = case when #{actualAllowSend} = #{desiredAllowSend} "
            + "then null else 'Provider did not confirm desired permission' end, "
            + "updated_at = #{now}, version = version + 1 "
            + "where id = #{id}::uuid and desired_allow_send = #{desiredAllowSend}")
    int markPermissionSucceeded(@Param("id") UUID id,
                                @Param("desiredAllowSend") boolean desiredAllowSend,
                                @Param("actualAllowSend") boolean actualAllowSend,
                                @Param("now") Instant now);

    @Update("update message_templates set permission_sync_status = 'FAILED', "
            + "permission_sync_attempt_count = #{attemptCount}, "
            + "permission_sync_next_attempt_at = #{nextAttemptAt}, "
            + "permission_sync_error_code = #{errorCode}, "
            + "permission_sync_error_message = #{errorMessage}, "
            + "updated_at = #{now}, version = version + 1 "
            + "where id = #{id}::uuid and desired_allow_send = #{desiredAllowSend}")
    int markPermissionFailed(@Param("id") UUID id,
                             @Param("desiredAllowSend") boolean desiredAllowSend,
                             @Param("attemptCount") int attemptCount,
                             @Param("nextAttemptAt") Instant nextAttemptAt,
                             @Param("errorCode") String errorCode,
                             @Param("errorMessage") String errorMessage,
                             @Param("now") Instant now);

    @Update("INSERT INTO message_templates (id, channel_account_id, provider_template_id, language_code, name, body, status, provider_updated_at, metadata_jsonb, last_synced_at, created_at, updated_at) "
            + "VALUES (gen_random_uuid(), #{channelAccountId}::uuid, #{providerTemplateId}, #{languageCode}, #{name}, #{body}, #{status}, #{providerUpdatedAt}, #{metadataJsonb}::jsonb, #{lastSyncedAt}, now(), now()) "
            + "ON CONFLICT (channel_account_id, provider_template_id, language_code) DO UPDATE SET "
            + "name = excluded.name, body = COALESCE(NULLIF(excluded.body, ''), message_templates.body), "
            + "status = excluded.status, "
            + "provider_updated_at = excluded.provider_updated_at, metadata_jsonb = excluded.metadata_jsonb, "
            + "last_synced_at = excluded.last_synced_at, "
            + "updated_at = now()")
    int upsert(@Param("channelAccountId") UUID channelAccountId,
               @Param("providerTemplateId") String providerTemplateId,
               @Param("languageCode") String languageCode,
               @Param("name") String name,
               @Param("body") String body,
               @Param("status") String status,
               @Param("providerUpdatedAt") Instant providerUpdatedAt,
               @Param("metadataJsonb") String metadataJsonb,
               @Param("lastSyncedAt") Instant lastSyncedAt);
}
