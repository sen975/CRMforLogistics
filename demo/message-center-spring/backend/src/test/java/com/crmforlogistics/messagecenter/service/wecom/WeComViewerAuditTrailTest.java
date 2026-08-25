package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.WeComViewerAuditEntity;
import com.crmforlogistics.messagecenter.mapper.WeComViewerAuditMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WeComViewerAuditTrailTest {
    @Test
    void recordAssignsDatabaseIdBeforeInsert() {
        WeComViewerAuditMapper mapper = mock(WeComViewerAuditMapper.class);
        when(mapper.insert(any(WeComViewerAuditEntity.class))).thenReturn(1);
        WeComViewerAuditTrail trail = new WeComViewerAuditTrail(mapper,
                Clock.fixed(Instant.parse("2026-08-19T00:00:00Z"), ZoneOffset.UTC));

        trail.record("wecom.viewer.session_create", "success", "user-1", "contact-1", "session-1");

        ArgumentCaptor<WeComViewerAuditEntity> captor = ArgumentCaptor.forClass(WeComViewerAuditEntity.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getId()).isNotNull();
    }

    @Test
    void diagnosticRecordAssignsDatabaseIdBeforeInsert() {
        WeComViewerAuditMapper mapper = mock(WeComViewerAuditMapper.class);
        when(mapper.insert(any(WeComViewerAuditEntity.class))).thenReturn(1);
        WeComViewerAuditTrail trail = new WeComViewerAuditTrail(mapper,
                Clock.fixed(Instant.parse("2026-08-19T00:00:00Z"), ZoneOffset.UTC));

        trail.recordDiagnostic("wecom.viewer.chatdata_sync", "failed", "user-1", "contact-1", "session-1",
                "WECOM_CHATDATA_PROGRAM_ERROR", 710660, "/cgi-bin/chatdata/sync_call_program", 200, "upstream");

        ArgumentCaptor<WeComViewerAuditEntity> captor = ArgumentCaptor.forClass(WeComViewerAuditEntity.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getId()).isNotNull();
    }
}
