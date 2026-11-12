package com.crmforlogistics.messagecenter.mapper;

import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface WeComCredentialMigrationMapper {

    @Select({
            "<script>",
            "SELECT id, permanent_code AS value FROM wecom_installations",
            "WHERE permanent_code IS NOT NULL",
            "<if test='afterId != null'>",
            "AND id > #{afterId}",
            "</if>",
            "ORDER BY id LIMIT #{limit}",
            "</script>"
    })
    List<WeComCredentialRow> nextInstallations(@Param("afterId") UUID afterId,
                                               @Param("limit") int limit);

    @Select({
            "<script>",
            "SELECT id, secret_key AS value FROM wecom_chatdata_messages",
            "WHERE secret_key IS NOT NULL",
            "<if test='afterId != null'>",
            "AND id > #{afterId}",
            "</if>",
            "ORDER BY id LIMIT #{limit}",
            "</script>"
    })
    List<WeComCredentialRow> nextChatDataMessages(@Param("afterId") UUID afterId,
                                                  @Param("limit") int limit);

    @Update("UPDATE wecom_installations SET permanent_code = #{encryptedValue}, updated_at = now() "
            + "WHERE id = #{id} AND permanent_code = #{expectedValue}")
    int replaceInstallation(@Param("id") UUID id, @Param("expectedValue") String expectedValue,
                            @Param("encryptedValue") String encryptedValue);

    @Update("UPDATE wecom_chatdata_messages SET secret_key = #{encryptedValue} "
            + "WHERE id = #{id} AND secret_key = #{expectedValue}")
    int replaceChatDataMessage(@Param("id") UUID id, @Param("expectedValue") String expectedValue,
                               @Param("encryptedValue") String encryptedValue);

    @Select("SELECT EXISTS (SELECT 1 FROM wecom_credential_migration_markers "
            + "WHERE migration_name = #{migrationName})")
    boolean markerExists(@Param("migrationName") String migrationName);

    @Insert("INSERT INTO wecom_credential_migration_markers (migration_name, completed_at) "
            + "VALUES (#{migrationName}, now())")
    int insertMarker(@Param("migrationName") String migrationName);
}
