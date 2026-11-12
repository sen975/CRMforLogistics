package com.crmforlogistics.messagecenter.service.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.crmforlogistics.messagecenter.entity.RoleEntity;
import com.crmforlogistics.messagecenter.entity.UserEntity;
import com.crmforlogistics.messagecenter.mapper.RoleMapper;
import com.crmforlogistics.messagecenter.mapper.UserMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
public class BootstrapService {

    private static final String ENV_ADMIN_USERNAME = "ADMIN_USERNAME";
    private static final String ENV_ADMIN_PASSWORD = "ADMIN_PASSWORD";
    private static final String DEFAULT_USERNAME = "admin";
    private static final String DEFAULT_PASSWORD = "admin";

    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final PasswordEncoder passwordEncoder;

    public BootstrapService(UserMapper userMapper, RoleMapper roleMapper, PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public BootstrapResult bootstrap() {
        String username = env(ENV_ADMIN_USERNAME, DEFAULT_USERNAME);
        String password = env(ENV_ADMIN_PASSWORD, DEFAULT_PASSWORD);
        String normalized = normalizeUsername(username);

        Optional<UserEntity> existing = userMapper.findByUsernameNormalized(normalized);
        if (existing.isPresent()) {
            return new BootstrapResult(false, null, "USER_EXISTS");
        }

        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername(username.trim());
        user.setUsernameNormalized(normalized);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setDisplayName(username.trim());
        user.setStatus("active");
        Instant now = Instant.now();
        user.setCreatedAt(now);
        user.setUpdatedAt(now);

        userMapper.insert(user);

        RoleEntity adminRole = roleMapper.selectOne(
                new LambdaQueryWrapper<RoleEntity>().eq(RoleEntity::getCode, "admin"));
        if (adminRole == null) {
            throw new IllegalStateException("admin role not found in roles table");
        }

        roleMapper.insertUserRole(user.getId(), adminRole.getId());

        return new BootstrapResult(true, user.getId(), "CREATED");
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return (value != null && !value.isBlank()) ? value : defaultValue;
    }

    static String normalizeUsername(String username) {
        return Normalizer.normalize(username, Normalizer.Form.NFKC).trim().toLowerCase(Locale.ROOT);
    }
}
