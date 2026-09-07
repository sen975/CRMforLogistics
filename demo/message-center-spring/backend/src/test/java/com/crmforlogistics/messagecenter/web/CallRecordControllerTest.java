package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.entity.CallRecordEntity;
import com.crmforlogistics.messagecenter.mapper.CallRecordMapper;
import com.crmforlogistics.messagecenter.mapper.CallTranscriptRevisionMapper;
import com.crmforlogistics.messagecenter.mapper.ContactIdentityMapper;
import com.crmforlogistics.messagecenter.service.callrecord.CallAudioSessionService;
import com.crmforlogistics.messagecenter.service.callrecord.CallRecordException;
import com.crmforlogistics.messagecenter.service.callrecord.CallRecordService;
import com.crmforlogistics.messagecenter.service.callrecord.ContactTimelineService;
import com.crmforlogistics.messagecenter.service.callrecord.MinioAudioStore;
import com.crmforlogistics.messagecenter.service.contact.ContactService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CallRecordControllerTest {

    @Test
    void streamAudioDoesNotSetAudioContentTypeBeforeTheObjectIsOpened() throws Exception {
        UUID callRecordId = UUID.randomUUID();
        CallRecordService callRecordService = mock(CallRecordService.class);
        MinioAudioStore audioStore = mock(MinioAudioStore.class);
        CallRecordEntity entity = audioEntity(callRecordId);
        UUID ownerId = UUID.randomUUID();
        when(callRecordService.detail(ownerId, callRecordId)).thenReturn(entity);
        when(audioStore.open(any())).thenThrow(new CallRecordException(
                "CALL_AUDIO_NOT_FOUND", 404, "Call audio does not exist", false));

        MockHttpServletResponse response = new MockHttpServletResponse();

        var request = new MockHttpServletRequest();
        request.addHeader("Cookie", "mc_call_audio=session-token");
        var sessionService = mock(CallAudioSessionService.class);
        when(sessionService.authorize("session-token", callRecordId)).thenReturn(
                new CallAudioSessionService.AudioAuthorization(ownerId.toString(), callRecordId, 1L));
        CallRecordController controller = new CallRecordController(callRecordService, mock(ContactTimelineService.class),
                sessionService, audioStore, mock(CallRecordMapper.class),
                mock(CallTranscriptRevisionMapper.class), mock(ContactIdentityMapper.class),
                mock(ContactService.class));

        assertThrows(CallRecordException.class, () -> controller.streamAudio(
                callRecordId, request, response));

        assertNull(response.getContentType());
    }

    private static CallRecordEntity audioEntity(UUID id) {
        CallRecordEntity entity = new CallRecordEntity();
        entity.setId(id);
        entity.setAudioRelativePath("audio/" + id + ".mp3");
        entity.setAudioOriginalFileName("recording.mp3");
        entity.setAudioSizeBytes(3L);
        entity.setAudioSha256("a".repeat(64));
        entity.setAudioContentType("audio/mpeg");
        entity.setAudioDurationSeconds(1.0);
        entity.setAudioObjectKey("call-records/" + id + ".mp3");
        return entity;
    }
}
