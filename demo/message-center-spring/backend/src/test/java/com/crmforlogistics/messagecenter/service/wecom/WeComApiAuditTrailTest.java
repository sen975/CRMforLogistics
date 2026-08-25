package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.ResolvedInstallation;
import com.crmforlogistics.messagecenter.channel.wecom.WeComApiAuditEntity;
import com.crmforlogistics.messagecenter.channel.wecom.WeComException;
import com.crmforlogistics.messagecenter.mapper.WeComApiAuditMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

class WeComApiAuditTrailTest {
    private static final Instant NOW = Instant.parse("2026-08-18T03:00:00Z");
    private static final UUID ACTOR = UUID.fromString("d6c6baaa-a40b-4de4-8324-25788d103cbe");
    private static final ResolvedInstallation INSTALLATION = new ResolvedInstallation(
            "21cc8d72-b5b5-4b2d-a57a-c3752c0603d8", "suite", "corp-1",
            "100001", "permanent-code-must-not-be-stored", 3L);

    @Test
    void springCanConstructAuditTrailWithItsPrimaryConstructor() {
        new ApplicationContextRunner()
                .withBean(WeComApiAuditMapper.class, () -> mock(WeComApiAuditMapper.class))
                .withUserConfiguration(WeComApiAuditTrail.class)
                .withPropertyValues("app.wecom-enabled=true")
                .run(context -> assertThat(context).hasSingleBean(WeComApiAuditTrail.class));
    }

    @Test
    void successWritesOnlyBoundedOperationMetadata() {
        WeComApiAuditMapper mapper = mock(WeComApiAuditMapper.class);
        when(mapper.insert(any(WeComApiAuditEntity.class))).thenReturn(1);
        WeComApiAuditTrail trail = new WeComApiAuditTrail(mapper,
                Clock.fixed(NOW, ZoneOffset.UTC));

        WeComApiAuditTrail.Attempt attempt = trail.begin(ACTOR, INSTALLATION,
                "wecom.api.directory.member_get", "/cgi-bin/user/get", "trace-1");
        trail.success(attempt);

        ArgumentCaptor<WeComApiAuditEntity> captor = ArgumentCaptor.forClass(WeComApiAuditEntity.class);
        verify(mapper, org.mockito.Mockito.times(2)).insert(captor.capture());
        assertThat(captor.getAllValues()).extracting(WeComApiAuditEntity::getResult)
                .containsExactly("accepted", "success");
        WeComApiAuditEntity entity = captor.getAllValues().get(1);
        assertThat(entity.getId()).isNotNull();
        assertThat(entity.getInstallationId()).isEqualTo(UUID.fromString(INSTALLATION.installationId()));
        assertThat(entity.getActorUserId()).isEqualTo(ACTOR);
        assertThat(entity.getResult()).isEqualTo("success");
        assertThat(entity.getUpstreamPath()).isEqualTo("/cgi-bin/user/get");
        assertThat(entity.toString()).doesNotContain(INSTALLATION.permanentCode())
                .doesNotContain("access_token");
    }

    @Test
    void failureStoresDiagnosticFieldsWithoutProviderBody() {
        WeComApiAuditMapper mapper = mock(WeComApiAuditMapper.class);
        when(mapper.insert(any(WeComApiAuditEntity.class))).thenReturn(1);
        WeComApiAuditTrail trail = new WeComApiAuditTrail(mapper,
                Clock.fixed(NOW, ZoneOffset.UTC));
        WeComApiAuditTrail.Attempt attempt = trail.begin(ACTOR, INSTALLATION,
                "wecom.api.appchat.send", "/cgi-bin/appchat/send", "trace-2");

        trail.failed(attempt, new WeComException("WECOM_API_UPSTREAM_ERROR", 502,
                "safe", 40058, "/cgi-bin/appchat/send", 200, "safe_hint"));

        ArgumentCaptor<WeComApiAuditEntity> captor = ArgumentCaptor.forClass(WeComApiAuditEntity.class);
        verify(mapper, org.mockito.Mockito.times(2)).insert(captor.capture());
        WeComApiAuditEntity entity = captor.getAllValues().get(1);
        assertThat(entity.getResult()).isEqualTo("failed");
        assertThat(entity.getErrorCode()).isEqualTo("WECOM_API_UPSTREAM_ERROR");
        assertThat(entity.getUpstreamErrcode()).isEqualTo(40058);
        assertThat(entity.getUpstreamHint()).isEqualTo("safe_hint");
    }
}
