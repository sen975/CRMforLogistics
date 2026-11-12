package com.crmforlogistics.messagecenter.service.auth;

import com.crmforlogistics.messagecenter.entity.SessionEntity;
import com.crmforlogistics.messagecenter.mapper.SessionMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.authentication.DisabledException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthSessionServiceTest {
    private final SessionMapper sessionMapper = mock(SessionMapper.class);
    private final UserDetailsServiceImpl userDetailsService = mock(UserDetailsServiceImpl.class);
    private final AuthSessionService service = new AuthSessionService(
            sessionMapper, userDetailsService, Duration.ofHours(12));

    @AfterEach
    void clearAuthentication() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @Test
    void issueStoresOnlySha256HashOfOpaqueThirtyTwoByteToken() throws Exception {
        UUID userId = UUID.randomUUID();
        when(sessionMapper.insertSession(any(SessionEntity.class))).thenReturn(1);

        String token = service.issue(userId, "127.0.0.1", "test-agent");

        byte[] rawToken = Base64.getUrlDecoder().decode(token);
        assertThat(rawToken).hasSize(32);
        ArgumentCaptor<SessionEntity> session = ArgumentCaptor.forClass(SessionEntity.class);
        verify(sessionMapper).insertSession(session.capture());
        assertThat(session.getValue().getUserId()).isEqualTo(userId);
        assertThat(session.getValue().getTokenHash())
                .containsExactly(MessageDigest.getInstance("SHA-256")
                        .digest(token.getBytes(StandardCharsets.UTF_8)));
        assertThat(new String(session.getValue().getTokenHash(), StandardCharsets.UTF_8))
                .isNotEqualTo(token);
        assertThat(session.getValue().getExpiresAt())
                .isAfter(session.getValue().getIssuedAt().plus(Duration.ofHours(11)));
    }

    @Test
    void authenticateResolvesOnlyAnActiveStoredSession() {
        UUID userId = UUID.randomUUID();
        SessionEntity session = new SessionEntity();
        session.setId(UUID.randomUUID());
        session.setUserId(userId);
        when(sessionMapper.findActiveByTokenHash(any(byte[].class), any()))
                .thenReturn(Optional.of(session));
        when(userDetailsService.loadUserById(userId))
                .thenReturn(User.withUsername(userId.toString()).password("x").roles("AGENT").build());

        assertThat(service.authenticate("opaque-token"))
                .get()
                .extracting(details -> details.getUsername())
                .isEqualTo(userId.toString());
    }

    @Test
    void revokeHashesThePresentedTokenAndRevokesOnlyThatSession() throws Exception {
        service.revoke("opaque-token");

        ArgumentCaptor<byte[]> hash = ArgumentCaptor.forClass(byte[].class);
        verify(sessionMapper).revokeByTokenHash(hash.capture(), any());
        assertThat(hash.getValue()).containsExactly(MessageDigest.getInstance("SHA-256")
                .digest("opaque-token".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void disabledUserMakesAnOtherwiseActiveSessionUnauthenticated() {
        UUID userId = UUID.randomUUID();
        SessionEntity session = new SessionEntity();
        session.setUserId(userId);
        when(sessionMapper.findActiveByTokenHash(any(byte[].class), any()))
                .thenReturn(Optional.of(session));
        when(userDetailsService.loadUserById(userId))
                .thenThrow(new DisabledException("disabled"));

        assertThat(service.authenticate("opaque-token")).isEmpty();
    }
}
