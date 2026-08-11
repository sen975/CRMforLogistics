package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.channel.email.EmailSendService;
import com.crmforlogistics.messagecenter.entity.MessageEntity;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.ContactIdentityMapper;
import com.crmforlogistics.messagecenter.mapper.ConversationMapper;
import com.crmforlogistics.messagecenter.mapper.MessageMapper;
import com.crmforlogistics.messagecenter.service.chatapp.ChatAppMediaApplicationService;
import com.crmforlogistics.messagecenter.service.chatapp.ChatAppMessageApplicationService;
import com.crmforlogistics.messagecenter.service.conversation.ConversationAccessService;
import com.crmforlogistics.messagecenter.service.message.TemplateMessageTextResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MessageController.class)
@AutoConfigureMockMvc(addFilters = false)
class MessageControllerAuthorizationTest {
    @Autowired MockMvc mvc;
    @MockitoBean MessageMapper messageMapper;
    @MockitoBean ChannelAccountMapper channelAccountMapper;
    @MockitoBean ConversationMapper conversationMapper;
    @MockitoBean ContactIdentityMapper contactIdentityMapper;
    @MockitoBean ChatAppMessageApplicationService chatAppMessageApplicationService;
    @MockitoBean ChatAppMediaApplicationService chatAppMediaApplicationService;
    @MockitoBean ConversationAccessService conversationAccessService;
    @MockitoBean TemplateMessageTextResolver templateMessageTextResolver;
    @MockitoBean EmailSendService emailSendService;

    private UUID userId;

    @BeforeEach
    void authenticate() {
        userId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        userId.toString(), "", java.util.List.of()));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void forbiddenMediaSendRemainsForbidden() throws Exception {
        when(chatAppMediaApplicationService.accept(
                any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new SecurityException("CHATAPP_CONVERSATION_FORBIDDEN"));

        mvc.perform(multipart("/api/send/chatapp-media")
                        .file("file", "image".getBytes())
                        .param("to", "60123456789")
                        .param("mediaType", "image"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void messageBodyIsNotReturnedWhenConversationIsForbidden() throws Exception {
        UUID messageId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID channelAccountId = UUID.randomUUID();
        MessageEntity message = new MessageEntity();
        message.setId(messageId);
        message.setConversationId(conversationId);
        message.setChannelAccountId(channelAccountId);
        message.setBodyText("must remain private");
        when(messageMapper.selectById(messageId)).thenReturn(message);
        when(conversationAccessService.requireAccessible(conversationId, channelAccountId, userId))
                .thenThrow(new SecurityException("CHATAPP_CONVERSATION_FORBIDDEN"));

        mvc.perform(get("/api/messages/{id}", messageId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void messageOwnerCanReadBodyAfterConversationAccessCheck() throws Exception {
        UUID messageId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID channelAccountId = UUID.randomUUID();
        MessageEntity message = new MessageEntity();
        message.setId(messageId);
        message.setConversationId(conversationId);
        message.setChannelAccountId(channelAccountId);
        message.setBodyText("owner-visible");
        when(messageMapper.selectById(messageId)).thenReturn(message);
        when(templateMessageTextResolver.resolve(message)).thenReturn("owner-visible");

        mvc.perform(get("/api/messages/{id}", messageId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bodyText").value("owner-visible"));

        verify(conversationAccessService).requireAccessible(
                conversationId, channelAccountId, userId);
    }

    @Test
    void missingMessageRemainsNotFoundWithoutAuthorizationLookup() throws Exception {
        UUID messageId = UUID.randomUUID();
        when(messageMapper.selectById(messageId)).thenReturn(null);

        mvc.perform(get("/api/messages/{id}", messageId))
                .andExpect(status().isNotFound());

        verifyNoInteractions(conversationAccessService, templateMessageTextResolver);
    }

    @Test
    void historicalTemplateBodyIsResolvedBeforeResponse() throws Exception {
        UUID messageId = UUID.randomUUID();
        MessageEntity message = new MessageEntity();
        message.setId(messageId);
        message.setMessageKind("template");
        message.setBodyText("[template]");
        when(messageMapper.selectById(messageId)).thenReturn(message);
        when(templateMessageTextResolver.resolve(message)).thenReturn("Hello Alice");

        mvc.perform(get("/api/messages/{id}", messageId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bodyText").value("Hello Alice"));
    }
}
