package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.crmforlogistics.messagecenter.entity.WhatsAppProviderScopeEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface WhatsAppProviderScopeMapper extends BaseMapper<WhatsAppProviderScopeEntity> {

    @Select("select id, provider, external_scope_id, status, created_at, updated_at "
            + "from whatsapp_provider_scopes where provider = #{provider} order by created_at asc")
    List<WhatsAppProviderScopeEntity> findAllByProvider(@Param("provider") String provider);

    @Select("select id, provider, external_scope_id, status, created_at, updated_at "
            + "from whatsapp_provider_scopes where provider = #{provider} "
            + "and external_scope_id = #{externalScopeId} limit 1")
    WhatsAppProviderScopeEntity findByProviderAndExternalScopeId(
            @Param("provider") String provider,
            @Param("externalScopeId") String externalScopeId);

    @Insert("insert into whatsapp_provider_scopes (provider, external_scope_id, status) "
            + "values (#{provider}, #{externalScopeId}, 'READY') on conflict (provider, external_scope_id) do nothing")
    int insertIgnore(@Param("provider") String provider,
                     @Param("externalScopeId") String externalScopeId);
}
