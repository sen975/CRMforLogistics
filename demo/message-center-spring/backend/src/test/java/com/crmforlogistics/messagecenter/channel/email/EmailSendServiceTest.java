package com.crmforlogistics.messagecenter.channel.email;

import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.ContactIdentityMapper;
import com.crmforlogistics.messagecenter.mapper.ConversationMapper;
import com.crmforlogistics.messagecenter.mapper.MessageMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EmailSendServiceTest {

    @Mock AppConfig config;
    @Mock MessageMapper messageMapper;
    @Mock ConversationMapper conversationMapper;
    @Mock ChannelAccountMapper channelAccountMapper;
    @Mock ContactIdentityMapper contactIdentityMapper;

    @Test
    void shouldConstructWithDependencies() {
        when(config.smtpHost()).thenReturn("smtp.example.com");
        when(config.smtpUser()).thenReturn("user@example.com");
        when(config.smtpPassword()).thenReturn("pass");
        when(config.smtpPort()).thenReturn("465");
        when(config.smtpSsl()).thenReturn(true);
        when(config.smtpStartTls()).thenReturn(false);
        when(config.smtpResolveIpv4()).thenReturn(false);
        when(config.mailFrom()).thenReturn("user@example.com");
        when(config.mailFromName()).thenReturn("");
        when(config.smtpLocalhost()).thenReturn(null);

        EmailSendService service = new EmailSendService(config, messageMapper,
                conversationMapper, channelAccountMapper, contactIdentityMapper);
        assertNotNull(service);
    }

    @Test
    void shouldValidateRequiredTo() {
        when(config.smtpHost()).thenReturn("smtp.example.com");
        when(config.smtpUser()).thenReturn("user@example.com");
        when(config.smtpPassword()).thenReturn("pass");
        when(config.smtpPort()).thenReturn("465");
        when(config.smtpSsl()).thenReturn(true);
        when(config.smtpStartTls()).thenReturn(false);
        when(config.smtpResolveIpv4()).thenReturn(false);
        when(config.mailFrom()).thenReturn("user@example.com");
        when(config.mailFromName()).thenReturn("");
        when(config.smtpLocalhost()).thenReturn(null);

        EmailSendService service = new EmailSendService(config, messageMapper,
                conversationMapper, channelAccountMapper, contactIdentityMapper);

        assertThrows(IllegalArgumentException.class,
                () -> service.send(null, "subject", "body"));
    }
}
