package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.crmforlogistics.messagecenter.entity.UserEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Optional;

@Mapper
public interface UserMapper extends BaseMapper<UserEntity> {

    Optional<UserEntity> findByUsername(@Param("username") String username);

    Optional<UserEntity> findByUsernameNormalized(@Param("normalized") String normalized);
}
