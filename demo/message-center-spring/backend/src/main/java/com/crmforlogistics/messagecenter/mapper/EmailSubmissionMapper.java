package com.crmforlogistics.messagecenter.mapper;

import com.crmforlogistics.messagecenter.entity.EmailSubmissionEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.UUID;

@Mapper
public interface EmailSubmissionMapper {
    @Insert("insert into email_submissions(id,owner_user_id,channel_account_id,recipient,subject,status," +
            "lease_token,lease_expires_at) values(#{id}::uuid,#{ownerUserId}::uuid,#{channelAccountId}::uuid," +
            "#{recipient},#{subject},#{status},#{leaseToken}::uuid, " +
            "case when #{status} in ('PENDING','SMTP_SENT') then now()+interval '5 minutes' else now() end)")
    int insert(EmailSubmissionEntity entity);

    @Update("update email_submissions set provider_message_id=#{providerMessageId},status=#{status}," +
            "last_error=#{lastError},updated_at=now(), " +
            "lease_expires_at=case when #{status} in ('SENT','UNKNOWN','FAILED') then now() else lease_expires_at end " +
            "where id=#{id}::uuid and lease_token=#{leaseToken}::uuid and lease_expires_at > now() and " +
            "((#{status}='SMTP_SENT' and status='PENDING') or " +
            "(#{status}='SENT' and status='SMTP_SENT') or " +
            "(#{status}='FAILED' and status='PENDING') or " +
            "(#{status}='UNKNOWN' and status in ('PENDING','SMTP_SENT'))) ")
    int update(EmailSubmissionEntity entity);

    @Update("update email_submissions set lease_expires_at=now() + interval '5 minutes' " +
            "where id=#{id}::uuid and lease_token=#{leaseToken}::uuid " +
            "and status in ('PENDING','SMTP_SENT') and lease_expires_at > now()")
    int renewLease(@Param("id") UUID id, @Param("leaseToken") UUID leaseToken);

    @Update("with expired as (select id,lease_token from email_submissions " +
            "where owner_user_id=#{ownerId}::uuid and status in ('PENDING','SMTP_SENT') " +
            "and lease_expires_at <= now() order by lease_expires_at, id limit #{limit} " +
            "for update skip locked) " +
            "update email_submissions s set status='UNKNOWN', " +
            "last_error='Submission interrupted before local finalization',updated_at=now(),lease_expires_at=now() " +
            "from expired e where s.id=e.id and s.lease_token=e.lease_token and s.lease_expires_at <= now()")
    int markStaleSubmissionsUnknown(@Param("ownerId") UUID ownerId,
                                    @Param("limit") int limit);

    @Select("select s.id, s.owner_user_id, s.channel_account_id, s.provider_message_id, s.recipient, s.subject, " +
            "s.status, s.last_error, s.created_at, s.updated_at, s.lease_token, s.lease_expires_at " +
            "from email_submissions s " +
            "where s.owner_user_id=#{ownerId}::uuid " +
            "and s.status='UNKNOWN' order by s.updated_at desc, s.id desc limit #{limit}")
    List<EmailSubmissionEntity> listUnknownByOwner(@Param("ownerId") UUID ownerId,
                                                   @Param("limit") int limit);
}
