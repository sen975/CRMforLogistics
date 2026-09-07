package com.crmforlogistics.messagecenter.service.account;

import com.crmforlogistics.messagecenter.entity.RoleEntity;
import com.crmforlogistics.messagecenter.entity.UserEntity;
import com.crmforlogistics.messagecenter.channel.wecom.WeComUserBindingEntity;
import com.crmforlogistics.messagecenter.dto.response.AccountProfileResponse;
import com.crmforlogistics.messagecenter.dto.response.AdminUserPageResponse;
import com.crmforlogistics.messagecenter.dto.response.AdminUserResponse;
import com.crmforlogistics.messagecenter.dto.response.RoleResponse;
import com.crmforlogistics.messagecenter.infrastructure.MinioStorage;
import com.crmforlogistics.messagecenter.mapper.RoleMapper;
import com.crmforlogistics.messagecenter.mapper.UserMapper;
import com.crmforlogistics.messagecenter.mapper.WeComPartyMapper;
import com.crmforlogistics.messagecenter.mapper.WeComUserBindingMapper;
import com.crmforlogistics.messagecenter.service.auth.AuthSessionService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.List;
import java.util.UUID;

@Service
public class AccountService {
    private static final Logger LOG = LoggerFactory.getLogger(AccountService.class);
    private static final long MAX_AVATAR_BYTES = 2L * 1024 * 1024;
    private static final int MAX_AVATAR_DIMENSION = 4096;
    private final UserMapper users;
    private final RoleMapper roles;
    private final PasswordEncoder passwords;
    private final AuthSessionService sessions;
    private final MinioStorage storage;
    private final WeComUserBindingMapper weComBindings;
    private final WeComPartyMapper weComParties;

    public AccountService(UserMapper users, RoleMapper roles, PasswordEncoder passwords,
                          AuthSessionService sessions, MinioStorage storage,
                          WeComUserBindingMapper weComBindings, WeComPartyMapper weComParties) {
        this.users = users;
        this.roles = roles;
        this.passwords = passwords;
        this.sessions = sessions;
        this.storage = storage;
        this.weComBindings = weComBindings;
        this.weComParties = weComParties;
    }

    @Transactional
    public SessionResult register(String rawUsername, String rawDisplayName, String password,
                                  String ipAddress, String userAgent) {
        AccountCredentials.Username username = AccountCredentials.username(rawUsername);
        AccountCredentials.validatePassword(password);
        String displayName = AccountCredentials.displayName(rawDisplayName, username.value());
        if (users.findByUsernameNormalized(username.normalized()).isPresent()) {
            throw new AccountException("USERNAME_ALREADY_EXISTS", HttpStatus.CONFLICT);
        }
        RoleEntity agent = roles.findByCode("agent")
                .orElseThrow(() -> new IllegalStateException("ACCOUNT_DEFAULT_ROLE_MISSING"));

        UserEntity user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername(username.value());
        user.setUsernameNormalized(username.normalized());
        user.setPasswordHash(passwords.encode(password));
        user.setDisplayName(displayName);
        user.setStatus("active");
        Instant now = Instant.now();
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        try {
            if (users.insert(user) != 1 || roles.insertUserRole(user.getId(), agent.getId()) != 1) {
                throw new IllegalStateException("ACCOUNT_CREATE_FAILED");
            }
        } catch (DataIntegrityViolationException e) {
            throw new AccountException("USERNAME_ALREADY_EXISTS", HttpStatus.CONFLICT, e);
        }
        String token = sessions.issue(user.getId(), ipAddress, userAgent);
        return new SessionResult(token, user.getUsername(), List.of("AGENT"));
    }

    @Transactional
    public SessionResult changePassword(UUID userId, String currentPassword, String newPassword,
                                        String confirmation, String ipAddress, String userAgent) {
        UserEntity user = requireUser(userId);
        if (!passwords.matches(currentPassword == null ? "" : currentPassword, user.getPasswordHash())) {
            throw new AccountException("CURRENT_PASSWORD_INVALID", HttpStatus.BAD_REQUEST);
        }
        if (!java.util.Objects.equals(newPassword, confirmation)) {
            throw new AccountException("ACCOUNT_VALIDATION_FAILED", HttpStatus.BAD_REQUEST);
        }
        AccountCredentials.validatePassword(newPassword);
        if (passwords.matches(newPassword, user.getPasswordHash())) {
            throw new AccountException("PASSWORD_REUSE_NOT_ALLOWED", HttpStatus.BAD_REQUEST);
        }
        String encoded = passwords.encode(newPassword);
        if (users.updatePassword(userId, encoded) != 1) {
            throw new AccountException("USER_NOT_FOUND", HttpStatus.NOT_FOUND);
        }
        sessions.revokeAll(userId);
        String token = sessions.issue(userId, ipAddress, userAgent);
        List<String> roleCodes = roles.findByUserId(userId).stream()
                .map(RoleEntity::getCode).map(String::toUpperCase).distinct().sorted().toList();
        return new SessionResult(token, user.getUsername(), roleCodes);
    }

    @Transactional(readOnly = true)
    public AccountProfileResponse profile(UUID userId) {
        UserEntity user = requireUser(userId);
        List<String> roleCodes = roles.findByUserId(userId).stream()
                .map(RoleEntity::getCode).map(String::toUpperCase).distinct().sorted().toList();
        String initial = firstCharacter(AccountCredentials.displayName(user.getDisplayName(), user.getUsername()));

        String weComAvatar = weComBindings.findByUserId(userId)
                .flatMap(this::storedWeComAvatar)
                .orElse(null);
        AccountProfileResponse.Avatar avatar;
        if (weComAvatar != null) {
            avatar = new AccountProfileResponse.Avatar("WECOM", weComAvatar, initial,
                    Integer.toHexString(weComAvatar.hashCode()));
        } else if (user.getAvatarObjectKey() != null && !user.getAvatarObjectKey().isBlank()) {
            String revision = user.getAvatarUpdatedAt() == null ? null
                    : Long.toString(user.getAvatarUpdatedAt().toEpochMilli());
            avatar = new AccountProfileResponse.Avatar(
                    "UPLOAD", "/api/account/avatar/content", initial, revision);
        } else {
            avatar = new AccountProfileResponse.Avatar("INITIAL", null, initial, null);
        }
        return new AccountProfileResponse(user.getId(), user.getUsername(), user.getDisplayName(), roleCodes, avatar);
    }

    @Transactional
    public AccountProfileResponse updateProfile(UUID userId, String rawDisplayName) {
        UserEntity user = requireUser(userId);
        String displayName = AccountCredentials.displayName(rawDisplayName, user.getUsername());
        if (users.updateDisplayName(userId, displayName) != 1) {
            throw new AccountException("USER_NOT_FOUND", HttpStatus.NOT_FOUND);
        }
        return profile(userId);
    }

    public void uploadAvatar(UUID userId, MultipartFile file) {
        UserEntity user = requireUser(userId);
        if (file == null || file.isEmpty() || file.getSize() > MAX_AVATAR_BYTES) {
            throw avatarInvalid(null);
        }
        byte[] data;
        try {
            data = file == null ? new byte[0] : file.getBytes();
        } catch (Exception e) {
            throw avatarInvalid(e);
        }
        String mimeType = validateAvatar(data, file == null ? null : file.getContentType());
        String objectKey = "user-avatars/" + userId + "/" + UUID.randomUUID();
        try {
            storage.store(objectKey, data, mimeType);
            if (users.updateAvatar(userId, objectKey, mimeType, data.length, Instant.now()) != 1) {
                storage.remove(objectKey);
                throw new AccountException("USER_NOT_FOUND", HttpStatus.NOT_FOUND);
            }
        } catch (AccountException e) {
            throw e;
        } catch (Exception e) {
            try {
                storage.remove(objectKey);
            } catch (Exception ignored) {
                // The original storage/database error remains the actionable failure.
            }
            throw new AccountException("AVATAR_STORAGE_FAILED", HttpStatus.SERVICE_UNAVAILABLE, e);
        }
        if (user.getAvatarObjectKey() != null && !user.getAvatarObjectKey().equals(objectKey)) {
            try {
                storage.remove(user.getAvatarObjectKey());
            } catch (Exception cleanupFailure) {
                LOG.warn("event=account.avatar_stale_object_cleanup_failed userId={} errorType={}",
                        userId, cleanupFailure.getClass().getSimpleName());
            }
        }
    }

    @Transactional
    public void deleteAvatar(UUID userId) {
        UserEntity user = requireUser(userId);
        if (user.getAvatarObjectKey() == null || user.getAvatarObjectKey().isBlank()) return;
        if (users.clearAvatar(userId) != 1) {
            throw new AccountException("USER_NOT_FOUND", HttpStatus.NOT_FOUND);
        }
        try {
            storage.remove(user.getAvatarObjectKey());
        } catch (Exception cleanupFailure) {
            LOG.warn("event=account.avatar_object_delete_failed userId={} errorType={}",
                    userId, cleanupFailure.getClass().getSimpleName());
        }
    }

    @Transactional(readOnly = true)
    public AvatarContent avatarContent(UUID userId) {
        UserEntity user = requireUser(userId);
        if (user.getAvatarObjectKey() == null || user.getAvatarObjectKey().isBlank()) {
            throw new AccountException("AVATAR_NOT_FOUND", HttpStatus.NOT_FOUND);
        }
        try (InputStream input = storage.get(user.getAvatarObjectKey())) {
            byte[] data = input.readNBytes(Math.toIntExact(MAX_AVATAR_BYTES + 1));
            if (data.length > MAX_AVATAR_BYTES) throw avatarInvalid(null);
            return new AvatarContent(data, user.getAvatarMimeType());
        } catch (AccountException e) {
            throw e;
        } catch (Exception e) {
            throw new AccountException("AVATAR_STORAGE_FAILED", HttpStatus.SERVICE_UNAVAILABLE, e);
        }
    }

    @Transactional(readOnly = true)
    public AdminUserPageResponse listUsers(UUID actorId, int page, int size) {
        requireAdmin(actorId);
        if (page < 0 || size < 1 || size > 100) {
            throw new AccountException("ACCOUNT_VALIDATION_FAILED", HttpStatus.BAD_REQUEST);
        }
        List<AdminUserResponse> items = users.listUsers((long) page * size, size).stream()
                .map(user -> new AdminUserResponse(user.getId(), user.getUsername(), user.getDisplayName(),
                        user.getStatus(), roleCodes(user.getId()), user.getCreatedAt()))
                .toList();
        return new AdminUserPageResponse(items, users.countUsers(), page, size);
    }

    @Transactional(readOnly = true)
    public List<RoleResponse> listRoles(UUID actorId) {
        requireAdmin(actorId);
        return roles.findAllRoles().stream()
                .map(role -> new RoleResponse(role.getCode().toUpperCase(), role.getDisplayName()))
                .toList();
    }

    @Transactional
    public AdminUserResponse replaceRoles(UUID actorId, UUID targetId, Set<String> requestedCodes) {
        requireAdmin(actorId);
        UserEntity target = requireUser(targetId);
        if (requestedCodes == null || requestedCodes.isEmpty()) {
            throw new AccountException("ROLE_INVALID", HttpStatus.BAD_REQUEST);
        }
        Set<String> normalized = requestedCodes.stream()
                .map(code -> code == null ? "" : code.trim().toLowerCase())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (normalized.contains("")) {
            throw new AccountException("ROLE_INVALID", HttpStatus.BAD_REQUEST);
        }
        List<RoleEntity> replacements = normalized.stream()
                .map(code -> roles.findByCode(code)
                        .orElseThrow(() -> new AccountException("ROLE_INVALID", HttpStatus.BAD_REQUEST)))
                .toList();

        roles.lockRoleByCode("admin");
        boolean targetWasAdmin = roles.findByUserId(targetId).stream()
                .anyMatch(role -> "admin".equals(role.getCode()));
        if (targetWasAdmin && !normalized.contains("admin") && roles.countActiveAdmins() <= 1) {
            throw new AccountException("LAST_ADMIN_REQUIRED", HttpStatus.CONFLICT);
        }
        roles.deleteUserRoles(targetId);
        replacements.forEach(role -> {
            if (roles.insertUserRole(targetId, role.getId()) != 1) {
                throw new IllegalStateException("ACCOUNT_ROLE_REPLACE_FAILED");
            }
        });
        return new AdminUserResponse(target.getId(), target.getUsername(), target.getDisplayName(),
                target.getStatus(), normalized.stream().map(String::toUpperCase).sorted().toList(),
                target.getCreatedAt());
    }

    @Transactional
    public void adminResetPassword(UUID actorId, UUID targetId, String newPassword, String confirmation) {
        requireAdmin(actorId);
        requireUser(targetId);
        if (!java.util.Objects.equals(newPassword, confirmation)) {
            throw new AccountException("ACCOUNT_VALIDATION_FAILED", HttpStatus.BAD_REQUEST);
        }
        AccountCredentials.validatePassword(newPassword);
        if (users.updatePassword(targetId, passwords.encode(newPassword)) != 1) {
            throw new AccountException("USER_NOT_FOUND", HttpStatus.NOT_FOUND);
        }
        sessions.revokeAll(targetId);
    }

    private void requireAdmin(UUID actorId) {
        if (!roles.userHasRole(actorId, "admin")) {
            throw new AccountException("ACCOUNT_ADMIN_REQUIRED", HttpStatus.FORBIDDEN);
        }
    }

    private List<String> roleCodes(UUID userId) {
        return roles.findByUserId(userId).stream().map(RoleEntity::getCode)
                .map(String::toUpperCase).distinct().sorted().toList();
    }

    private java.util.Optional<String> storedWeComAvatar(WeComUserBindingEntity binding) {
        if (binding.getSuiteId() == null || binding.getAuthCorpId() == null || binding.getWecomUserId() == null) {
            return java.util.Optional.empty();
        }
        return weComParties.findEmployeeAvatarUrl(
                binding.getSuiteId(), binding.getAuthCorpId(), binding.getWecomUserId());
    }

    private static String validateAvatar(byte[] data, String declaredMimeType) {
        if (data.length == 0 || data.length > MAX_AVATAR_BYTES) throw avatarInvalid(null);
        String detected;
        if (data.length >= 8 && data[0] == (byte) 0x89 && data[1] == 0x50 && data[2] == 0x4e
                && data[3] == 0x47 && data[4] == 0x0d && data[5] == 0x0a
                && data[6] == 0x1a && data[7] == 0x0a) {
            detected = "image/png";
        } else if (data.length >= 3 && data[0] == (byte) 0xff && data[1] == (byte) 0xd8
                && data[2] == (byte) 0xff) {
            detected = "image/jpeg";
        } else {
            throw avatarInvalid(null);
        }
        if (!detected.equalsIgnoreCase(declaredMimeType == null ? "" : declaredMimeType.trim())) {
            throw avatarInvalid(null);
        }
        try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw avatarInvalid(null);
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > MAX_AVATAR_DIMENSION || height > MAX_AVATAR_DIMENSION) {
                    throw avatarInvalid(null);
                }
                if (reader.read(0) == null) throw avatarInvalid(null);
            } finally {
                reader.dispose();
            }
        } catch (AccountException e) {
            throw e;
        } catch (Exception e) {
            throw avatarInvalid(e);
        }
        return detected;
    }

    private static AccountException avatarInvalid(Throwable cause) {
        return new AccountException("AVATAR_INVALID", HttpStatus.BAD_REQUEST, cause);
    }

    private static String firstCharacter(String value) {
        String trimmed = value.trim();
        int end = trimmed.offsetByCodePoints(0, 1);
        return trimmed.substring(0, end);
    }

    private UserEntity requireUser(UUID userId) {
        return users.findByIdNotDeleted(userId)
                .orElseThrow(() -> new AccountException("USER_NOT_FOUND", HttpStatus.NOT_FOUND));
    }

    public record SessionResult(String token, String username, List<String> roles) {}
    public record AvatarContent(byte[] data, String mimeType) {}
}
