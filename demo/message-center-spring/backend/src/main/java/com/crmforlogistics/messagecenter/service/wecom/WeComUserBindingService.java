package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.WeComException;
import com.crmforlogistics.messagecenter.channel.wecom.WeComInstallationService;
import com.crmforlogistics.messagecenter.channel.wecom.WeComUserBindingEntity;
import com.crmforlogistics.messagecenter.config.ConditionalOnWeComEnabled;
import com.crmforlogistics.messagecenter.entity.RoleEntity;
import com.crmforlogistics.messagecenter.entity.UserEntity;
import com.crmforlogistics.messagecenter.mapper.RoleMapper;
import com.crmforlogistics.messagecenter.mapper.UserMapper;
import com.crmforlogistics.messagecenter.mapper.WeComUserBindingMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@Service
@ConditionalOnWeComEnabled
@ConditionalOnExpression("not '${app.wecom-suite-id:}'.isBlank()")
public class WeComUserBindingService {
    private final WeComUserBindingMapper bindingMapper;
    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final PasswordEncoder passwordEncoder;
    private final WeComInstallationService installationService;
    private final SecureRandom secureRandom = new SecureRandom();

    @Autowired
    public WeComUserBindingService(WeComUserBindingMapper bindingMapper,
                                   UserMapper userMapper,
                                   RoleMapper roleMapper,
                                   PasswordEncoder passwordEncoder,
                                   WeComInstallationService installationService) {
        this.bindingMapper = Objects.requireNonNull(bindingMapper);
        this.userMapper = Objects.requireNonNull(userMapper);
        this.roleMapper = Objects.requireNonNull(roleMapper);
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder);
        this.installationService = Objects.requireNonNull(installationService);
    }

    WeComUserBindingService(WeComUserBindingMapper bindingMapper,
                            UserMapper userMapper,
                            RoleMapper roleMapper,
                            PasswordEncoder passwordEncoder) {
        this.bindingMapper = Objects.requireNonNull(bindingMapper);
        this.userMapper = Objects.requireNonNull(userMapper);
        this.roleMapper = Objects.requireNonNull(roleMapper);
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder);
        this.installationService = null;
    }

    @Transactional
    public BoundIdentity resolveOrCreate(ResolvedIdentity identity) {
        validateIdentity(identity);
        var existing = bindingMapper.findByIdentity(identity.suiteId(), identity.authCorpId(), identity.wecomUserId());
        if (existing.isPresent()) {
            WeComUserBindingEntity binding = existing.get();
            UserEntity user = userMapper.findByIdNotDeleted(binding.getUserId())
                    .orElseThrow(() -> new WeComException("WECOM_BOUND_USER_NOT_FOUND", 500,
                            "企业微信绑定的 CRM 用户不存在"));
            Instant now = Instant.now();
            bindingMapper.touchLogin(binding.getId(), now, version(binding));
            return toBoundIdentity(binding, user, identity.installationBinding());
        }

        UUID userId = UUID.randomUUID();
        String username = stableUsername(identity);
        UserEntity user = new UserEntity();
        user.setId(userId);
        user.setUsername(username);
        user.setUsernameNormalized(username.toLowerCase(Locale.ROOT));
        user.setPasswordHash(passwordEncoder.encode(randomPassword()));
        user.setDisplayName(limit(identity.displayName(), identity.wecomUserId(), 200));
        user.setStatus("active");
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        if (userMapper.insert(user) != 1) {
            throw new WeComException("WECOM_USER_PROVISIONING_FAILED", 500, "企业微信用户创建失败");
        }

        RoleEntity role = roleMapper.findByCode("agent")
                .orElseThrow(() -> new WeComException("WECOM_AGENT_ROLE_NOT_FOUND", 500,
                        "系统缺少 agent 角色"));
        if (roleMapper.insertUserRole(userId, role.getId()) != 1) {
            throw new WeComException("WECOM_USER_ROLE_ASSIGNMENT_FAILED", 500,
                    "企业微信用户角色分配失败");
        }

        WeComUserBindingEntity binding = newBinding(userId, identity, "AUTO_CREATED");
        if (bindingMapper.insert(binding) != 1) {
            throw new WeComException("WECOM_IDENTITY_ALREADY_BOUND", 409,
                    "企业微信身份已被其他账号绑定");
        }
        return toBoundIdentity(binding, user, identity.installationBinding());
    }

    @Transactional
    public BoundIdentity bind(UUID currentUserId, ResolvedIdentity identity) {
        if (currentUserId == null) throw new WeComException("UNAUTHORIZED", 401, "当前登录用户不可用");
        validateIdentity(identity);
        var identityBinding = bindingMapper.findByIdentity(identity.suiteId(), identity.authCorpId(), identity.wecomUserId());
        if (identityBinding.isPresent() && !currentUserId.equals(identityBinding.get().getUserId())) {
            throw new WeComException("WECOM_IDENTITY_ALREADY_BOUND", 409,
                    "企业微信身份已被其他账号绑定");
        }
        var currentBinding = bindingMapper.findByUserId(currentUserId);
        if (currentBinding.isPresent() && (identityBinding.isEmpty()
                || !currentBinding.get().getId().equals(identityBinding.get().getId()))) {
            throw new WeComException("WECOM_USER_ALREADY_BOUND", 409, "当前账号已绑定企业微信");
        }
        if (identityBinding.isPresent()) {
            UserEntity user = userMapper.findByIdNotDeleted(currentUserId)
                    .orElseThrow(() -> new WeComException("USER_NOT_FOUND", 404, "当前账号不存在"));
            return toBoundIdentity(identityBinding.get(), user, identity.installationBinding());
        }
        UserEntity user = userMapper.findByIdNotDeleted(currentUserId)
                .orElseThrow(() -> new WeComException("USER_NOT_FOUND", 404, "当前账号不存在"));
        WeComUserBindingEntity binding = newBinding(currentUserId, identity, "BOUND_EXISTING");
        if (bindingMapper.insert(binding) != 1) {
            throw new WeComException("WECOM_IDENTITY_ALREADY_BOUND", 409,
                    "企业微信身份已被其他账号绑定");
        }
        return toBoundIdentity(binding, user, identity.installationBinding());
    }

    @Transactional(readOnly = true)
    public BoundIdentity requireByUserId(UUID userId) {
        WeComUserBindingEntity binding = bindingMapper.findByUserId(userId)
                .orElseThrow(() -> new WeComException("WECOM_USER_NOT_BOUND", 403, "账号尚未绑定企业微信"));
        UserEntity user = userMapper.findByIdNotDeleted(userId)
                .orElseThrow(() -> new WeComException("USER_NOT_FOUND", 404, "当前账号不存在"));
        return toBoundIdentity(binding, user, null);
    }

    @Transactional
    public void unbind(UUID userId) {
        WeComUserBindingEntity binding = bindingMapper.findByUserId(userId)
                .orElseThrow(() -> new WeComException("WECOM_USER_NOT_BOUND", 404, "账号尚未绑定企业微信"));
        if ("AUTO_CREATED".equals(binding.getProvisioningSource())) {
            throw new WeComException("WECOM_LAST_LOGIN_METHOD", 409,
                    "自动创建的账号尚未建立其他登录方式，不能解除唯一绑定");
        }
        if (bindingMapper.deleteById(binding.getId()) != 1) {
            throw new WeComException("WECOM_BINDING_DELETE_FAILED", 500, "企业微信绑定解除失败");
        }
    }

    private BoundIdentity toBoundIdentity(WeComUserBindingEntity binding, UserEntity user,
                                          WeComLoginAttemptService.InstallationBinding providedInstallation) {
        if (!"active".equalsIgnoreCase(user.getStatus())) {
            throw new WeComException("WECOM_BOUND_USER_INACTIVE", 403, "企业微信绑定的 CRM 用户不可登录");
        }
        List<String> roles = roleMapper.findByUserId(user.getId()).stream()
                .map(RoleEntity::getCode).filter(Objects::nonNull).map(String::toUpperCase).distinct().sorted().toList();
        WeComLoginAttemptService.InstallationBinding resolved = providedInstallation;
        if (resolved == null && installationService != null) {
            var installation = installationService.resolveInstallation(binding.getSuiteId(), binding.getAuthCorpId());
            resolved = new WeComLoginAttemptService.InstallationBinding(installation.installationId(),
                    installation.version(), installation.suiteId(), installation.authCorpId(), installation.agentId());
        }
        return new BoundIdentity(user.getId(), binding.getSuiteId(), binding.getAuthCorpId(),
                binding.getWecomUserId(), binding.getProvisioningSource(), resolved, user.getUsername(), roles);
    }

    private static WeComUserBindingEntity newBinding(UUID userId, ResolvedIdentity identity, String source) {
        WeComUserBindingEntity binding = new WeComUserBindingEntity();
        binding.setId(UUID.randomUUID());
        binding.setUserId(userId);
        binding.setSuiteId(identity.suiteId());
        binding.setAuthCorpId(identity.authCorpId());
        binding.setWecomUserId(identity.wecomUserId());
        binding.setProvisioningSource(source);
        binding.setBoundAt(Instant.now());
        binding.setCreatedAt(Instant.now());
        binding.setUpdatedAt(Instant.now());
        binding.setVersion(0L);
        return binding;
    }

    private static long version(WeComUserBindingEntity binding) {
        return binding.getVersion() == null ? 0 : binding.getVersion();
    }

    private String randomPassword() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String stableUsername(ResolvedIdentity identity) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((identity.suiteId() + "\0" + identity.authCorpId()
                    + "\0" + identity.wecomUserId()).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(24);
            for (int i = 0; i < 12; i++) hex.append(String.format("%02x", hash[i]));
            return "wecom_" + hex;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String limit(String displayName, String fallback, int max) {
        String value = displayName == null || displayName.isBlank() ? fallback : displayName.trim();
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static void validateIdentity(ResolvedIdentity identity) {
        if (identity == null || blank(identity.suiteId()) || blank(identity.authCorpId()) || blank(identity.wecomUserId())) {
            throw new WeComException("WECOM_LOGIN_IDENTITY_UNAVAILABLE", 403, "企业微信登录身份不可用");
        }
    }

    private static boolean blank(String value) { return value == null || value.isBlank() || value.length() > 128; }

    public record ResolvedIdentity(String suiteId, String authCorpId, String wecomUserId,
                                   String displayName,
                                   WeComLoginAttemptService.InstallationBinding installationBinding) {}

    public record BoundIdentity(UUID userId, String suiteId, String authCorpId, String wecomUserId,
                                String provisioningSource,
                                WeComLoginAttemptService.InstallationBinding installationBinding,
                                String username, List<String> roles) {
        public BoundIdentity(UUID userId, String suiteId, String authCorpId, String wecomUserId,
                             String provisioningSource,
                             WeComLoginAttemptService.InstallationBinding installationBinding,
                             String username) {
            this(userId, suiteId, authCorpId, wecomUserId, provisioningSource, installationBinding,
                    username, List.of("AGENT"));
        }
    }
}
