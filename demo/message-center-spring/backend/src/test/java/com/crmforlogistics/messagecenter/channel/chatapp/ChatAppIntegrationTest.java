package com.crmforlogistics.messagecenter.channel.chatapp;

import com.crmforlogistics.messagecenter.config.AppConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatAppIntegrationTest {

    @Test
    void shouldProcessWebhookWithValidJson() {
        AppConfig config = mock(AppConfig.class);
        when(config.custSpaceId()).thenReturn("test-space");
        when(config.chatappFrom()).thenReturn("8612345678");
        ChatAppSendService service = new ChatAppSendService(config);

        String rawBody = "{\"MessageId\":\"wamid-123\",\"From\":\"8612345678\",\"To\":\"8611111111\"," +
                "\"Message\":{\"text\":\"hello\"}}";
        ChatAppSendService.SendResult result = service.processWebhook(rawBody);
        assertNotNull(result);
        assertTrue(result.messageId().contains("wamid-123"));
    }

    @Test
    void shouldProcessWebhookWithStatusUpdate() {
        AppConfig config = mock(AppConfig.class);
        when(config.custSpaceId()).thenReturn("test-space");
        when(config.chatappFrom()).thenReturn("8612345678");
        ChatAppSendService service = new ChatAppSendService(config);

        String rawBody = "{\"MessageId\":\"wamid-456\",\"From\":\"8612345678\"," +
                "\"To\":\"8611111111\",\"Status\":\"delivered\"}";
        ChatAppSendService.SendResult result = service.processWebhook(rawBody);
        assertNotNull(result);
        assertTrue(result.messageId().contains("status"));
    }

    @Test
    void shouldConstructSendServiceAndSyncServices() {
        AppConfig config = mock(AppConfig.class);
        when(config.custSpaceId()).thenReturn("test-space");
        when(config.chatappFrom()).thenReturn("8612345678");

        ChatAppSendService sendService = new ChatAppSendService(config);
        assertNotNull(sendService);
    }
}
