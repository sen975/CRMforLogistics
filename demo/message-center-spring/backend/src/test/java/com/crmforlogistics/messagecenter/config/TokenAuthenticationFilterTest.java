package com.crmforlogistics.messagecenter.config;

import com.crmforlogistics.messagecenter.service.auth.AuthSessionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TokenAuthenticationFilterTest {
    private final AuthSessionService sessions = mock(AuthSessionService.class);
    private final TokenAuthenticationFilter filter = new TokenAuthenticationFilter(sessions);

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void validOpaqueBearerTokenPopulatesSecurityContext() throws Exception {
        var user = User.withUsername("c9c9797b-88a1-4650-85a7-1fe61ead6bc8")
                .password("x").roles("AGENT").build();
        when(sessions.authenticate("opaque-token")).thenReturn(Optional.of(user));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer opaque-token");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        verify(sessions).authenticate("opaque-token");
        assertThat(SecurityContextHolder.getContext().getAuthentication().getName())
                .isEqualTo(user.getUsername());
    }

    @Test
    void unknownTokenDoesNotAuthenticateRequest() throws Exception {
        when(sessions.authenticate("unknown-token")).thenReturn(Optional.empty());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer unknown-token");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
