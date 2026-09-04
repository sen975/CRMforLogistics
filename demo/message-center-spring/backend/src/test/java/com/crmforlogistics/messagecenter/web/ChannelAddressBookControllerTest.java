package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.dto.response.ChannelAddressBookPageResponse;
import com.crmforlogistics.messagecenter.service.auth.AuthSessionService;
import com.crmforlogistics.messagecenter.service.contact.ChannelAddressBookService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ChannelAddressBookController.class)
@Import(com.crmforlogistics.messagecenter.config.SecurityConfig.class)
class ChannelAddressBookControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean ChannelAddressBookService service;
    @MockitoBean AuthSessionService authSessionService;

    @Test
    void listPassesAuthenticatedUserToService() throws Exception {
        UUID owner = UUID.randomUUID();
        when(service.page(owner, "email", null, 1, 20))
                .thenReturn(new ChannelAddressBookPageResponse(List.of(), 1, 20, false));

        mvc.perform(get("/api/channel-address-books/email")
                        .with(user(owner.toString()).roles("AGENT")))
                .andExpect(status().isOk());
        verify(service).page(owner, "email", null, 1, 20);
    }

    @Test
    void createUsesPathChannelAndRejectsMissingAddress() throws Exception {
        UUID owner = UUID.randomUUID();
        mvc.perform(post("/api/channel-address-books/chatapp")
                        .with(user(owner.toString()).roles("AGENT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Buyer\"}"))
                .andExpect(status().isBadRequest());
    }
}
