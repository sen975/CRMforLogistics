package com.crmforlogistics.messagecenter.channel.chatapp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ChatAppIntegrationTest {

    @Test
    void shouldProcessWebhookWithValidJson() {
        ChatAppSendService service = new ChatAppSendService(mock(ChatAppOssMediaUploader.class));

        String rawBody = "{\"MessageId\":\"wamid-123\",\"From\":\"8612345678\",\"To\":\"8611111111\"," +
                "\"Message\":{\"text\":\"hello\"}}";
        ChatAppSendService.SendResult result = service.processWebhook(rawBody);
        assertNotNull(result);
        assertTrue(result.messageId().contains("wamid-123"));
    }

    @Test
    void shouldProcessWebhookWithStatusUpdate() {
        ChatAppSendService service = new ChatAppSendService(mock(ChatAppOssMediaUploader.class));

        String rawBody = "{\"MessageId\":\"wamid-456\",\"From\":\"8612345678\"," +
                "\"To\":\"8611111111\",\"Status\":\"delivered\"}";
        ChatAppSendService.SendResult result = service.processWebhook(rawBody);
        assertNotNull(result);
        assertTrue(result.messageId().contains("status"));
    }

    @Test
    void shouldConstructSendServiceAndSyncServices() {
        ChatAppSendService sendService = new ChatAppSendService(mock(ChatAppOssMediaUploader.class));
        assertNotNull(sendService);
    }
}
