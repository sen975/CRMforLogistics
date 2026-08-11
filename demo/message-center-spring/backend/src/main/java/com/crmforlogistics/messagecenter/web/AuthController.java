package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.dto.request.LoginRequest;
import com.crmforlogistics.messagecenter.dto.response.LoginResponse;
import com.crmforlogistics.messagecenter.service.auth.AuthSessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.UUID;
import java.util.List;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationConfiguration authConfig;
    private final AuthSessionService authSessionService;

    public AuthController(AuthenticationConfiguration authConfig,
                          AuthSessionService authSessionService) {
        this.authConfig = authConfig;
        this.authSessionService = authSessionService;
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request,
                               HttpServletRequest servletRequest) throws Exception {
        Authentication auth = authConfig.getAuthenticationManager()
                .authenticate(new UsernamePasswordAuthenticationToken(request.username(), request.password()));
        SecurityContextHolder.getContext().setAuthentication(auth);
        String token = authSessionService.issue(UUID.fromString(auth.getName()),
                servletRequest.getRemoteAddr(), servletRequest.getHeader("User-Agent"));
        List<String> roles = auth.getAuthorities().stream()
                .map(authority -> authority.getAuthority().replaceFirst("^ROLE_", ""))
                .distinct()
                .sorted()
                .toList();
        return new LoginResponse(token, request.username(), roles);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            authSessionService.revoke(header.substring(7));
        }
        SecurityContextHolder.clearContext();
    }
}
