package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.crmforlogistics.messagecenter.entity.TemplateEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.Optional;

@Mapper
public interface TemplateMapper extends BaseMapper<TemplateEntity> {

    @Select("select id, channel_account_id, provider_template_id, language_code, name, body, status, "
            + "category, template_type, components_jsonb, examples_jsonb, message_send_ttl_seconds, allow_send, "
            + "provider_audit_status, rejection_reason, quality_score, provider_updated_at, metadata_jsonb, "
            + "last_synced_at, created_at, updated_at, deleted_at, version "
            + "from message_templates "
            + "where channel_account_id = #{channelAccountId}::uuid "
            + "and upper(status) = 'APPROVED' "
            + "and allow_send = true "
            + "and deleted_at is null "
            + "order by updated_at desc")
    List<TemplateEntity> findSendableForChannelAccount(@Param("channelAccountId") UUID channelAccountId);

    @Select("select id, channel_account_id, provider_template_id, language_code, name, body, status, "
            + "category, template_type, components_jsonb, examples_jsonb, message_send_ttl_seconds, allow_send, "
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

    @Select("select id, channel_account_id, provider_template_id, language_code, name, body, status, "
            + "category, template_type, components_jsonb, examples_jsonb, message_send_ttl_seconds, allow_send, "
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
