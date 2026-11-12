package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.dto.response.ConversationPreferenceResponse;
import com.crmforlogistics.messagecenter.service.conversation.ConversationPreferenceService;
import com.crmforlogistics.messagecenter.service.conversation.UnifiedConversationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ConversationControllerTest {
    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void delegatesPinHideAndOrderToTheCurrentUsersPreferenceService() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        var preferences = mock(ConversationPreferenceService.class);
        when(preferences.togglePinned(userId, "CONTACT", contactId))
                .thenReturn(new ConversationPreferenceResponse("CONTACT", contactId, true, false, "悦为小森"));
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(userId.toString(), "n/a", java.util.List.of()));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new ConversationController(mock(UnifiedConversationService.class), preferences)).build();

        mvc.perform(post("/api/conversations/preferences/pin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\":\"CONTACT\",\"targetId\":\"" + contactId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pinned").value(true));
        mvc.perform(post("/api/conversations/preferences/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\":\"WECOM_GROUP\",\"targetId\":\"" + groupId + "\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/conversations/preferences/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceType\":\"CONTACT\",\"sourceId\":\"" + contactId
                                + "\",\"targetType\":\"WECOM_GROUP\",\"targetId\":\"" + groupId
                                + "\",\"placement\":\"BEFORE\"}"))
                .andExpect(status().isNoContent());

        verify(preferences).togglePinned(userId, "CONTACT", contactId);
        verify(preferences).hide(userId, "WECOM_GROUP", groupId);
        verify(preferences).reorder(userId, "CONTACT", contactId,
                "WECOM_GROUP", groupId, "BEFORE");
    }
}
