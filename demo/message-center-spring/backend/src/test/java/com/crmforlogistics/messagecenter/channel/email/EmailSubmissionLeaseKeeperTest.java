package com.crmforlogistics.messagecenter.channel.email;

import com.crmforlogistics.messagecenter.mapper.EmailSubmissionMapper;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class EmailSubmissionLeaseKeeperTest {
    @Test
    void renewsRegisteredLeaseAndStopsAfterRegistrationCloses() {
        EmailSubmissionMapper mapper = mock(EmailSubmissionMapper.class);
        EmailSubmissionLeaseKeeper keeper = new EmailSubmissionLeaseKeeper(mapper);
        UUID submissionId = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        when(mapper.renewLease(submissionId, token)).thenReturn(1);

        EmailSubmissionLeaseKeeper.Registration registration = keeper.track(submissionId, token);
        keeper.renewActiveLeases();
        registration.close();
        keeper.renewActiveLeases();

        verify(mapper).renewLease(submissionId, token);
        verifyNoMoreInteractions(mapper);
        keeper.shutdown();
    }

    @Test
    void dropsLeaseRegistrationWhenDatabaseReportsItIsNoLongerActive() {
        EmailSubmissionMapper mapper = mock(EmailSubmissionMapper.class);
        EmailSubmissionLeaseKeeper keeper = new EmailSubmissionLeaseKeeper(mapper);
        UUID submissionId = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        when(mapper.renewLease(submissionId, token)).thenReturn(0);

        keeper.track(submissionId, token);
        keeper.renewActiveLeases();
        keeper.renewActiveLeases();

        verify(mapper).renewLease(submissionId, token);
        verifyNoMoreInteractions(mapper);
        keeper.shutdown();
    }
}
