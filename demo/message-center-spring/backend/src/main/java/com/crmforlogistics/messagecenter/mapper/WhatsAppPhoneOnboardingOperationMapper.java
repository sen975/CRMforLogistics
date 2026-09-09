package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.crmforlogistics.messagecenter.entity.WhatsAppPhoneOnboardingOperationEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.UUID;

@Mapper
public interface WhatsAppPhoneOnboardingOperationMapper
        extends BaseMapper<WhatsAppPhoneOnboardingOperationEntity> {
    @Select("select id, user_id, provider_scope_id, phone_number, country_code, verified_name, status, " +
            "created_at, updated_at from whatsapp_phone_onboarding_operations " +
            "where user_id = #{userId}::uuid and provider_scope_id = #{scopeId}::uuid " +
            "and phone_number = #{phoneNumber} limit 1")
    WhatsAppPhoneOnboardingOperationEntity find(@Param("userId") UUID userId,
                                                @Param("scopeId") UUID scopeId,
                                                @Param("phoneNumber") String phoneNumber);

    @Insert("insert into whatsapp_phone_onboarding_operations " +
            "(id, user_id, provider_scope_id, phone_number, country_code, verified_name, status) " +
            "values (#{id}::uuid, #{userId}::uuid, #{providerScopeId}::uuid, #{phoneNumber}, " +
            "#{countryCode}, #{verifiedName}, #{status})")
    int insert(WhatsAppPhoneOnboardingOperationEntity entity);

    @Update("update whatsapp_phone_onboarding_operations set status = #{status}, updated_at = now() " +
            "where id = #{id}::uuid and user_id = #{userId}::uuid")
    int updateStatus(@Param("id") UUID id, @Param("userId") UUID userId, @Param("status") String status);
}
