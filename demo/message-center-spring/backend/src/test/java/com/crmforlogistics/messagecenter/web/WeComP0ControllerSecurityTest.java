package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.channel.wecom.WeComInstallationService;
import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.config.CorsConfig;
import com.crmforlogistics.messagecenter.config.SecurityConfig;
import com.crmforlogistics.messagecenter.service.auth.AuthSessionService;
import com.crmforlogistics.messagecenter.service.wecom.WeComAppChatService;
import com.crmforlogistics.messagecenter.service.wecom.WeComDirectoryService;
import com.crmforlogistics.messagecenter.service.wecom.WeComExternalContactService;
import com.crmforlogistics.messagecenter.service.wecom.WeComProfileBackfillService;
import com.crmforlogistics.messagecenter.service.wecom.WeComUserBindingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = WeComP0Controller.class, properties = {
        "app.wecom-enabled=true",
        "app.wecom-suite-id=test"
})
@Import({SecurityConfig.class, CorsConfig.class, GlobalExceptionHandler.class})
class WeComP0ControllerSecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean WeComInstallationService installations;
    @MockitoBean WeComAppChatService appChats;
    @MockitoBean WeComExternalContactService externalContacts;
    @MockitoBean WeComDirectoryService directory;
    @MockitoBean WeComProfileBackfillService profileBackfill;
    @MockitoBean WeComUserBindingService bindings;
    @MockitoBean AppConfig config;
    @MockitoBean AuthSessionService authSessionService;

    @Test
    void anonymousCannotAccessWeComP0() throws Exception {
        mvc.perform(get("/api/v1/wecom/installations")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "AGENT")
    void nonAdminCannotAccessWeComP0() throws Exception {
        mvc.perform(get("/api/v1/wecom/installations")).andExpect(status().isForbidden());
    }
}
