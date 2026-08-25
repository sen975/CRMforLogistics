package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.crmforlogistics.messagecenter.channel.wecom.WeComUserBindingEntity;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface WeComUserBindingMapper extends BaseMapper<WeComUserBindingEntity> {

    @Select("SELECT * FROM wecom_user_bindings WHERE suite_id = #{suiteId} "
            + "AND auth_corp_id = #{authCorpId} ORDER BY last_login_at DESC NULLS LAST, updated_at DESC LIMIT 1")
    Optional<WeComUserBindingEntity> findLatestByInstallation(@Param("suiteId") String suiteId,
                                                                @Param("authCorpId") String authCorpId);

    @Select("SELECT * FROM wecom_user_bindings WHERE suite_id = #{suiteId} "
            + "AND auth_corp_id = #{authCorpId} AND wecom_user_id = #{wecomUserId}")
    Optional<WeComUserBindingEntity> findByIdentity(@Param("suiteId") String suiteId,
                                                    @Param("authCorpId") String authCorpId,
                                                    @Param("wecomUserId") String wecomUserId);

    @Select("SELECT * FROM wecom_user_bindings WHERE user_id = #{userId}::uuid")
    Optional<WeComUserBindingEntity> findByUserId(@Param("userId") UUID userId);

    @Override
    int insert(WeComUserBindingEntity entity);

    @Update("UPDATE wecom_user_bindings SET last_login_at = #{loggedInAt}, updated_at = now(), "
            + "version = version + 1 WHERE id = #{id}::uuid AND version = #{expectedVersion}")
    int touchLogin(@Param("id") UUID id,
                   @Param("loggedInAt") Instant loggedInAt,
                   @Param("expectedVersion") long expectedVersion);
}
