package com.crmforlogistics.messagecenter.config;

import com.crmforlogistics.messagecenter.dto.response.ApiError;
import com.crmforlogistics.messagecenter.service.auth.AuthSessionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.util.Map;
import java.util.UUID;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, AuthSessionService authSessionService,
                                           ObjectMapper objectMapper) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .cors(Customizer.withDefaults())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(ex -> ex
                    .authenticationEntryPoint((request, response, cause) -> {
                        response.setStatus(HttpStatus.UNAUTHORIZED.value());
                        response.setContentType("application/json");
                        objectMapper.writeValue(response.getOutputStream(), new ApiError(
                                "UNAUTHORIZED", "AUTHENTICATION_REQUIRED", UUID.randomUUID().toString(), Map.of()));
                    })
                    .accessDeniedHandler((request, response, cause) -> {
                        response.setStatus(HttpStatus.FORBIDDEN.value());
                        response.setContentType("application/json");
                        objectMapper.writeValue(response.getOutputStream(), new ApiError(
                                "FORBIDDEN", "ACCESS_DENIED", UUID.randomUUID().toString(), Map.of()));
                    }))
            .addFilterBefore(new TokenAuthenticationFilter(authSessionService),
                    UsernamePasswordAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers("/api/auth/login").permitAll()
                .requestMatchers("/api/events").permitAll()
                .requestMatchers("/api/wecom/callback").permitAll()
                .requestMatchers("/api/v1/webhooks/chatapp").permitAll()
                .requestMatchers("/api/v1/call-records/*/audio").permitAll()
                .requestMatchers("/api/channel-accounts/**").hasRole("ADMIN")
                .requestMatchers("/api/v1/channel-accounts/*/whatsapp/templates/**").hasRole("ADMIN")
                .requestMatchers(
                        "/api/v1/channel-accounts/*/whatsapp/template-media",
                        "/api/v1/channel-accounts/*/whatsapp/template-media/**")
                    .hasRole("ADMIN")
                .requestMatchers("/api/**").authenticated()
                .anyRequest().permitAll()
            );
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }
}
