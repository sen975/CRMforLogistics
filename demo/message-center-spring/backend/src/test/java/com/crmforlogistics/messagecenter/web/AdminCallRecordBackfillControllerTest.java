package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.config.CorsConfig;
import com.crmforlogistics.messagecenter.config.SecurityConfig;
import com.crmforlogistics.messagecenter.service.auth.AuthSessionService;
import com.crmforlogistics.messagecenter.service.callrecord.CallRecordContactBackfillService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminCallRecordBackfillController.class)
@Import({SecurityConfig.class, CorsConfig.class, GlobalExceptionHandler.class})
class AdminCallRecordBackfillControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean CallRecordContactBackfillService backfill;
    @MockitoBean AuthSessionService authSessionService;

    @Test
    @WithMockUser(username = "00000000-0000-0000-0000-000000000002", roles = "ADMIN")
    void administratorCanRunBoundedBackfill() throws Exception {
        when(backfill.run(200)).thenReturn(new CallRecordContactBackfillService.BackfillResult(3, 1, 1, 1, 0));

        mvc.perform(post("/api/v1/admin/call-records/contact-backfill"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processed").value(3))
                .andExpect(jsonPath("$.createdContacts").value(1))
                .andExpect(jsonPath("$.skippedUnowned").value(1));

        verify(backfill).run(200);
    }

    @Test
    @WithMockUser(username = "00000000-0000-0000-0000-000000000004", roles = "AGENT")
    void nonAdministratorCannotRunBackfill() throws Exception {
        mvc.perform(post("/api/v1/admin/call-records/contact-backfill?limit=10"))
                .andExpect(status().isForbidden());
    }
}
