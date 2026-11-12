package com.crmforlogistics.messagecenter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class SessionService {
    private static final int TOKEN_BYTES = 32;
    private final JdbcAuthRepository repository;
    private final PasswordHasher passwordHasher;
    private final AuditService audit;
    private final Clock clock;
    private final Duration sessionTtl;
    private final SecureRandom secureRandom = new SecureRandom();

    public SessionService(JdbcAuthRepository repository, PasswordHasher passwordHasher,
                          AuditService audit, Clock clock, Duration sessionTtl) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.passwordHasher = Objects.requireNonNull(passwordHasher, "passwordHasher");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sessionTtl = Objects.requireNonNull(sessionTtl, "sessionTtl");
        if (sessionTtl.isZero() || sessionTtl.isNegative() || sessionTtl.compareTo(Duration.ofDays(30)) > 0) {
            throw new IllegalArgumentException("Session TTL is invalid");
        }
    }

    public IssuedSession login(String username, char[] password) throws Exception {
        try {
            Instant now = clock.instant();
            AuthUser user = repository.findUser(username).orElse(null);
            boolean valid = user != null && "active".equals(user.status())
                    && passwordHasher.verify(user.passwordHash(), password);
            if (!valid) {
                audit.record(null, "auth.login", "user", user == null ? null : user.id(),
                        Map.of(), Map.of("username", safeUsername(username)), "denied");
                throw new AuthenticationException("INVALID_CREDENTIALS");
            }
            byte[] raw = new byte[TOKEN_BYTES];
            byte[] tokenHash = null;
            secureRandom.nextBytes(raw);
            String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
            try {
                tokenHash = sha256(rawToken);
                Instant expiresAt = now.plus(sessionTtl);
                repository.createSession(user.id(), tokenHash, now, expiresAt, audit);
                return new IssuedSession(rawToken, expiresAt, user.id());
            } finally {
                Arrays.fill(raw, (byte) 0);
                if (tokenHash != null) Arrays.fill(tokenHash, (byte) 0);
            }
        } finally {
            if (password != null) Arrays.fill(password, '\0');
        }
    }

    public AuthenticatedSession authenticate(String rawToken) throws Exception {
        byte[] tokenHash = sha256(rawToken);
        try {
            return repository.findSession(tokenHash, clock.instant())
                    .orElseThrow(() -> new AuthenticationException("INVALID_SESSION"));
        } finally {
            Arrays.fill(tokenHash, (byte) 0);
        }
    }

    public void logout(String rawToken) throws Exception {
        byte[] tokenHash = sha256(rawToken);
        try {
            if (repository.revokeSession(tokenHash, clock.instant(), audit).isEmpty()) {
                audit.record(null, "auth.logout", "session", null,
                        Map.of(), Map.of(), "denied");
                throw new AuthenticationException("INVALID_SESSION");
            }
        } finally {
            Arrays.fill(tokenHash, (byte) 0);
        }
    }

    public BootstrapResult bootstrapAdmin(String username, PasswordSupplier passwordSupplier) throws Exception {
        Objects.requireNonNull(passwordSupplier, "passwordSupplier");
        return repository.bootstrapAdmin(username, () -> {
            char[] password = null;
            try {
                password = passwordSupplier.get();
                return passwordHasher.hash(password);
            } finally {
                if (password != null) Arrays.fill(password, '\0');
            }
        }, audit);
    }

    private static byte[] sha256(String rawToken) throws Exception {
        if (rawToken == null || rawToken.isBlank() || rawToken.length() > 200) {
            throw new AuthenticationException("INVALID_SESSION");
        }
        byte[] bytes = rawToken.getBytes(StandardCharsets.US_ASCII);
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private static String safeUsername(String username) {
        if (username == null) return "";
        return username.length() <= 100 ? username : username.substring(0, 100);
    }

    public static final class AuthenticationException extends Exception {
        private final String code;

        AuthenticationException(String code) {
            super("Authentication failed");
            this.code = code;
        }

        public String code() { return code; }
    }
}

record IssuedSession(String rawToken, Instant expiresAt, UUID userId) {}
record AuthenticatedSession(UUID sessionId, UUID userId, Instant expiresAt) {}

@FunctionalInterface
interface PasswordSupplier {
    char[] get() throws Exception;
}
