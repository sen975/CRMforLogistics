package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.entity.CallRecordEntity;
import com.crmforlogistics.messagecenter.mapper.CallRecordMapper;
import com.crmforlogistics.messagecenter.mapper.CallTranscriptRevisionMapper;
import com.crmforlogistics.messagecenter.mapper.ContactIdentityMapper;
import com.crmforlogistics.messagecenter.service.auth.AuthSessionService;
import com.crmforlogistics.messagecenter.service.callrecord.CallAudioSessionService;
import com.crmforlogistics.messagecenter.service.callrecord.CallRecordService;
import com.crmforlogistics.messagecenter.service.callrecord.ContactTimelineService;
import com.crmforlogistics.messagecenter.service.callrecord.MinioAudioStore;
import com.crmforlogistics.messagecenter.service.contact.ContactService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CallRecordController.class)
@Import(com.crmforlogistics.messagecenter.config.SecurityConfig.class)
class CallRecordControllerOwnerSecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean CallRecordService callRecordService;
    @MockitoBean ContactTimelineService timelineService;
    @MockitoBean CallAudioSessionService sessionService;
    @MockitoBean MinioAudioStore audioStore;
    @MockitoBean CallRecordMapper callRecordMapper;
    @MockitoBean CallTranscriptRevisionMapper revisionMapper;
    @MockitoBean ContactIdentityMapper contactIdentityMapper;
    @MockitoBean ContactService contactService;
    @MockitoBean AuthSessionService authSessionService;

    @Test
    void detailPassesAuthenticatedOwnerToService() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID recordId = UUID.randomUUID();
        CallRecordEntity record = new CallRecordEntity();
        record.setId(recordId);
        record.setVersion(1L);
        when(callRecordService.detail(ownerId, recordId)).thenReturn(record);

        mvc.perform(get("/api/v1/call-records/{id}", recordId)
                        .with(user(ownerId.toString()).roles("AGENT")))
                .andExpect(status().isOk());

        verify(callRecordService).detail(ownerId, recordId);
    }
}
