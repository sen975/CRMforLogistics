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

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccountServiceRegistrationTest {
    private final UserMapper users = mock(UserMapper.class);
    private final RoleMapper roles = mock(RoleMapper.class);
    private final PasswordEncoder passwords = mock(PasswordEncoder.class);
    private final AuthSessionService sessions = mock(AuthSessionService.class);
    private final AccountService service = new AccountService(users, roles, passwords, sessions,
            mock(MinioStorage.class), mock(WeComUserBindingMapper.class), mock(WeComPartyMapper.class));

    @Test
    void registersNormalizedUserWithOnlyAgentRoleAndSession() {
        RoleEntity agent = new RoleEntity();
        agent.setId(UUID.randomUUID());
        agent.setCode("agent");
        when(users.findByUsernameNormalized("agent_01")).thenReturn(Optional.empty());
        when(roles.findByCode("agent")).thenReturn(Optional.of(agent));
        when(users.insert(any(UserEntity.class))).thenReturn(1);
        when(roles.insertUserRole(any(), any())).thenReturn(1);
        when(passwords.encode("Example123")).thenReturn("hash");
        when(sessions.issue(any(), any(), any())).thenReturn("token");

        AccountService.SessionResult result = service.register(
                " Agent_01 ", " 张三 ", "Example123", "127.0.0.1", "test");

        assertThat(result.token()).isEqualTo("token");
        assertThat(result.username()).isEqualTo("Agent_01");
        assertThat(result.roles()).containsExactly("AGENT");
        verify(users).insert(org.mockito.ArgumentMatchers.<UserEntity>argThat(user ->
                user.getUsernameNormalized().equals("agent_01")
                        && user.getDisplayName().equals("张三")
                        && user.getPasswordHash().equals("hash")));
        verify(roles).insertUserRole(any(), org.mockito.ArgumentMatchers.eq(agent.getId()));
    }
}
