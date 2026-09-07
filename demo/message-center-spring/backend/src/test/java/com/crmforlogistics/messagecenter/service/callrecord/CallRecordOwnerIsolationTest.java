package com.crmforlogistics.messagecenter.service.callrecord;

import com.crmforlogistics.messagecenter.config.CallRecordConfig;
import com.crmforlogistics.messagecenter.config.FunAsrConfig;
import com.crmforlogistics.messagecenter.entity.CallRecordEntity;
import com.crmforlogistics.messagecenter.mapper.CallRecordMapper;
import com.crmforlogistics.messagecenter.mapper.CallTranscriptRevisionMapper;
import com.crmforlogistics.messagecenter.mapper.ContactIdentityMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CallRecordOwnerIsolationTest {
    private static final Instant NOW = Instant.parse("2026-09-04T08:00:00Z");

    @Test
    void detailLoadsRecordOnlyThroughOwnerScopedQuery() {
        CallRecordMapper mapper = mock(CallRecordMapper.class);
        UUID ownerId = UUID.randomUUID();
        UUID recordId = UUID.randomUUID();
        CallRecordEntity record = new CallRecordEntity();
        record.setId(recordId);
        record.setOwnerUserId(ownerId);
        when(mapper.findByIdAndOwner(recordId, ownerId)).thenReturn(Optional.of(record));

        assertThat(service(mapper).detail(ownerId, recordId)).isSameAs(record);

        verify(mapper).findByIdAndOwner(recordId, ownerId);
    }

    @Test
    void detailReturnsNotFoundWhenRecordBelongsToAnotherOwner() {
        CallRecordMapper mapper = mock(CallRecordMapper.class);
        UUID ownerId = UUID.randomUUID();
        UUID recordId = UUID.randomUUID();
        when(mapper.findByIdAndOwner(recordId, ownerId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service(mapper).detail(ownerId, recordId))
                .isInstanceOf(CallRecordException.class)
                .extracting(error -> ((CallRecordException) error).code())
                .isEqualTo("CALL_RECORD_NOT_FOUND");
    }

    private static CallRecordService service(CallRecordMapper mapper) {
        return new CallRecordService(
                mapper,
                mock(CallTranscriptRevisionMapper.class),
                mock(MinioAudioStore.class),
                mock(ContactIdentityMapper.class),
                new CallRecordConfig("data/call-records", 104_857_600L, 7_200,
                        10_737_418_240L, 10_000, 64, 1, 2_100, 3,
                        10_485_760L, 20_000, 20, 300, 8, 256),
                new FunAsrConfig("http://127.0.0.1:8000", "sensevoice",
                        java.time.Duration.ofSeconds(3), java.time.Duration.ofSeconds(30)),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
