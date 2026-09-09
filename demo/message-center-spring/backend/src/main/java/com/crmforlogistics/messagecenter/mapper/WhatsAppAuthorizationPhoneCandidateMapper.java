package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.crmforlogistics.messagecenter.entity.WhatsAppAuthorizationPhoneCandidateEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.UUID;

@Mapper
public interface WhatsAppAuthorizationPhoneCandidateMapper
        extends BaseMapper<WhatsAppAuthorizationPhoneCandidateEntity> {

    @Insert("insert into whatsapp_authorization_phone_candidates "
            + "(id, attempt_id, token_hash, phone_number, masked_phone, expires_at) values "
            + "(#{id}::uuid, #{attemptId}::uuid, #{tokenHash}, #{phoneNumber}, #{maskedPhone}, #{expiresAt})")
    int insertCandidate(WhatsAppAuthorizationPhoneCandidateEntity entity);

    @Select("select id, attempt_id, token_hash, phone_number, masked_phone, expires_at, created_at "
            + "from whatsapp_authorization_phone_candidates where attempt_id = #{attemptId}::uuid "
            + "and token_hash = #{tokenHash} and expires_at > #{now} limit 1")
    WhatsAppAuthorizationPhoneCandidateEntity findValid(@Param("attemptId") UUID attemptId,
                                                         @Param("tokenHash") String tokenHash,
                                                         @Param("now") Instant now);

    @Delete("delete from whatsapp_authorization_phone_candidates where attempt_id = #{attemptId}::uuid")
    int deleteByAttempt(@Param("attemptId") UUID attemptId);
}
