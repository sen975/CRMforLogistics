package com.crmforlogistics.messagecenter.service.auth;

import com.crmforlogistics.messagecenter.entity.SessionEntity;
import com.crmforlogistics.messagecenter.mapper.SessionMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class AuthSessionService {
    private static final int TOKEN_BYTES = 32;
    private static final int MAX_PRESENTED_TOKEN_LENGTH = 256;

    private final SessionMapper sessionMapper;
    private final UserDetailsServiceImpl userDetailsService;
    private final Duration sessionTtl;
    private final SecureRandom secureRandom = new SecureRandom();

    public AuthSessionService(SessionMapper sessionMapper,
                              UserDetailsServiceImpl userDetailsService,
                              @Value("${app.auth-session-ttl:PT12H}") Duration sessionTtl) {
        this.sessionMapper = Objects.requireNonNull(sessionMapper);
        this.userDetailsService = Objects.requireNonNull(userDetailsService);
        if (sessionTtl == null || sessionTtl.isZero() || sessionTtl.isNegative()) {
            throw new IllegalArgumentException("AUTH_SESSION_TTL_INVALID");
        }
        this.sessionTtl = sessionTtl;
    }

    @Transactional
    public String issue(UUID userId, String ipAddress, String userAgent) {
        byte[] tokenBytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        Instant now = Instant.now();

        SessionEntity session = new SessionEntity();
        session.setId(UUID.randomUUID());
        session.setUserId(userId);
        session.setTokenHash(hash(token));
        session.setIssuedAt(now);
        session.setExpiresAt(now.plus(sessionTtl));
        session.setLastSeenAt(now);
        session.setIpAddress(blankToNull(ipAddress));
        session.setUserAgent(limit(blankToNull(userAgent), 500));
        if (sessionMapper.insertSession(session) != 1) {
            throw new IllegalStateException("AUTH_SESSION_CREATE_FAILED");
        }
        return token;
    }

    @Transactional(readOnly = true)
    public Optional<UserDetails> authenticate(String token) {
        if (!isValidPresentedToken(token)) {
            return Optional.empty();
        }
        Optional<SessionEntity> session = sessionMapper.findActiveByTokenHash(hash(token), Instant.now());
        if (session.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(userDetailsService.loadUserById(session.get().getUserId()));
        } catch (AuthenticationException e) {
            return Optional.empty();
        }
    }

    @Transactional
    public void revoke(String token) {
        if (isValidPresentedToken(token)) {
            sessionMapper.revokeByTokenHash(hash(token), Instant.now());
        }
    }

    private static boolean isValidPresentedToken(String token) {
        return token != null && !token.isBlank() && token.length() <= MAX_PRESENTED_TOKEN_LENGTH;
    }

    private static byte[] hash(String token) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("AUTH_TOKEN_HASH_UNAVAILABLE", e);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String limit(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) return value;
        return value.substring(0, maxLength);
    }
}
