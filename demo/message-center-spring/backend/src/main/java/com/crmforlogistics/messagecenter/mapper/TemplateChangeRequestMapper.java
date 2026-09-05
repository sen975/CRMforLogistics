package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.crmforlogistics.messagecenter.entity.TemplateChangeRequestEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Mapper
public interface TemplateChangeRequestMapper extends BaseMapper<TemplateChangeRequestEntity> {
    @Insert("insert into template_change_requests (id, template_id, change_type, requested_payload_jsonb, base_version, "
            + "requested_by_user_id, requested_via_account_id, status, idempotency_key, created_at, updated_at) values "
            + "(#{id}::uuid, #{templateId}::uuid, #{changeType}, cast(#{requestedPayloadJsonb} as jsonb), #{baseVersion}, "
            + "#{requestedByUserId}::uuid, #{requestedViaAccountId}::uuid, #{status}, #{idempotencyKey}, #{createdAt}, #{updatedAt}) "
            + "on conflict (requested_by_user_id, idempotency_key) do nothing")
    int insertIgnore(TemplateChangeRequestEntity entity);

    @Select("select * from template_change_requests where requested_by_user_id = #{userId}::uuid "
            + "and idempotency_key = #{idempotencyKey} limit 1")
    Optional<TemplateChangeRequestEntity> findByRequesterAndIdempotency(@Param("userId") UUID userId,
                                                                         @Param("idempotencyKey") String idempotencyKey);

    @Select("select * from template_change_requests where requested_by_user_id = #{userId}::uuid "
            + "order by created_at desc, id desc limit #{limit} offset #{offset}")
    List<TemplateChangeRequestEntity> listByRequester(@Param("userId") UUID userId,
                                                       @Param("offset") long offset, @Param("limit") int limit);

    @Select("select count(*) from template_change_requests where requested_by_user_id = #{userId}::uuid")
    long countByRequester(@Param("userId") UUID userId);
}
