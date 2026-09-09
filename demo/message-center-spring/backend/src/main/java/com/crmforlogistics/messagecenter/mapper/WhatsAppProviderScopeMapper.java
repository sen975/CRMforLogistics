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

    @Select("select id, provider, external_scope_id, waba_id, scope_type, owner_user_id, identity_status, encrypted_config, status, created_at, updated_at "
            + "from whatsapp_provider_scopes where provider = #{provider} order by created_at asc")
    List<WhatsAppProviderScopeEntity> findAllByProvider(@Param("provider") String provider);

    @Select("select id, provider, external_scope_id, waba_id, scope_type, owner_user_id, identity_status, encrypted_config, status, created_at, updated_at "
            + "from whatsapp_provider_scopes where provider = #{provider} "
            + "and external_scope_id = #{externalScopeId} limit 1")
    WhatsAppProviderScopeEntity findByProviderAndExternalScopeId(
            @Param("provider") String provider,
            @Param("externalScopeId") String externalScopeId);

    @Select("select id, provider, external_scope_id, waba_id, scope_type, owner_user_id, identity_status, encrypted_config, status, created_at, updated_at "
            + "from whatsapp_provider_scopes where provider = #{provider} and waba_id = #{wabaId} limit 1")
    WhatsAppProviderScopeEntity findByProviderAndWabaId(@Param("provider") String provider,
                                                         @Param("wabaId") String wabaId);

    @Select("select id, provider, external_scope_id, waba_id, scope_type, owner_user_id, identity_status, encrypted_config, status, created_at, updated_at "
            + "from whatsapp_provider_scopes where provider = #{provider} and scope_type = 'ENTERPRISE_API' "
            + "and status = 'READY' order by created_at asc limit 1")
    WhatsAppProviderScopeEntity findEnterpriseApiScope(@Param("provider") String provider);

    @Select("select id, provider, external_scope_id, waba_id, scope_type, owner_user_id, identity_status, encrypted_config, status, created_at, updated_at "
            + "from whatsapp_provider_scopes where provider = #{provider} and waba_id = #{wabaId} "
            + "and scope_type = 'EMPLOYEE_BUSINESS_APP' and owner_user_id = #{ownerUserId}::uuid limit 1")
    WhatsAppProviderScopeEntity findOwnedBusinessAppScope(@Param("provider") String provider,
                                                           @Param("wabaId") String wabaId,
                                                           @Param("ownerUserId") java.util.UUID ownerUserId);

    @Insert("insert into whatsapp_provider_scopes "
            + "(provider, external_scope_id, waba_id, scope_type, owner_user_id, status, identity_status) "
            + "values (#{provider}, #{externalScopeId}, #{wabaId}, #{scopeType}, #{ownerUserId}::uuid, "
            + "'READY', 'IDENTITY_VERIFIED') on conflict (provider, external_scope_id) do nothing")
    int insertBound(@Param("provider") String provider,
                    @Param("externalScopeId") String externalScopeId,
                    @Param("wabaId") String wabaId,
                    @Param("scopeType") String scopeType,
                    @Param("ownerUserId") java.util.UUID ownerUserId);

    @Update("update whatsapp_provider_scopes set status = 'BLOCKED', updated_at = now() "
            + "where id = #{scopeId}::uuid and scope_type = 'ENTERPRISE_API' and status = 'READY'")
    int blockEnterpriseApiScope(@Param("scopeId") java.util.UUID scopeId);

    @Select("select id, provider, external_scope_id, waba_id, scope_type, owner_user_id, identity_status, encrypted_config, status, created_at, updated_at "
            + "from whatsapp_provider_scopes where provider = #{provider} "
            + "and scope_type = 'ENTERPRISE_API' and status = 'READY' order by created_at asc limit 1")
    WhatsAppProviderScopeEntity findEnterpriseScope(@Param("provider") String provider);

    @Update("update whatsapp_provider_scopes set waba_id = #{wabaId}, " +
            "identity_status = #{identityStatus}, updated_at = now() " +
            "where id = #{scopeId}::uuid and (waba_id is null or waba_id = #{wabaId})")
    int updateIdentity(@Param("scopeId") java.util.UUID scopeId,
                       @Param("wabaId") String wabaId,
                       @Param("identityStatus") String identityStatus);

    @Insert("insert into whatsapp_provider_scopes (provider, external_scope_id, status) "
            + "values (#{provider}, #{externalScopeId}, 'READY') on conflict (provider, external_scope_id) do nothing")
    int insertIgnore(@Param("provider") String provider,
                     @Param("externalScopeId") String externalScopeId);

    @Update("update whatsapp_provider_scopes set encrypted_config = #{config}::jsonb, updated_at = now() " +
            "where id = #{scopeId}::uuid and (encrypted_config = '{}'::jsonb or encrypted_config is null)")
    int updateEncryptedConfigIfEmpty(@Param("scopeId") java.util.UUID scopeId,
                                     @Param("config") String config);
}
