package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.dto.request.LoginRequest;
import com.crmforlogistics.messagecenter.dto.response.LoginResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Base64;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationConfiguration authConfig;

    public AuthController(AuthenticationConfiguration authConfig) {
        this.authConfig = authConfig;
    }

    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest request) throws Exception {
        Authentication auth = authConfig.getAuthenticationManager()
                .authenticate(new UsernamePasswordAuthenticationToken(request.username(), request.password()));
        SecurityContextHolder.getContext().setAuthentication(auth);
        String token = Base64.getEncoder().encodeToString((auth.getName() + ":" + UUID.randomUUID()).getBytes());
        return new LoginResponse(token, auth.getName());
    }

    @PostMapping("/logout")
    public void logout() {
        SecurityContextHolder.clearContext();
    }
}
