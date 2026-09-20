package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.crmforlogistics.messagecenter.entity.TemplateEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Mapper
public interface TemplateMapper extends BaseMapper<TemplateEntity> {

    @Select("select * from message_templates where provider_scope_id = #{scopeId}::uuid "
            + "and provider_template_id = #{code} and language_code = #{language} "
            + "and template_domain = 'ENTERPRISE_API' and deleted_at is null order by updated_at desc, id")
    Optional<TemplateEntity> findBySharedIdentity(@Param("scopeId") UUID scopeId,
                                                   @Param("code") String code,
                                                   @Param("language") String language);

    @Select("select * from message_templates where channel_account_id = #{accountId}::uuid "
            + "and template_domain = 'EMPLOYEE_BUSINESS_APP' "
            + "and provider_template_id = #{code} and language_code = #{language} "
            + "and deleted_at is null order by updated_at desc, id")
    Optional<TemplateEntity> findByPrivateIdentity(@Param("accountId") UUID accountId,
                                                    @Param("code") String code,
                                                    @Param("language") String language);

    @Select("select * from message_templates where id = #{templateId}::uuid "
            + "and template_domain = 'ENTERPRISE_API' for update")
    Optional<TemplateEntity> findSharedForUpdate(@Param("templateId") UUID templateId);

    @Select("select * from message_templates where id = #{templateId}::uuid "
            + "and channel_account_id = #{accountId}::uuid "
            + "and template_domain = 'EMPLOYEE_BUSINESS_APP' for update")
    Optional<TemplateEntity> findPrivateForUpdate(@Param("templateId") UUID templateId,
                                                   @Param("accountId") UUID accountId);

    @Select("select * from message_templates where provider_scope_id = #{scopeId}::uuid "
            + "and template_domain = 'ENTERPRISE_API' and deleted_at is null order by updated_at desc, id")
    List<TemplateEntity> findScopeTemplates(@Param("scopeId") UUID scopeId);

    @Select("select count(*) from message_templates where provider_scope_id = #{scopeId}::uuid "
            + "and template_domain = 'ENTERPRISE_API' and deleted_at is null")
    long countLiveSharedForScope(@Param("scopeId") UUID scopeId);

    @Select("select * from message_templates where channel_account_id = #{accountId}::uuid "
            + "and template_domain = 'EMPLOYEE_BUSINESS_APP' and deleted_at is null "
            + "order by updated_at desc nulls last, id")
    List<TemplateEntity> findPrivateForAccount(@Param("accountId") UUID accountId);

    @Select("select template.* from message_templates template join channel_accounts account "
            + "on account.id = template.channel_account_id "
            + "where account.provider_scope_id = #{scopeId}::uuid "
            + "and template.template_domain = 'ENTERPRISE_API' and template.deleted_at is null "
            + "order by template.updated_at desc nulls last, template.id")
    List<TemplateEntity> findTemplatesForScopeMigration(@Param("scopeId") UUID scopeId);

    @Update("update message_templates set provider_scope_id = #{scopeId}::uuid, updated_at = #{at} "
            + "where id = #{templateId}::uuid and (provider_scope_id is null or provider_scope_id = #{scopeId}::uuid)")
    int assignProviderScope(@Param("templateId") UUID templateId, @Param("scopeId") UUID scopeId,
                            @Param("at") Instant at);

    @Select("select * from message_templates where provider_scope_id = #{scopeId}::uuid "
            + "and template_domain = 'ENTERPRISE_API' and upper(status) = 'APPROVED' and allow_send = true and deleted_at is null "
            + "order by updated_at desc, id")
    List<TemplateEntity> findScopeSendable(@Param("scopeId") UUID scopeId);

    @Update("update message_templates set deleted_at = #{at}, allow_send = false, "
            + "updated_at = #{at}, version = version + 1 where id = #{id}::uuid and deleted_at is null")
    int retireDuplicate(@Param("id") UUID id, @Param("at") Instant at);

    @Insert("insert into message_templates (id, channel_account_id, provider_scope_id, template_domain, created_by_user_id, "
            + "provider_template_id, language_code, name, remark, body, status, category, template_type, "
            + "components_jsonb, examples_jsonb, message_send_ttl_seconds, allow_send, provider_audit_status, "
            + "rejection_reason, provider_updated_at, last_synced_at, created_at, updated_at, deleted_at, version) "
            + "values (#{id}::uuid, #{channelAccountId}::uuid, #{providerScopeId}::uuid, 'ENTERPRISE_API', #{createdByUserId}::uuid, "
            + "#{providerTemplateId}, #{languageCode}, #{name}, #{remark}, #{body}, #{status}, #{category}, #{templateType}, "
            + "cast(#{componentsJsonb} as jsonb), cast(#{examplesJsonb} as jsonb), #{messageSendTtlSeconds}, #{allowSend}, "
            + "#{providerAuditStatus}, #{rejectionReason}, #{providerUpdatedAt}, "
            + "#{lastSyncedAt}, #{createdAt}, #{updatedAt}, #{deletedAt}, coalesce(#{version}, 0)) "
            + "on conflict (provider_scope_id, provider_template_id, language_code) "
            + "where template_domain = 'ENTERPRISE_API' and provider_scope_id is not null and deleted_at is null do update set "
            + "name = excluded.name, body = coalesce(nullif(excluded.body, ''), message_templates.body), "
            + "status = excluded.status, category = excluded.category, components_jsonb = excluded.components_jsonb, "
            + "examples_jsonb = excluded.examples_jsonb, allow_send = excluded.allow_send, "
            + "provider_audit_status = excluded.provider_audit_status, rejection_reason = excluded.rejection_reason, "
            + "provider_updated_at = excluded.provider_updated_at, last_synced_at = excluded.last_synced_at, "
            + "updated_at = excluded.updated_at, deleted_at = excluded.deleted_at, version = message_templates.version + 1")
    int upsertShared(TemplateEntity entity);

    @Insert("insert into message_templates (id, channel_account_id, provider_scope_id, template_domain, created_by_user_id, "
            + "provider_template_id, language_code, name, remark, body, status, category, template_type, "
            + "components_jsonb, examples_jsonb, message_send_ttl_seconds, allow_send, provider_audit_status, "
            + "rejection_reason, provider_updated_at, last_synced_at, created_at, updated_at, deleted_at, version) "
            + "values (#{id}::uuid, #{channelAccountId}::uuid, #{providerScopeId}::uuid, 'EMPLOYEE_BUSINESS_APP', #{createdByUserId}::uuid, "
            + "#{providerTemplateId}, #{languageCode}, #{name}, #{remark}, #{body}, #{status}, #{category}, #{templateType}, "
            + "cast(#{componentsJsonb} as jsonb), cast(#{examplesJsonb} as jsonb), #{messageSendTtlSeconds}, #{allowSend}, "
            + "#{providerAuditStatus}, #{rejectionReason}, #{providerUpdatedAt}, "
            + "#{lastSyncedAt}, #{createdAt}, #{updatedAt}, #{deletedAt}, coalesce(#{version}, 0)) "
            + "on conflict (channel_account_id, provider_template_id, language_code) "
            + "where template_domain = 'EMPLOYEE_BUSINESS_APP' and deleted_at is null do update set "
            + "name = excluded.name, body = coalesce(nullif(excluded.body, ''), message_templates.body), "
            + "status = excluded.status, category = excluded.category, components_jsonb = excluded.components_jsonb, "
            + "examples_jsonb = excluded.examples_jsonb, allow_send = excluded.allow_send, "
            + "provider_audit_status = excluded.provider_audit_status, rejection_reason = excluded.rejection_reason, "
            + "provider_updated_at = excluded.provider_updated_at, last_synced_at = excluded.last_synced_at, "
            + "updated_at = excluded.updated_at, deleted_at = excluded.deleted_at, version = message_templates.version + 1")
    int upsertPrivate(TemplateEntity entity);

    @Select("select * from message_templates where provider_scope_id = #{providerScopeId}::uuid "
            + "and provider_template_id = #{providerTemplateId} and language_code = #{languageCode} "
            + "and template_domain = 'ENTERPRISE_API' and upper(status) = 'APPROVED' and allow_send = true and deleted_at is null limit 1")
    Optional<TemplateEntity> findSharedForSend(@Param("providerScopeId") UUID providerScopeId,
                                                @Param("providerTemplateId") String providerTemplateId,
                                               @Param("languageCode") String languageCode);

    @Select("select * from message_templates where channel_account_id = #{accountId}::uuid "
            + "and template_domain = 'EMPLOYEE_BUSINESS_APP' "
            + "and provider_template_id = #{providerTemplateId} and language_code = #{languageCode} "
            + "and upper(status) = 'APPROVED' and allow_send = true and deleted_at is null limit 1")
    Optional<TemplateEntity> findPrivateForSend(@Param("accountId") UUID accountId,
                                                  @Param("providerTemplateId") String providerTemplateId,
                                                  @Param("languageCode") String languageCode);

    @Select("select * from message_templates where provider_scope_id = #{providerScopeId}::uuid "
            + "and provider_template_id = #{providerTemplateId} and language_code = #{languageCode} "
            + "and template_domain = 'ENTERPRISE_API' and deleted_at is null limit 1")
    Optional<TemplateEntity> findSharedForDisplay(@Param("providerScopeId") UUID providerScopeId,
                                                   @Param("providerTemplateId") String providerTemplateId,
                                                   @Param("languageCode") String languageCode);

    @Select("select * from message_templates where id = #{templateId}::uuid "
            + "and channel_account_id = #{accountId}::uuid and template_domain = 'EMPLOYEE_BUSINESS_APP' "
            + "and deleted_at is null limit 1")
    Optional<TemplateEntity> findPrivateForDisplay(@Param("templateId") UUID templateId,
                                                    @Param("accountId") UUID accountId);

    @Select("select * from message_templates where channel_account_id = #{accountId}::uuid "
            + "and template_domain = 'EMPLOYEE_BUSINESS_APP' and provider_template_id = #{providerTemplateId} "
            + "and language_code = #{languageCode} and deleted_at is null limit 1")
    Optional<TemplateEntity> findPrivateForDisplayByIdentity(@Param("accountId") UUID accountId,
                                                              @Param("providerTemplateId") String providerTemplateId,
                                                              @Param("languageCode") String languageCode);

    @Select("select * from message_templates where channel_account_id = #{accountId}::uuid "
            + "and template_domain = 'EMPLOYEE_BUSINESS_APP' and deleted_at is null "
            + "order by updated_at desc nulls last, id")
    List<TemplateEntity> findPrivateForSendable(@Param("accountId") UUID accountId);

    @Select("select * from message_templates where provider_scope_id = #{providerScopeId}::uuid "
            + "and template_domain = 'ENTERPRISE_API' and deleted_at is null and upper(status) = 'APPROVED' and allow_send = true "
            + "order by updated_at desc nulls last, id")
    List<TemplateEntity> findSharedSendableForScope(@Param("providerScopeId") UUID providerScopeId);

    @Select("select * from message_templates where provider_scope_id = #{scopeId}::uuid "
            + "and template_domain = 'ENTERPRISE_API' and deleted_at is null order by updated_at desc nulls last, id")
    List<TemplateEntity> findSharedForScope(@Param("scopeId") UUID scopeId);

    @Update("update message_templates set remark = #{remark}, updated_at = now() where id = #{templateId}::uuid")
    int updateRemark(@Param("templateId") UUID templateId, @Param("remark") String remark);
}
