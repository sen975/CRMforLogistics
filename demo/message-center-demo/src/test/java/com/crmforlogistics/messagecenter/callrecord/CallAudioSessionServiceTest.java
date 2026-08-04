package com.crmforlogistics.messagecenter.callrecord;

import com.crmforlogistics.messagecenter.Config;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CallAudioSessionServiceTest {
    private static final UUID CALL_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final UUID OTHER_CALL_ID = UUID.fromString("d2719b17-ae1f-4e7e-9c5b-1f47875d6884");

    @Test
    void createsPathBoundHttpOnlyCookieAndRejectsOtherRecord() throws Exception {
        CallAudioSessionService sessions = service(new MutableClock(Instant.ofEpochSecond(1_000)), Map.of());

        CallAudioSessionService.AudioSessionCookie cookie = sessions.create("zhangsan", CALL_ID);

        assertTrue(cookie.headerValue().contains("mc_call_audio=" + cookie.value()));
        assertTrue(cookie.headerValue().contains("HttpOnly"));
        assertTrue(cookie.headerValue().contains("SameSite=Strict"));
        assertTrue(cookie.headerValue().contains("Max-Age=300"));
        assertTrue(cookie.headerValue().contains("Path=/api/v1/call-records/" + CALL_ID + "/audio"));
        assertEquals(CALL_ID, sessions.authorize(cookie.value(), CALL_ID).callRecordId());
        assertThrows(CallRecordException.class, () -> sessions.authorize(cookie.value(), OTHER_CALL_ID));
    }

    @Test
    void expiresSessionsWithoutConsumingValidRangeAuthorization() throws Exception {
        MutableClock clock = new MutableClock(Instant.ofEpochSecond(1_000));
        CallAudioSessionService sessions = service(clock, Map.of());
        String token = sessions.create("zhangsan", CALL_ID).value();

        assertEquals(CALL_ID, sessions.authorize(token, CALL_ID).callRecordId());
        assertEquals(CALL_ID, sessions.authorize(token, CALL_ID).callRecordId());
        clock.advance(Duration.ofSeconds(300));
        CallRecordException exception = assertThrows(CallRecordException.class,
                () -> sessions.authorize(token, CALL_ID));
        assertEquals("AUDIO_SESSION_EXPIRED", exception.code());
        assertEquals(401, exception.httpStatus());
    }

    @Test
    void evictsOldestSessionsPerActorAndGlobally() throws Exception {
        MutableClock clock = new MutableClock(Instant.ofEpochSecond(1_000));
        CallAudioSessionService perActor = service(clock, Map.of("CALL_AUDIO_SESSION_MAX_PER_ACTOR", "1"));
        String oldest = perActor.create("zhangsan", CALL_ID).value();
        clock.advance(Duration.ofSeconds(1));
        String newest = perActor.create("zhangsan", OTHER_CALL_ID).value();

        assertThrows(CallRecordException.class, () -> perActor.authorize(oldest, CALL_ID));
        assertEquals(OTHER_CALL_ID, perActor.authorize(newest, OTHER_CALL_ID).callRecordId());

        CallAudioSessionService global = service(clock, Map.of("CALL_AUDIO_SESSION_MAX_ACTIVE", "8"));
        String first = global.create("actor-0", CALL_ID).value();
        for (int index = 1; index <= 8; index++) {
            clock.advance(Duration.ofSeconds(1));
            global.create("actor-" + index, OTHER_CALL_ID);
        }
        assertThrows(CallRecordException.class, () -> global.authorize(first, CALL_ID));
    }

    @Test
    void derivesSecureCookieOnlyFromConfiguredRedirectAndKeepsNoReusableTokenState() throws Exception {
        MutableClock clock = new MutableClock(Instant.ofEpochSecond(1_000));
        CallAudioSessionService https = service(clock, Map.of(
                "WECOM_ALLOWED_JSAPI_ORIGINS", "https://crm.example.com",
                "WECOM_LOGIN_REDIRECT_URI", "https://crm.example.com/"));
        CallAudioSessionService.AudioSessionCookie first = https.create("zhangsan", CALL_ID);
        CallAudioSessionService.AudioSessionCookie second = https.create("zhangsan", CALL_ID);

        assertTrue(first.headerValue().endsWith("; Secure"));
        assertNotEquals(first.value(), second.value());
        assertFalse(first.toString().contains(first.value()));
        assertFalse(service(clock, Map.of()).create("zhangsan", CALL_ID).headerValue().contains("Secure"));
        assertThrows(CallRecordException.class,
                () -> service(clock, Map.of()).authorize(first.value(), CALL_ID));
    }

    private static CallAudioSessionService service(MutableClock clock, Map<String, String> overrides) {
        java.util.HashMap<String, String> environment = new java.util.HashMap<>(overrides);
        environment.putIfAbsent("WECOM_ALLOWED_JSAPI_ORIGINS", "http://localhost:8099");
        environment.putIfAbsent("WECOM_LOGIN_REDIRECT_URI", "http://localhost:8099/");
        return CallAudioSessionService.forTests(new Config(environment), clock);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override public Instant instant() {
            return instant;
        }
    }
}
