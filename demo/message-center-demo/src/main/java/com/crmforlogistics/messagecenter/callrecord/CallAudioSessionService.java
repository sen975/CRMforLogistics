package com.crmforlogistics.messagecenter.callrecord;

import com.crmforlogistics.messagecenter.Config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/** Holds short-lived, path-bound audio playback authorizations in process memory only. */
public final class CallAudioSessionService {
    private static final String COOKIE_NAME = "mc_call_audio";
    private static final int TOKEN_BYTES = 32;

    private final Config config;
    private final Clock clock;
    private final SecureRandom random;
    private final ConcurrentMap<String, AudioSession> sessionsByTokenHash = new ConcurrentHashMap<>();
    private final AtomicLong createdOrder = new AtomicLong();

    public CallAudioSessionService(Config config) {
        this(config, Clock.systemUTC(), new SecureRandom());
    }

    private CallAudioSessionService(Config config, Clock clock, SecureRandom random) {
        this.config = config;
        this.clock = clock;
        this.random = random;
    }

    static CallAudioSessionService forTests(Config config, Clock clock) {
        return new CallAudioSessionService(config, clock, new SecureRandom());
    }

    public synchronized AudioSessionCookie create(String actor, UUID callRecordId) throws CallRecordException {
        requireActor(actor);
        requireCallRecordId(callRecordId);
        long now = clock.instant().getEpochSecond();
        cleanupExpired(now);
        evictOldestForActor(actor);
        evictOldestGlobally();

        byte[] tokenBytes = new byte[TOKEN_BYTES];
        String token;
        String tokenHash;
        do {
            random.nextBytes(tokenBytes);
            token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
            tokenHash = hashToken(token);
        } while (sessionsByTokenHash.containsKey(tokenHash));

        long expiresAt = now + config.callAudioSessionTtlSeconds();
        sessionsByTokenHash.put(tokenHash, new AudioSession(actor, callRecordId, expiresAt,
                createdOrder.incrementAndGet()));
        String path = "/api/v1/call-records/" + callRecordId + "/audio";
        String header = COOKIE_NAME + "=" + token
                + "; Max-Age=" + config.callAudioSessionTtlSeconds()
                + "; Path=" + path + "; HttpOnly; SameSite=Strict"
                + (config.callAudioCookieSecure() ? "; Secure" : "");
        return new AudioSessionCookie(token, header);
    }

    public synchronized AudioAuthorization authorize(String cookieValue, UUID callRecordId)
            throws CallRecordException {
        requireCallRecordId(callRecordId);
        if (cookieValue == null || cookieValue.isBlank() || cookieValue.length() > 128) {
            throw expired();
        }
        long now = clock.instant().getEpochSecond();
        cleanupExpired(now);
        AudioSession session = sessionsByTokenHash.get(hashToken(cookieValue));
        if (session == null || now >= session.expiresAtEpochSecond() || !session.callRecordId().equals(callRecordId)) {
            throw expired();
        }
        return new AudioAuthorization(session.actor(), session.callRecordId(), session.expiresAtEpochSecond());
    }

    private void cleanupExpired(long now) {
        sessionsByTokenHash.entrySet().removeIf(entry -> now >= entry.getValue().expiresAtEpochSecond());
    }

    private void evictOldestForActor(String actor) {
        while (countForActor(actor) >= config.callAudioSessionMaxPerActor()) {
            oldest(entry -> entry.getValue().actor().equals(actor)).ifPresentOrElse(
                    entry -> sessionsByTokenHash.remove(entry.getKey(), entry.getValue()),
                    () -> { throw new IllegalStateException("audio session actor limit is inconsistent"); });
        }
    }

    private void evictOldestGlobally() {
        while (sessionsByTokenHash.size() >= config.callAudioSessionMaxActive()) {
            oldest(entry -> true).ifPresentOrElse(
                    entry -> sessionsByTokenHash.remove(entry.getKey(), entry.getValue()),
                    () -> { throw new IllegalStateException("audio session global limit is inconsistent"); });
        }
    }

    private long countForActor(String actor) {
        return sessionsByTokenHash.values().stream().filter(session -> session.actor().equals(actor)).count();
    }

    private java.util.Optional<Map.Entry<String, AudioSession>> oldest(
            java.util.function.Predicate<Map.Entry<String, AudioSession>> filter) {
        return sessionsByTokenHash.entrySet().stream().filter(filter)
                .min(Comparator.comparingLong(entry -> entry.getValue().createdOrder()));
    }

    private static String hashToken(String token) throws CallRecordException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new CallRecordException("AUDIO_SESSION_UNAVAILABLE", 503,
                    "音频播放会话不可用", false, exception);
        }
    }

    private static void requireActor(String actor) throws CallRecordException {
        if (actor == null || actor.isBlank() || actor.length() > 128) {
            throw new CallRecordException("AUDIO_SESSION_ACTOR_INVALID", 400,
                    "播放会话操作人无效", false);
        }
    }

    private static void requireCallRecordId(UUID callRecordId) throws CallRecordException {
        if (callRecordId == null) {
            throw new CallRecordException("CALL_RECORD_ID_INVALID", 400,
                    "电话记录标识无效", false);
        }
    }

    private static CallRecordException expired() {
        return new CallRecordException("AUDIO_SESSION_EXPIRED", 401,
                "音频播放会话已过期或无效", false);
    }

    public record AudioSessionCookie(String value, String headerValue) {
        @Override public String toString() {
            return "AudioSessionCookie[redacted]";
        }
    }
    public record AudioAuthorization(String actor, UUID callRecordId, long expiresAtEpochSecond) {}
    private record AudioSession(String actor, UUID callRecordId, long expiresAtEpochSecond, long createdOrder) {}
}
