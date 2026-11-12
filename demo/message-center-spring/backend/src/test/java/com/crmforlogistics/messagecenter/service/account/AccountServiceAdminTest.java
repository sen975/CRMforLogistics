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
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccountServiceAdminTest {
    private final UserMapper users = mock(UserMapper.class);
    private final RoleMapper roles = mock(RoleMapper.class);
    private final PasswordEncoder passwords = mock(PasswordEncoder.class);
    private final AuthSessionService sessions = mock(AuthSessionService.class);
    private final AccountService service = new AccountService(users, roles, passwords, sessions,
            mock(MinioStorage.class), mock(WeComUserBindingMapper.class), mock(WeComPartyMapper.class));

    @Test
    void preventsRemovingTheLastActiveAdministrator() {
        UUID actorId = UUID.randomUUID();
        when(roles.userHasRole(actorId, "admin")).thenReturn(true);
        when(users.findByIdNotDeleted(actorId)).thenReturn(Optional.of(user(actorId)));
        when(roles.findByUserId(actorId)).thenReturn(List.of(role("admin")));
        when(roles.findByCode("agent")).thenReturn(Optional.of(role("agent")));
        when(roles.lockRoleByCode("admin")).thenReturn(role("admin"));
        when(roles.countActiveAdmins()).thenReturn(1L);

        assertThatThrownBy(() -> service.replaceRoles(actorId, actorId, Set.of("agent")))
                .isInstanceOf(AccountException.class)
                .hasMessage("LAST_ADMIN_REQUIRED");

        verify(roles, never()).deleteUserRoles(actorId);
    }

    @Test
    void resetsPasswordAndRevokesEveryTargetSession() {
        UUID actorId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        when(roles.userHasRole(actorId, "admin")).thenReturn(true);
        when(users.findByIdNotDeleted(targetId)).thenReturn(Optional.of(user(targetId)));
        when(passwords.encode("Resetpass1")).thenReturn("new-hash");
        when(users.updatePassword(targetId, "new-hash")).thenReturn(1);

        service.adminResetPassword(actorId, targetId, "Resetpass1", "Resetpass1");

        verify(sessions).revokeAll(targetId);
    }

    private static UserEntity user(UUID id) {
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setUsername("user");
        user.setDisplayName("User");
        user.setStatus("active");
        return user;
    }

    private static RoleEntity role(String code) {
        RoleEntity role = new RoleEntity();
        role.setId(UUID.randomUUID());
        role.setCode(code);
        return role;
    }
}
