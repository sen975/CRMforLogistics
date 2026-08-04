package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.crmforlogistics.messagecenter.entity.UserEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Optional;

@Mapper
public interface UserMapper extends BaseMapper<UserEntity> {

    @Select("select id, username, username_normalized, password_hash, display_name, status, last_login_at, created_at, updated_at, deleted_at from users where username = #{username} and deleted_at is null limit 1")
    Optional<UserEntity> findByUsername(@Param("username") String username);

    @Select("select id, username, username_normalized, password_hash, display_name, status, last_login_at, created_at, updated_at, deleted_at from users where username_normalized = #{normalized} and deleted_at is null limit 1")
    Optional<UserEntity> findByUsernameNormalized(@Param("normalized") String normalized);
}
