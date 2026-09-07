package com.crmforlogistics.messagecenter.service.account;

import com.crmforlogistics.messagecenter.entity.RoleEntity;
import com.crmforlogistics.messagecenter.entity.UserEntity;
import com.crmforlogistics.messagecenter.infrastructure.MinioStorage;
import com.crmforlogistics.messagecenter.mapper.RoleMapper;
import com.crmforlogistics.messagecenter.mapper.UserMapper;
import com.crmforlogistics.messagecenter.mapper.WeComPartyMapper;
import com.crmforlogistics.messagecenter.mapper.WeComUserBindingMapper;
import com.crmforlogistics.messagecenter.service.auth.AuthSessionService;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccountServicePasswordTest {
    private final UserMapper users = mock(UserMapper.class);
    private final RoleMapper roles = mock(RoleMapper.class);
    private final PasswordEncoder passwords = mock(PasswordEncoder.class);
    private final AuthSessionService sessions = mock(AuthSessionService.class);
    private final AccountService service = new AccountService(users, roles, passwords, sessions,
            mock(MinioStorage.class), mock(WeComUserBindingMapper.class), mock(WeComPartyMapper.class));

    @Test
    void changesPasswordRevokesAllSessionsAndIssuesReplacement() {
        UUID userId = UUID.randomUUID();
        UserEntity user = user(userId);
        RoleEntity agent = new RoleEntity();
        agent.setCode("agent");
        when(users.findByIdNotDeleted(userId)).thenReturn(Optional.of(user));
        when(passwords.matches("Oldpass1", "old-hash")).thenReturn(true);
        when(passwords.matches("Newpass2", "old-hash")).thenReturn(false);
        when(passwords.encode("Newpass2")).thenReturn("new-hash");
        when(users.updatePassword(userId, "new-hash")).thenReturn(1);
        when(roles.findByUserId(userId)).thenReturn(List.of(agent));
        when(sessions.issue(userId, "127.0.0.1", "test")).thenReturn("replacement");

        AccountService.SessionResult result = service.changePassword(
                userId, "Oldpass1", "Newpass2", "Newpass2", "127.0.0.1", "test");

        assertThat(result.token()).isEqualTo("replacement");
        verify(sessions).revokeAll(userId);
        verify(sessions).issue(userId, "127.0.0.1", "test");
    }

    @Test
    void rejectsWrongCurrentPasswordWithoutChangingState() {
        UUID userId = UUID.randomUUID();
        when(users.findByIdNotDeleted(userId)).thenReturn(Optional.of(user(userId)));
        when(passwords.matches("wrong", "old-hash")).thenReturn(false);

        assertThatThrownBy(() -> service.changePassword(
                userId, "wrong", "Newpass2", "Newpass2", null, null))
                .isInstanceOf(AccountException.class)
                .hasMessage("CURRENT_PASSWORD_INVALID");
    }

    private static UserEntity user(UUID id) {
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setUsername("agent_01");
        user.setDisplayName("张三");
        user.setPasswordHash("old-hash");
        user.setStatus("active");
        return user;
    }
}
