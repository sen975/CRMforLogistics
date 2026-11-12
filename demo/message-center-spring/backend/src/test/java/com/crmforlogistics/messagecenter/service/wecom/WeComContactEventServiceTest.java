package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.WeComAppEventCodec;
import com.crmforlogistics.messagecenter.channel.wecom.WeComContactEventEntity;
import com.crmforlogistics.messagecenter.channel.wecom.WeComException;
import com.crmforlogistics.messagecenter.channel.wecom.WeComInstallationEntity;
import com.crmforlogistics.messagecenter.channel.wecom.WeComInstallationService;
import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.mapper.WeComContactEventMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 落库服务的 ack 语义与去重键。
 *
 * <p>重点不是「能插入」，而是**什么时候允许对企微 ack**：只有提交成功或幂等冲突才返回 success，
 * 其余（开关关闭、未知安装、数据库不可用）都必须把重试机会留给企微。
 */
class WeComContactEventServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-21T02:00:00Z");
    private static final String SUITE_ID = "wwsuite";
    private static final String CORP_ID = "wpxxxxxxxxcorpid";
    private static final UUID INSTALLATION_ID =
            UUID.fromString("11111111-2222-3333-4444-555555555555");

    private WeComContactEventMapper mapper;
    private WeComInstallationService installationService;
    private AppConfig config;
    private WeComContactEventService service;

    @BeforeEach
    void setUp() {
        mapper = mock(WeComContactEventMapper.class);
        installationService = mock(WeComInstallationService.class);
        config = mock(AppConfig.class);
        when(config.wecomSuiteId()).thenReturn(SUITE_ID);
        when(config.wecomContactEventEnabled()).thenReturn(true);
        service = new WeComContactEventService(mapper, installationService, config,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void acksWhenTheRowIsInserted() {
        givenInstallation();
        when(mapper.insertIgnore(any())).thenReturn(1);

        assertThat(service.ingest(event("add_external_contact"))).isEqualTo(
                WeComContactEventService.IngestResult.ACCEPTED);
    }

    @Test
    void acksWhenTheSameEventIsRedelivered() {
        givenInstallation();
        // on conflict (dedupe_key) do nothing 时受影响行数为 0：企微重推，直接 ack 不再重试。
        when(mapper.insertIgnore(any())).thenReturn(0);

        assertThat(service.ingest(event("add_external_contact"))).isEqualTo(
                WeComContactEventService.IngestResult.DUPLICATE);
        assertThat(WeComContactEventService.IngestResult.DUPLICATE.acked()).isTrue();
    }

    @Test
    void doesNotAckWhileTheFeatureSwitchIsOff() {
        when(config.wecomContactEventEnabled()).thenReturn(false);

        assertThat(service.ingest(event("add_external_contact"))).isEqualTo(
                WeComContactEventService.IngestResult.DISABLED);
        verify(mapper, never()).insertIgnore(any());
    }

    @Test
    void doesNotAckWhenInstallationIsUnknownUnderTheConfiguredSuite() {
        when(installationService.find(anyString(), anyString())).thenReturn(null);

        assertThat(service.ingest(event("add_external_contact"))).isEqualTo(
                WeComContactEventService.IngestResult.INSTALLATION_UNKNOWN);
        verify(mapper, never()).insertIgnore(any());
        verify(installationService).find(SUITE_ID, CORP_ID);
    }

    @Test
    void doesNotAckWhenTheInstallationHasNoId() {
        // 安装记录存在但主键为空属于数据异常；此时不 ack，让企微重推而不是写出一行挂不上外键的事件。
        when(installationService.find(anyString(), anyString()))
                .thenReturn(new WeComInstallationEntity());

        assertThat(service.ingest(event("add_external_contact"))).isEqualTo(
                WeComContactEventService.IngestResult.INSTALLATION_UNKNOWN);
        verify(mapper, never()).insertIgnore(any());
    }

    @Test
    void databaseFailureWhileResolvingInstallationIsRetriable503() {
        when(installationService.find(anyString(), anyString()))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("connection refused"));

        assertThatThrownBy(() -> service.ingest(event("add_external_contact")))
                .isInstanceOf(WeComException.class)
                .satisfies(thrown -> {
                    WeComException failure = (WeComException) thrown;
                    assertThat(failure.code()).isEqualTo("WECOM_CONTACT_EVENT_INSTALLATION_LOOKUP_FAILED");
                    assertThat(failure.httpStatus()).isEqualTo(503);
                });
        verify(mapper, never()).insertIgnore(any());
    }

    @Test
    void failsWithRetriableErrorWhenTheDatabaseIsUnavailable() {
        givenInstallation();
        when(mapper.insertIgnore(any())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException(
                "connection refused"));

        assertThatThrownBy(() -> service.ingest(event("add_external_contact")))
                .isInstanceOf(WeComException.class)
                .satisfies(thrown -> assertThat(((WeComException) thrown).httpStatus()).isEqualTo(503))
                .hasMessageContaining("落库失败");
    }

    @Test
    void resolvesInstallationByConfiguredSuiteAndCiphertextCorpId() {
        givenInstallation();
        when(mapper.insertIgnore(any())).thenReturn(1);

        service.ingest(event("del_external_contact"));

        verify(installationService).find(SUITE_ID, CORP_ID);
    }

    @Test
    void persistedRowKeepsProviderFactsAndStartsAsReceived() {
        givenInstallation();
        when(mapper.insertIgnore(any())).thenReturn(1);

        service.ingest(event("del_external_contact"));

        ArgumentCaptor<WeComContactEventEntity> captor =
                ArgumentCaptor.forClass(WeComContactEventEntity.class);
        verify(mapper).insertIgnore(captor.capture());
        WeComContactEventEntity row = captor.getValue();
        assertThat(row.getInstallationId()).isEqualTo(INSTALLATION_ID);
        assertThat(row.getSuiteId()).isEqualTo(SUITE_ID);
        assertThat(row.getAuthCorpId()).isEqualTo(CORP_ID);
        assertThat(row.getEvent()).isEqualTo("change_external_contact");
        assertThat(row.getChangeType()).isEqualTo("del_external_contact");
        assertThat(row.getExternalUserId()).isEqualTo("wmZZZZZZZZ");
        assertThat(row.getProviderCreatedAt()).isEqualTo(Instant.ofEpochSecond(1_403_610_513L));
        assertThat(row.getReceivedAt()).isEqualTo(NOW);
        assertThat(row.getIngestStatus()).isEqualTo("RECEIVED");
        assertThat(row.getAttemptCount()).isZero();
        assertThat(row.getDedupeKey()).hasSize(64);
    }

    @Test
    void timelineQueryClampsTheLimitToItsBoundedRange() {
        when(mapper.listTimeline(any(), any(), any(), anyInt())).thenReturn(java.util.List.of());

        service.listTimeline(INSTALLATION_ID, null, null, 10_000);
        service.listTimeline(INSTALLATION_ID, null, null, 0);

        verify(mapper).listTimeline(eq(INSTALLATION_ID), isNull(), isNull(), eq(200));
        verify(mapper).listTimeline(eq(INSTALLATION_ID), isNull(), isNull(), eq(1));
    }

    // ---------- 去重键 ----------

    @Test
    void dedupeKeyIsStableForIdenticalEvents() {
        assertThat(key("add_external_contact", "state-a", "WELCOME")).isEqualTo(
                key("add_external_contact", "state-a", "WELCOME"));
    }

    @Test
    void dedupeKeyKeepsEverySemanticField() {
        String base = key("add_external_contact", "state-a", "WELCOME");

        assertThat(key("del_external_contact", "state-a", "WELCOME")).isNotEqualTo(base);
        assertThat(key("add_external_contact", "state-b", "WELCOME")).isNotEqualTo(base);
        assertThat(key("add_external_contact", "state-a", "WELCOME2")).isNotEqualTo(base);
        assertThat(WeComContactEventService.dedupeKey(INSTALLATION_ID, "change_external_contact",
                "add_external_contact", "zhangsan", "wmZZZZZZZZ", null, "state-a", "WELCOME", null,
                null, Instant.ofEpochSecond(1_403_610_513L))).isEqualTo(base);
        // 不同安装的同一事件不是同一条
        assertThat(WeComContactEventService.dedupeKey(UUID.randomUUID(), "change_external_contact",
                "add_external_contact", "zhangsan", "wmZZZZZZZZ", null, "state-a", "WELCOME", null,
                null, Instant.ofEpochSecond(1_403_610_513L))).isNotEqualTo(base);
    }

    @Test
    void dedupeKeyDoesNotNormalizeCase() {
        // 密文 ID 与 welcome code 都区分大小写，toLowerCase() 会把两个不同事件判成同一条。
        String lower = WeComContactEventService.dedupeKey(INSTALLATION_ID, "change_external_contact",
                "del_follow_user", "zhangsan", "wmAAAA", null, null, null, null, null, CREATED);
        String upper = WeComContactEventService.dedupeKey(INSTALLATION_ID, "change_external_contact",
                "del_follow_user", "zhangsan", "WMAAAA", null, null, null, null, null, CREATED);

        assertThat(lower).isNotEqualTo(upper);
        assertThat(WeComContactEventService.dedupeKey(INSTALLATION_ID, "change_external_contact",
                "del_follow_user", "zhangsan", "WMAAAA", null, null, null, null, null, CREATED))
                .as("同一大小写的重复投递仍必须收敛到同一条").isEqualTo(upper);
    }

    @Test
    void dedupeKeyKeepsNullDistinctFromEmptyString() {
        String withNull = WeComContactEventService.dedupeKey(INSTALLATION_ID, "change_external_contact",
                "add_external_contact", "zhangsan", "wmZZZZZZZZ", null, null, null, null, null, CREATED);
        String withEmpty = WeComContactEventService.dedupeKey(INSTALLATION_ID, "change_external_contact",
                "add_external_contact", "zhangsan", "wmZZZZZZZZ", null, "", null, null, null, CREATED);

        assertThat(withNull).isNotEqualTo(withEmpty);
    }

    @Test
    void dedupeKeyUsesProviderCreateTimeNotLocalReceiveTime() {
        // 企微重推时 CreateTime 不变、本地接收时刻每次都变；用接收时刻做键等于没有幂等。
        assertThat(WeComContactEventService.dedupeKey(INSTALLATION_ID, "change_external_contact",
                "add_external_contact", "zhangsan", "wmZZZZZZZZ", null, null, null, null, null,
                Instant.ofEpochSecond(1_403_610_513L)))
                .isNotEqualTo(WeComContactEventService.dedupeKey(INSTALLATION_ID, "change_external_contact",
                        "add_external_contact", "zhangsan", "wmZZZZZZZZ", null, null, null, null, null,
                        Instant.ofEpochSecond(1_403_610_514L)));
    }

    // ---------- 夹具 ----------

    private static final Instant CREATED = Instant.ofEpochSecond(1_403_610_513L);

    private static String key(String changeType, String state, String welcomeCode) {
        return WeComContactEventService.dedupeKey(INSTALLATION_ID, "change_external_contact",
                changeType, "zhangsan", "wmZZZZZZZZ", null, state, welcomeCode, null, null, CREATED);
    }

    private void givenInstallation() {
        WeComInstallationEntity installation = new WeComInstallationEntity();
        installation.setId(INSTALLATION_ID);
        installation.setSuiteId(SUITE_ID);
        installation.setAuthCorpId(CORP_ID);
        when(installationService.find(anyString(), anyString())).thenReturn(installation);
    }

    private static WeComAppEventCodec.DecodedAppEvent event(String changeType) {
        return new WeComAppEventCodec.DecodedAppEvent(CORP_ID, "sys", "change_external_contact",
                changeType, "zhangsan", "wmZZZZZZZZ", null, "state-a", "WELCOME", null,
                "DELETE_BY_TRANSFER", CREATED);
    }
}
