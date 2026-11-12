package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.crmforlogistics.messagecenter.entity.RoleEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Mapper
public interface RoleMapper extends BaseMapper<RoleEntity> {

    @Select("SELECT r.* FROM roles r JOIN user_roles ur ON r.id = ur.role_id WHERE ur.user_id = #{userId}")
    List<RoleEntity> findByUserId(@Param("userId") UUID userId);

    @Select("SELECT * FROM roles WHERE code = #{code}")
    Optional<RoleEntity> findByCode(@Param("code") String code);

    @Insert("INSERT INTO user_roles (user_id, role_id) VALUES (#{userId}, #{roleId})")
    int insertUserRole(@Param("userId") UUID userId, @Param("roleId") UUID roleId);

    @Select("SELECT * FROM roles ORDER BY code")
    List<RoleEntity> findAllRoles();

    @Delete("DELETE FROM user_roles WHERE user_id = #{userId}::uuid")
    int deleteUserRoles(@Param("userId") UUID userId);

    @Select("SELECT EXISTS (SELECT 1 FROM user_roles ur JOIN roles r ON r.id = ur.role_id "
            + "WHERE ur.user_id = #{userId}::uuid AND r.code = #{roleCode})")
    boolean userHasRole(@Param("userId") UUID userId, @Param("roleCode") String roleCode);

    @Select("SELECT count(DISTINCT u.id) FROM users u JOIN user_roles ur ON ur.user_id = u.id "
            + "JOIN roles r ON r.id = ur.role_id WHERE r.code = 'admin' AND u.status = 'active' "
            + "AND u.deleted_at IS NULL")
    long countActiveAdmins();

    @Select("SELECT * FROM roles WHERE code = #{code} FOR UPDATE")
    RoleEntity lockRoleByCode(@Param("code") String code);
}
