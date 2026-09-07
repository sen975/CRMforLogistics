package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.crmforlogistics.messagecenter.entity.UserEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Optional;
import java.util.UUID;

@Mapper
public interface UserMapper extends BaseMapper<UserEntity> {

    String USER_COLUMNS = "id, username, username_normalized, password_hash, display_name, "
            + "avatar_object_key, avatar_mime_type, avatar_size_bytes, avatar_updated_at, "
            + "status, last_login_at, created_at, updated_at, deleted_at";

    @Select("select " + USER_COLUMNS + " from users where username = #{username} and deleted_at is null limit 1")
    Optional<UserEntity> findByUsername(@Param("username") String username);

    @Select("select " + USER_COLUMNS + " from users where username_normalized = #{normalized} and deleted_at is null limit 1")
    Optional<UserEntity> findByUsernameNormalized(@Param("normalized") String normalized);

    @Select("select " + USER_COLUMNS + " from users where id = #{id} and deleted_at is null limit 1")
    Optional<UserEntity> findByIdNotDeleted(@Param("id") UUID id);

    @Update("update users set display_name = #{displayName}, updated_at = now() where id = #{id}::uuid and deleted_at is null")
    int updateDisplayName(@Param("id") UUID id, @Param("displayName") String displayName);

    @Update("update users set password_hash = #{passwordHash}, updated_at = now() where id = #{id}::uuid and deleted_at is null")
    int updatePassword(@Param("id") UUID id, @Param("passwordHash") String passwordHash);

    @Update("update users set avatar_object_key = #{objectKey}, avatar_mime_type = #{mimeType}, "
            + "avatar_size_bytes = #{sizeBytes}, avatar_updated_at = #{updatedAt}, updated_at = now() "
            + "where id = #{id}::uuid and deleted_at is null")
    int updateAvatar(@Param("id") UUID id, @Param("objectKey") String objectKey,
                     @Param("mimeType") String mimeType, @Param("sizeBytes") long sizeBytes,
                     @Param("updatedAt") java.time.Instant updatedAt);

    @Update("update users set avatar_object_key = null, avatar_mime_type = null, "
            + "avatar_size_bytes = null, avatar_updated_at = null, updated_at = now() "
            + "where id = #{id}::uuid and deleted_at is null")
    int clearAvatar(@Param("id") UUID id);

    @Select("select " + USER_COLUMNS + " from users where deleted_at is null "
            + "order by created_at desc, id limit #{limit} offset #{offset}")
    java.util.List<UserEntity> listUsers(@Param("offset") long offset, @Param("limit") int limit);

    @Select("select count(*) from users where deleted_at is null")
    long countUsers();
}
