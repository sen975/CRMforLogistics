package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.crmforlogistics.messagecenter.entity.WhatsAppProviderScopeEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface WhatsAppProviderScopeMapper extends BaseMapper<WhatsAppProviderScopeEntity> {

    String SCOPE_COLUMNS = "id, provider, external_scope_id, waba_id, scope_type, owner_user_id, "
            + "identity_status, encrypted_config, status, display_name, last_tested_at, last_test_status, "
            + "last_test_error_code, last_synced_at, last_sync_status, last_sync_error_code, version, created_at, updated_at ";

    @Select("select " + SCOPE_COLUMNS
            + "from whatsapp_provider_scopes where provider = #{provider} order by created_at asc")
    List<WhatsAppProviderScopeEntity> findAllByProvider(@Param("provider") String provider);

    @Select("select " + SCOPE_COLUMNS
            + "from whatsapp_provider_scopes where provider = #{provider} "
            + "and external_scope_id = #{externalScopeId} limit 1")
    WhatsAppProviderScopeEntity findByProviderAndExternalScopeId(
            @Param("provider") String provider,
            @Param("externalScopeId") String externalScopeId);

    @Select("select " + SCOPE_COLUMNS
            + "from whatsapp_provider_scopes where provider = #{provider} and waba_id = #{wabaId} limit 1")
    WhatsAppProviderScopeEntity findByProviderAndWabaId(@Param("provider") String provider,
                                                         @Param("wabaId") String wabaId);

    @Select("select " + SCOPE_COLUMNS
            + "from whatsapp_provider_scopes where provider = #{provider} and scope_type = 'ENTERPRISE_API' "
            + "and status = 'READY' order by created_at asc limit 1")
    WhatsAppProviderScopeEntity findEnterpriseApiScope(@Param("provider") String provider);

    @Select("select " + SCOPE_COLUMNS
            + "from whatsapp_provider_scopes where provider = #{provider} and waba_id = #{wabaId} "
            + "and scope_type = 'EMPLOYEE_BUSINESS_APP' and owner_user_id = #{ownerUserId}::uuid limit 1")
    WhatsAppProviderScopeEntity findOwnedBusinessAppScope(@Param("provider") String provider,
                                                           @Param("wabaId") String wabaId,
                                                           @Param("ownerUserId") java.util.UUID ownerUserId);

    @Insert("insert into whatsapp_provider_scopes "
            + "(provider, external_scope_id, waba_id, scope_type, owner_user_id, status, identity_status, "
            + "display_name) "
            + "values (#{provider}, #{externalScopeId}, #{wabaId}, #{scopeType}, #{ownerUserId}::uuid, "
            + "'READY', 'IDENTITY_VERIFIED', #{externalScopeId}) "
            + "on conflict (provider, external_scope_id) do nothing")
    int insertBound(@Param("provider") String provider,
                    @Param("externalScopeId") String externalScopeId,
                    @Param("wabaId") String wabaId,
                    @Param("scopeType") String scopeType,
                    @Param("ownerUserId") java.util.UUID ownerUserId);

    @Update("update whatsapp_provider_scopes set status = 'BLOCKED', updated_at = now() "
            + "where id = #{scopeId}::uuid and scope_type = 'ENTERPRISE_API' and status = 'READY'")
    int blockEnterpriseApiScope(@Param("scopeId") java.util.UUID scopeId);

    @Select("select " + SCOPE_COLUMNS
            + "from whatsapp_provider_scopes where provider = #{provider} "
            + "and scope_type = 'ENTERPRISE_API' and status = 'READY' order by created_at asc limit 1")
    WhatsAppProviderScopeEntity findEnterpriseScope(@Param("provider") String provider);

    @Update("update whatsapp_provider_scopes set waba_id = #{wabaId}, " +
            "identity_status = #{identityStatus}, updated_at = now() " +
            "where id = #{scopeId}::uuid and (waba_id is null or waba_id = #{wabaId})")
    int updateIdentity(@Param("scopeId") java.util.UUID scopeId,
                       @Param("wabaId") String wabaId,
                       @Param("identityStatus") String identityStatus);

    @Insert("insert into whatsapp_provider_scopes (provider, external_scope_id, status, display_name) "
            + "values (#{provider}, #{externalScopeId}, 'READY', #{externalScopeId}) "
            + "on conflict (provider, external_scope_id) do nothing")
    int insertIgnore(@Param("provider") String provider,
                     @Param("externalScopeId") String externalScopeId);

    @Update("update whatsapp_provider_scopes set encrypted_config = #{config}::jsonb, updated_at = now() " +
            "where id = #{scopeId}::uuid and (encrypted_config = '{}'::jsonb or encrypted_config is null)")
    int updateEncryptedConfigIfEmpty(@Param("scopeId") java.util.UUID scopeId,
                                     @Param("config") String config);

    @Insert("insert into whatsapp_provider_scopes " +
            "(provider, external_scope_id, scope_type, owner_user_id, status, identity_status, "
            + "encrypted_config, display_name) " +
            "values (#{provider}, #{externalScopeId}, 'ENTERPRISE_API', null, 'READY', " +
            "'IDENTITY_PENDING', #{encryptedConfig}::jsonb, #{externalScopeId}) " +
            "on conflict (provider, external_scope_id) do update set " +
            "scope_type = 'ENTERPRISE_API', owner_user_id = null, status = 'READY', " +
            "encrypted_config = excluded.encrypted_config, updated_at = now()")
    int upsertAdminConfigured(@Param("provider") String provider,
                              @Param("externalScopeId") String externalScopeId,
                              @Param("encryptedConfig") String encryptedConfig);

    /** Both space types, because an administrator administers a Business App space as well as an enterprise one. */
    @Select("select " + SCOPE_COLUMNS + " from whatsapp_provider_scopes "
            + "where provider = 'ALIYUN_CAMS' order by created_at asc, id asc")
    List<WhatsAppProviderScopeEntity> findAllAdminScopes();

    @Select("select " + SCOPE_COLUMNS + " from whatsapp_provider_scopes "
            + "where id = #{scopeId}::uuid and provider = 'ALIYUN_CAMS' limit 1")
    WhatsAppProviderScopeEntity findAdminScopeById(@Param("scopeId") java.util.UUID scopeId);

    @Insert("insert into whatsapp_provider_scopes "
            + "(provider, external_scope_id, display_name, scope_type, owner_user_id, status, identity_status, encrypted_config) "
            + "values ('ALIYUN_CAMS', #{externalScopeId}, #{displayName}, #{scopeType}, #{ownerUserId}::uuid, 'READY', "
            + "'IDENTITY_PENDING', #{encryptedConfig}::jsonb) on conflict (provider, external_scope_id) do nothing")
    int insertAdminScope(@Param("externalScopeId") String externalScopeId,
                         @Param("displayName") String displayName,
                         @Param("encryptedConfig") String encryptedConfig,
                         @Param("scopeType") String scopeType,
                         @Param("ownerUserId") java.util.UUID ownerUserId);

    @Update("update whatsapp_provider_scopes set external_scope_id = #{externalScopeId}, display_name = #{displayName}, "
            + "scope_type = #{scopeType}, owner_user_id = #{ownerUserId}::uuid, "
            + "encrypted_config = #{encryptedConfig}::jsonb, updated_at = now(), version = version + 1 "
            + "where id = #{scopeId}::uuid and provider = 'ALIYUN_CAMS' "
            + "and version = #{expectedVersion}")
    int updateAdminScope(@Param("scopeId") java.util.UUID scopeId,
                         @Param("externalScopeId") String externalScopeId,
                         @Param("displayName") String displayName,
                         @Param("encryptedConfig") String encryptedConfig,
                         @Param("scopeType") String scopeType,
                         @Param("ownerUserId") java.util.UUID ownerUserId,
                         @Param("expectedVersion") long expectedVersion);

    @Update("update whatsapp_provider_scopes set status = 'BLOCKED', updated_at = now(), version = version + 1 "
            + "where id = #{scopeId}::uuid and provider = 'ALIYUN_CAMS' "
            + "and status = 'READY' and version = #{expectedVersion}")
    int blockAdminScope(@Param("scopeId") java.util.UUID scopeId,
                        @Param("expectedVersion") long expectedVersion);

    @Update("update whatsapp_provider_scopes set last_tested_at = now(), last_test_status = #{status}, "
            + "last_test_error_code = #{errorCode}, updated_at = now(), version = version + 1 "
            + "where id = #{scopeId}::uuid")
    int touchTestResult(@Param("scopeId") java.util.UUID scopeId,
                        @Param("status") String status,
                        @Param("errorCode") String errorCode);

    @Update("update whatsapp_provider_scopes set last_synced_at = now(), last_sync_status = #{status}, "
            + "last_sync_error_code = #{errorCode}, updated_at = now(), version = version + 1 "
            + "where id = #{scopeId}::uuid")
    int touchSyncResult(@Param("scopeId") java.util.UUID scopeId,
                        @Param("status") String status,
                        @Param("errorCode") String errorCode);
}
