package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.crmforlogistics.messagecenter.entity.WhatsAppCallbackConfigEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.UUID;

@Mapper
public interface WhatsAppCallbackConfigMapper extends BaseMapper<WhatsAppCallbackConfigEntity> {
    String COLUMNS = "id, provider_scope_id, channel_account_id, level, desired_up_callback_url, "
            + "desired_status_callback_url, http_flag, queue_flag, provider_state, last_apply_status, "
            + "apply_token, apply_started_at, last_provider_request_id, last_error_code, last_applied_at, version, created_at, updated_at ";

    @Update("update whatsapp_cams_callback_configs set last_apply_status = 'APPLYING', " +
            "apply_token = #{token}::uuid, apply_started_at = now(), version = version + 1, updated_at = now() " +
            "where id = #{id}::uuid and version = #{expectedVersion} " +
            "and (last_apply_status <> 'APPLYING' or apply_started_at < now() - interval '5 minutes')")
    int claimApply(@Param("id") UUID id, @Param("expectedVersion") long expectedVersion,
                   @Param("token") UUID token);

    @Select("select " + COLUMNS + "from whatsapp_cams_callback_configs "
            + "where provider_scope_id = #{scopeId}::uuid and level = 'PHONE' "
            + "and channel_account_id = #{accountId}::uuid limit 1")
    WhatsAppCallbackConfigEntity findPhone(@Param("scopeId") UUID scopeId,
                                            @Param("accountId") UUID accountId);

    @Select("select " + COLUMNS + "from whatsapp_cams_callback_configs "
            + "where provider_scope_id = #{scopeId}::uuid and level = 'ACCOUNT' limit 1")
    WhatsAppCallbackConfigEntity findAccount(@Param("scopeId") UUID scopeId);

    @Select("select " + COLUMNS + "from whatsapp_cams_callback_configs "
            + "where provider_scope_id = #{scopeId}::uuid order by level, channel_account_id")
    List<WhatsAppCallbackConfigEntity> findByScope(@Param("scopeId") UUID scopeId);

    @Insert("insert into whatsapp_cams_callback_configs "
            + "(provider_scope_id, channel_account_id, level, desired_up_callback_url, "
            + "desired_status_callback_url, http_flag, queue_flag) "
            + "values (#{scopeId}::uuid, #{accountId}::uuid, 'PHONE', #{upUrl}, #{statusUrl}, #{httpFlag}, #{queueFlag}) "
            + "on conflict (provider_scope_id, channel_account_id) where level = 'PHONE' do nothing")
    int insertPhone(@Param("scopeId") UUID scopeId, @Param("accountId") UUID accountId,
                    @Param("upUrl") String upUrl, @Param("statusUrl") String statusUrl,
                    @Param("httpFlag") String httpFlag, @Param("queueFlag") String queueFlag);

    @Insert("insert into whatsapp_cams_callback_configs "
            + "(provider_scope_id, level, desired_status_callback_url, http_flag, queue_flag) "
            + "values (#{scopeId}::uuid, 'ACCOUNT', #{statusUrl}, #{httpFlag}, #{queueFlag}) "
            + "on conflict (provider_scope_id) where level = 'ACCOUNT' do nothing")
    int insertAccount(@Param("scopeId") UUID scopeId, @Param("statusUrl") String statusUrl,
                      @Param("httpFlag") String httpFlag, @Param("queueFlag") String queueFlag);

    @Update("update whatsapp_cams_callback_configs set last_apply_status = 'SUCCEEDED', "
            + "last_provider_request_id = #{requestId}, last_error_code = null, last_applied_at = now(), "
            + "provider_state = 'UNKNOWN', version = version + 1, updated_at = now() "
            + "where id = #{id}::uuid and version = #{expectedVersion} and apply_token = #{token}::uuid")
    int updateApplySuccess(@Param("id") UUID id, @Param("expectedVersion") long expectedVersion,
                           @Param("requestId") String requestId, @Param("token") UUID token);

    @Update("update whatsapp_cams_callback_configs set desired_up_callback_url = #{upUrl}, "
            + "desired_status_callback_url = #{statusUrl}, http_flag = #{httpFlag}, queue_flag = #{queueFlag}, "
            + "last_apply_status = 'SUCCEEDED', last_provider_request_id = #{requestId}, last_error_code = null, "
            + "last_applied_at = now(), provider_state = 'UNKNOWN', version = version + 1, updated_at = now() "
            + "where id = #{id}::uuid and version = #{expectedVersion} and apply_token = #{token}::uuid")
    int updatePhoneDesiredAndApplySuccess(@Param("id") UUID id, @Param("expectedVersion") long expectedVersion,
                                          @Param("upUrl") String upUrl, @Param("statusUrl") String statusUrl,
                                          @Param("httpFlag") String httpFlag, @Param("queueFlag") String queueFlag,
                                          @Param("requestId") String requestId,
                                          @Param("token") UUID token);

    @Update("update whatsapp_cams_callback_configs set desired_status_callback_url = #{statusUrl}, "
            + "http_flag = #{httpFlag}, queue_flag = #{queueFlag}, last_apply_status = 'SUCCEEDED', "
            + "last_provider_request_id = #{requestId}, last_error_code = null, last_applied_at = now(), "
            + "provider_state = 'UNKNOWN', version = version + 1, updated_at = now() "
            + "where id = #{id}::uuid and version = #{expectedVersion} and apply_token = #{token}::uuid")
    int updateAccountDesiredAndApplySuccess(@Param("id") UUID id, @Param("expectedVersion") long expectedVersion,
                                            @Param("statusUrl") String statusUrl, @Param("httpFlag") String httpFlag,
                                            @Param("queueFlag") String queueFlag, @Param("requestId") String requestId,
                                            @Param("token") UUID token);

    @Update("update whatsapp_cams_callback_configs set last_apply_status = 'FAILED', "
            + "last_provider_request_id = #{requestId}, last_error_code = #{errorCode}, "
            + "updated_at = now() where id = #{id}::uuid and version = #{expectedVersion} and apply_token = #{token}::uuid")
    int updateApplyFailure(@Param("id") UUID id, @Param("expectedVersion") long expectedVersion,
                           @Param("requestId") String requestId, @Param("errorCode") String errorCode,
                           @Param("token") UUID token);

    @Update("update whatsapp_cams_callback_configs set last_apply_status = 'SUBMISSION_UNKNOWN', " +
            "last_provider_request_id = #{requestId}, last_error_code = #{errorCode}, " +
            "updated_at = now() where id = #{id}::uuid and version = #{expectedVersion} " +
            "and apply_token = #{token}::uuid")
    int updateApplyUnknown(@Param("id") UUID id, @Param("expectedVersion") long expectedVersion,
                           @Param("requestId") String requestId, @Param("errorCode") String errorCode,
                           @Param("token") UUID token);
}
