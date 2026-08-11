package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.service.contact.ContactGroupService;
import com.crmforlogistics.messagecenter.service.contact.ContactService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.Invocation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockingDetails;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ContactController.class)
@AutoConfigureMockMvc(addFilters = false)
class ContactControllerAuthorizationTest {
    @Autowired MockMvc mvc;
    @MockitoBean ContactService contactService;
    @MockitoBean ContactGroupService contactGroupService;

    private UUID userId;

    @BeforeEach
    void authenticate() {
        userId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), "", java.util.List.of()));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void markReadPassesCurrentUserToContactService() throws Exception {
        UUID contactId = UUID.randomUUID();

        mvc.perform(post("/api/contacts/{id}/mark-read", contactId))
                .andExpect(status().isOk());

        Invocation invocation = mockingDetails(contactService).getInvocations().stream()
                .filter(item -> item.getMethod().getName().equals("markAsRead"))
                .findFirst()
                .orElseThrow();
        assertThat(invocation.getArguments()).containsExactly(userId, contactId);
    }
}
