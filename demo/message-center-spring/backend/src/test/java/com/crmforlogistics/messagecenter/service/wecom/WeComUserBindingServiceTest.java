package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.entity.WeComUserBindingEntity;
import com.crmforlogistics.messagecenter.mapper.RoleMapper;
import com.crmforlogistics.messagecenter.mapper.UserMapper;
import com.crmforlogistics.messagecenter.mapper.WeComUserBindingMapper;
import com.crmforlogistics.messagecenter.entity.RoleEntity;
import com.crmforlogistics.messagecenter.entity.UserEntity;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class WeComUserBindingServiceTest {
    private static final String SUITE = "suite";
    private static final String CORP = "corp";
    private static final String USER = "wecom-user";
    private final WeComUserBindingMapper bindings = mock(WeComUserBindingMapper.class);
    private final UserMapper users = mock(UserMapper.class);
    private final RoleMapper roles = mock(RoleMapper.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final WeComUserBindingService service = new WeComUserBindingService(bindings, users, roles, encoder);

    @Test
    void firstWeComLoginCreatesAgentUserBinding() {
        when(bindings.findByIdentity(SUITE, CORP, USER)).thenReturn(Optional.empty());
        when(bindings.findByUserId(any())).thenReturn(Optional.empty());
        RoleEntity role = new RoleEntity(); role.setId(UUID.randomUUID());
        when(roles.findByCode("agent")).thenReturn(Optional.of(role));
        when(encoder.encode(any())).thenReturn("hash");
        when(users.insert(any(UserEntity.class))).thenReturn(1);
        when(bindings.insert(any(WeComUserBindingEntity.class))).thenReturn(1);
        when(roles.insertUserRole(any(), any())).thenReturn(1);

        var result = service.resolveOrCreate(new WeComUserBindingService.ResolvedIdentity(
                SUITE, CORP, USER, "Member", null));

        assertThat(result.provisioningSource()).isEqualTo("AUTO_CREATED");
        verify(users).insert(argThat((UserEntity u) -> u.getUsername().startsWith("wecom_")
                && u.getUsername().length() == 30 && "hash".equals(u.getPasswordHash())));
        verify(roles).insertUserRole(any(), eq(role.getId()));
        verify(bindings).insert((WeComUserBindingEntity) argThat((WeComUserBindingEntity b) -> USER.equals(b.getWecomUserId())
                && "AUTO_CREATED".equals(b.getProvisioningSource())));
    }

    @Test
    void cannotStealIdentityAlreadyBoundToAnotherUser() {
        UUID other = UUID.randomUUID();
        WeComUserBindingEntity existing = new WeComUserBindingEntity(); existing.setUserId(other);
        when(bindings.findByIdentity(SUITE, CORP, USER)).thenReturn(Optional.of(existing));
        assertThatThrownBy(() -> service.bind(UUID.randomUUID(),
                new WeComUserBindingService.ResolvedIdentity(SUITE, CORP, USER, "Member", null)))
                .isInstanceOf(com.crmforlogistics.messagecenter.channel.wecom.WeComException.class)
                .extracting(e -> ((com.crmforlogistics.messagecenter.channel.wecom.WeComException) e).code())
                .isEqualTo("WECOM_IDENTITY_ALREADY_BOUND");
    }

    @Test
    void autoCreatedUserCannotUnbindOnlyLoginMethod() {
        UUID userId = UUID.randomUUID();
        WeComUserBindingEntity binding = new WeComUserBindingEntity();
        binding.setUserId(userId); binding.setProvisioningSource("AUTO_CREATED");
        when(bindings.findByUserId(userId)).thenReturn(Optional.of(binding));
        assertThatThrownBy(() -> service.unbind(userId))
                .isInstanceOf(com.crmforlogistics.messagecenter.channel.wecom.WeComException.class)
                .extracting(e -> ((com.crmforlogistics.messagecenter.channel.wecom.WeComException) e).code())
                .isEqualTo("WECOM_LAST_LOGIN_METHOD");
    }
}
