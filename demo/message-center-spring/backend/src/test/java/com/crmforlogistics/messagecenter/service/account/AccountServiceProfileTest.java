package com.crmforlogistics.messagecenter.service.account;

import com.crmforlogistics.messagecenter.entity.WeComUserBindingEntity;
import com.crmforlogistics.messagecenter.dto.response.AccountProfileResponse;
import com.crmforlogistics.messagecenter.entity.RoleEntity;
import com.crmforlogistics.messagecenter.entity.UserEntity;
import com.crmforlogistics.messagecenter.infrastructure.MinioStorage;
import com.crmforlogistics.messagecenter.mapper.RoleMapper;
import com.crmforlogistics.messagecenter.mapper.UserMapper;
import com.crmforlogistics.messagecenter.mapper.WeComPartyMapper;
import com.crmforlogistics.messagecenter.mapper.WeComUserBindingMapper;
import com.crmforlogistics.messagecenter.service.auth.AuthSessionService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccountServiceProfileTest {
    private final UserMapper users = mock(UserMapper.class);
    private final RoleMapper roles = mock(RoleMapper.class);
    private final MinioStorage storage = mock(MinioStorage.class);
    private final WeComUserBindingMapper bindings = mock(WeComUserBindingMapper.class);
    private final WeComPartyMapper parties = mock(WeComPartyMapper.class);
    private final AccountService service = new AccountService(users, roles, mock(PasswordEncoder.class),
            mock(AuthSessionService.class), storage, bindings, parties);

    @Test
    void projectsStoredWeComAvatarBeforeUploadedAvatar() {
        UUID userId = UUID.randomUUID();
        UserEntity user = user(userId, "张三");
        user.setAvatarObjectKey("user-avatars/" + userId + "/old");
        user.setAvatarUpdatedAt(Instant.parse("2026-09-03T01:00:00Z"));
        WeComUserBindingEntity binding = new WeComUserBindingEntity();
        binding.setSuiteId("suite");
        binding.setAuthCorpId("corp");
        binding.setWecomUserId("user");
        when(users.findByIdNotDeleted(userId)).thenReturn(Optional.of(user));
        when(bindings.findByUserId(userId)).thenReturn(Optional.of(binding));
        when(parties.findEmployeeAvatarUrl("suite", "corp", "user"))
                .thenReturn(Optional.of("https://wecom.example/avatar.png"));
        when(roles.findByUserId(userId)).thenReturn(List.of(role("agent")));

        AccountProfileResponse result = service.profile(userId);

        assertThat(result.avatar().source()).isEqualTo("WECOM");
        assertThat(result.avatar().contentUrl()).isEqualTo("https://wecom.example/avatar.png");
        assertThat(result.avatar().initial()).isEqualTo("张");
    }

    @Test
    void fallsBackToInitialWhenNoUsableAvatarExists() {
        UUID userId = UUID.randomUUID();
        when(users.findByIdNotDeleted(userId)).thenReturn(Optional.of(user(userId, " Pazuma ")));
        when(bindings.findByUserId(userId)).thenReturn(Optional.empty());
        when(roles.findByUserId(userId)).thenReturn(List.of(role("agent")));

        AccountProfileResponse result = service.profile(userId);

        assertThat(result.avatar().source()).isEqualTo("INITIAL");
        assertThat(result.avatar().contentUrl()).isNull();
        assertThat(result.avatar().initial()).isEqualTo("P");
    }

    @Test
    void uploadsDecodedPngUsingPrivateObjectKey() throws Exception {
        UUID userId = UUID.randomUUID();
        byte[] png = Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
        when(users.findByIdNotDeleted(userId)).thenReturn(Optional.of(user(userId, "张三")));
        when(storage.store(any(String.class), any(byte[].class), any(String.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(users.updateAvatar(any(), any(), any(), any(Long.class), any())).thenReturn(1);

        service.uploadAvatar(userId, new MockMultipartFile("file", "avatar.png", "image/png", png));

        verify(storage).store(org.mockito.ArgumentMatchers.startsWith("user-avatars/" + userId + "/"),
                org.mockito.ArgumentMatchers.eq(png), org.mockito.ArgumentMatchers.eq("image/png"));
    }

    @Test
    void rejectsDeclaredImageThatCannotBeDecoded() {
        UUID userId = UUID.randomUUID();
        when(users.findByIdNotDeleted(userId)).thenReturn(Optional.of(user(userId, "张三")));

        assertThatThrownBy(() -> service.uploadAvatar(userId,
                new MockMultipartFile("file", "avatar.png", "image/png", new byte[]{1, 2, 3})))
                .isInstanceOf(AccountException.class)
                .hasMessage("AVATAR_INVALID");
    }

    private static UserEntity user(UUID id, String displayName) {
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setUsername("agent_01");
        user.setDisplayName(displayName);
        user.setStatus("active");
        return user;
    }

    private static RoleEntity role(String code) {
        RoleEntity role = new RoleEntity();
        role.setCode(code);
        return role;
    }
}
