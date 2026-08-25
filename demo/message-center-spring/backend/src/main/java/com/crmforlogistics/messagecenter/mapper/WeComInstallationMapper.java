package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.crmforlogistics.messagecenter.channel.wecom.WeComInstallationEntity;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface WeComInstallationMapper extends BaseMapper<WeComInstallationEntity> {

    @Select("SELECT * FROM wecom_installations WHERE auth_status = 'ACTIVE' "
            + "AND deleted_at IS NULL ORDER BY authorized_at DESC NULLS LAST, id LIMIT 2")
    List<WeComInstallationEntity> findActiveForReconciliation();

    @Select("SELECT * FROM wecom_installations "
            + "WHERE suite_id = #{suiteId} AND auth_corp_id = #{authCorpId} LIMIT 1")
    WeComInstallationEntity findBySuiteAndAuthCorpId(@Param("suiteId") String suiteId,
                                                      @Param("authCorpId") String authCorpId);

    @Select("SELECT * FROM wecom_installations WHERE suite_id = #{suiteId} AND auth_corp_id = #{authCorpId} AND auth_status = 'ACTIVE' AND deleted_at IS NULL")
    WeComInstallationEntity findActive(@Param("suiteId") String suiteId,
                                       @Param("authCorpId") String authCorpId);

    @Update("UPDATE wecom_installations SET agent_id = #{agentId}, permanent_code = #{permanentCode}, "
            + "auth_status = #{authStatus}, authorized_at = #{authorizedAt}, "
            + "last_authorization_event_id = #{eventId}, last_authorization_event_at = #{eventAt}, "
            + "version = version + 1, updated_at = now() "
            + "WHERE id = #{id}::uuid AND version = #{expectedVersion} "
            + "AND (last_authorization_event_at IS NULL "
            + "OR last_authorization_event_at < #{eventAt} "
            + "OR (last_authorization_event_at = #{eventAt} AND auth_status <> 'REVOKED'))")
    int updateForEvent(@Param("id") UUID id,
                       @Param("agentId") String agentId,
                       @Param("permanentCode") String permanentCode,
                       @Param("authStatus") String authStatus,
                       @Param("authorizedAt") Instant authorizedAt,
                       @Param("eventId") String eventId,
                       @Param("eventAt") Instant eventAt,
                       @Param("expectedVersion") long expectedVersion);

    @Update("UPDATE wecom_installations SET corp_name = #{corpName}, updated_at = now() "
            + "WHERE id = #{id}::uuid")
    int updateCorpName(@Param("id") UUID id, @Param("corpName") String corpName);

    @Update("UPDATE wecom_installations SET auth_status = #{authStatus}, "
            + "last_authorization_event_id = #{eventId}, last_authorization_event_at = #{eventAt}, "
            + "version = version + 1, updated_at = now() "
            + "WHERE id = #{id}::uuid AND version = #{expectedVersion}")
    int updateStatusForEvent(@Param("id") UUID id,
                             @Param("authStatus") String authStatus,
                             @Param("eventId") String eventId,
                             @Param("eventAt") Instant eventAt,
                             @Param("expectedVersion") long expectedVersion);

    @Update("UPDATE wecom_installations SET agent_id = #{agentId}, "
            + "permanent_code = #{permanentCode}, auth_status = #{authStatus}, "
            + "authorized_at = #{authorizedAt}, deleted_at = NULL, version = version + 1, "
            + "updated_at = now() WHERE id = #{id} AND version = #{expectedVersion}")
    int updateImported(@Param("id") UUID id,
                       @Param("agentId") String agentId,
                       @Param("permanentCode") String permanentCode,
                       @Param("authStatus") String authStatus,
                       @Param("authorizedAt") Instant authorizedAt,
                       @Param("expectedVersion") long expectedVersion);
}
