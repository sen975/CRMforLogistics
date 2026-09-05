package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.crmforlogistics.messagecenter.entity.WhatsAppTemplateMigrationExceptionEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.UUID;

@Mapper
public interface WhatsAppTemplateMigrationMapper
        extends BaseMapper<WhatsAppTemplateMigrationExceptionEntity> {

    @Insert("insert into whatsapp_template_migration_state "
            + "(migration_key, status, started_at, updated_at) values ('shared-template-v1', 'RUNNING', now(), now()) "
            + "on conflict (migration_key) do update set status = 'RUNNING', started_at = now(), "
            + "completed_at = null, updated_at = now()")
    int markRunning();

    @Update("delete from whatsapp_template_migration_exceptions where migration_key = 'shared-template-v1'")
    int clearExceptions();

    @Update("update template_operations set template_id = #{survivorId}::uuid "
            + "where template_id = #{duplicateId}::uuid")
    int rewireOperations(@Param("duplicateId") UUID duplicateId, @Param("survivorId") UUID survivorId);

    @Update("insert into template_media_bindings (template_id, media_asset_id, change_request_id, bound_at) "
            + "select #{survivorId}::uuid, media_asset_id, change_request_id, bound_at "
            + "from template_media_bindings where template_id = #{duplicateId}::uuid "
            + "on conflict (template_id, media_asset_id) do nothing")
    int copyMediaBindings(@Param("duplicateId") UUID duplicateId, @Param("survivorId") UUID survivorId);

    @Update("delete from template_media_bindings where template_id = #{duplicateId}::uuid")
    int deleteDuplicateMediaBindings(@Param("duplicateId") UUID duplicateId);

    @Update("update template_operations operation set template_id = template.id "
            + "from channel_accounts account, message_templates template "
            + "where operation.template_id is null and operation.channel_account_id = account.id "
            + "and account.provider_scope_id = #{scopeId}::uuid "
            + "and template.provider_scope_id = #{scopeId}::uuid and template.deleted_at is null "
            + "and template.provider_template_id = operation.provider_template_id "
            + "and template.language_code = operation.language_code")
    int linkOperationsByIdentity(@Param("scopeId") UUID scopeId);

    @Select("select count(*) from template_operations operation join channel_accounts account "
            + "on account.id = operation.channel_account_id where account.provider_scope_id = #{scopeId}::uuid "
            + "and operation.template_id is null and operation.operation_type <> 'RETIRED' "
            + "and nullif(operation.provider_template_id, '') is not null")
    int countUnlinkedOperations(@Param("scopeId") UUID scopeId);

    @Select("select count(*) from template_media_assets asset join channel_accounts account "
            + "on account.id = asset.channel_account_id where account.provider_scope_id = #{scopeId}::uuid "
            + "and asset.asset_status = 'ATTACHED' and not exists "
            + "(select 1 from template_media_bindings binding where binding.media_asset_id = asset.id)")
    int countUnlinkedAttachedMedia(@Param("scopeId") UUID scopeId);

    @Insert("insert into whatsapp_template_migration_exceptions "
            + "(id, migration_key, resource_type, reason_code, details_jsonb) "
            + "values (gen_random_uuid(), 'shared-template-v1', #{resourceType}, #{reasonCode}, "
            + "jsonb_build_object('count', #{count}))")
    int insertCountException(@Param("resourceType") String resourceType,
                             @Param("reasonCode") String reasonCode,
                             @Param("count") int count);
}
