package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.service.contact.ContactGroupService;
import com.crmforlogistics.messagecenter.service.contact.ContactService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ContactController.class)
@AutoConfigureMockMvc(addFilters = false)
class ContactControllerTest {
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
    void updatesContactTagsThroughPutEndpoint() throws Exception {
        UUID contactId = UUID.randomUUID();

        mvc.perform(put("/api/contacts/{id}/tags", contactId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tags\":[]}"))
                .andExpect(status().isOk());

        verify(contactGroupService).updateTags(eq(contactId), eq(java.util.List.of()), eq(userId));
    }
}
