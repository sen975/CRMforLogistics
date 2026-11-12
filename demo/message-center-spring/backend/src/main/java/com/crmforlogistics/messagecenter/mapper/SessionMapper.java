package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.crmforlogistics.messagecenter.entity.SessionEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Mapper
public interface SessionMapper extends BaseMapper<SessionEntity> {
    @Insert("""
            insert into user_sessions (id, user_id, token_hash, issued_at, expires_at,
                                       last_seen_at, ip_address, user_agent)
            values (#{session.id}, #{session.userId}, #{session.tokenHash},
                    #{session.issuedAt}, #{session.expiresAt}, #{session.lastSeenAt},
                    cast(#{session.ipAddress} as inet), #{session.userAgent})
            """)
    int insertSession(@Param("session") SessionEntity session);

    @Select("""
            select id, user_id, token_hash, issued_at, expires_at, last_seen_at,
                   revoked_at, ip_address, user_agent
            from user_sessions
            where token_hash = #{tokenHash}
              and revoked_at is null
              and expires_at > #{now}
            limit 1
            """)
    Optional<SessionEntity> findActiveByTokenHash(@Param("tokenHash") byte[] tokenHash,
                                                   @Param("now") Instant now);

    @Update("""
            update user_sessions
            set revoked_at = #{revokedAt}
            where token_hash = #{tokenHash}
              and revoked_at is null
            """)
    int revokeByTokenHash(@Param("tokenHash") byte[] tokenHash,
                          @Param("revokedAt") Instant revokedAt);

    @Update("update user_sessions set revoked_at = #{revokedAt} where user_id = #{userId}::uuid and revoked_at is null")
    int revokeActiveByUserId(@Param("userId") UUID userId,
                             @Param("revokedAt") Instant revokedAt);
}
