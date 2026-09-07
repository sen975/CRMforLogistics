package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.channel.wecom.WeComInstallationService;
import com.crmforlogistics.messagecenter.channel.wecom.WeComException;
import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.config.CorsConfig;
import com.crmforlogistics.messagecenter.config.SecurityConfig;
import com.crmforlogistics.messagecenter.service.auth.AuthSessionService;
import com.crmforlogistics.messagecenter.service.wecom.WeComApiActor;
import com.crmforlogistics.messagecenter.service.wecom.WeComAppChatService;
import com.crmforlogistics.messagecenter.service.wecom.WeComDirectoryService;
import com.crmforlogistics.messagecenter.service.wecom.WeComExternalContactService;
import com.crmforlogistics.messagecenter.service.wecom.WeComProfileBackfillService;
import com.crmforlogistics.messagecenter.service.wecom.WeComUserBindingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = WeComP0Controller.class, properties = {
        "app.wecom-enabled=true",
        "app.wecom-suite-id=test"
})
@Import({SecurityConfig.class, CorsConfig.class, GlobalExceptionHandler.class})
class WeComP0ControllerTest {
    private static final UUID ADMIN_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean WeComInstallationService installations;
    @MockitoBean WeComAppChatService appChats;
    @MockitoBean WeComExternalContactService externalContacts;
    @MockitoBean WeComDirectoryService directory;
    @MockitoBean WeComProfileBackfillService profileBackfill;
    @MockitoBean WeComUserBindingService bindings;
    @MockitoBean AppConfig config;
    @MockitoBean AuthSessionService authSessionService;

    @Test
    @WithMockUser(username = "00000000-0000-0000-0000-000000000002", roles = "ADMIN")
    void listsOnlyNonSensitiveInstallationSummary() throws Exception {
        when(config.wecomSuiteId()).thenReturn("suite-1");
        when(installations.listInstallationSummaries("suite-1")).thenReturn(List.of(
                new WeComInstallationService.InstallationSummary("corp-1", "示例企业", "100", "ACTIVE",
                        Instant.parse("2026-08-18T00:00:00Z"))));

        mvc.perform(get("/api/v1/wecom/installations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].authCorpId").value("corp-1"))
                .andExpect(jsonPath("$[0].corpName").value("示例企业"))
                .andExpect(jsonPath("$[0].agentId").value("100"))
                .andExpect(jsonPath("$[0].authStatus").value("ACTIVE"))
                .andExpect(jsonPath("$[0].permanentCode").doesNotExist())
                .andExpect(jsonPath("$[0].accessToken").doesNotExist());
    }

    @Test
    @WithMockUser(username = "00000000-0000-0000-0000-000000000002", roles = "ADMIN")
    void mapsAppChatCreateToServiceWithActor() throws Exception {
        mvc.perform(post("/api/v1/wecom/installations/corp-1/app-chats")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Trace-Id", "trace-create")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "运营群", "owner", "owner-1", "userList", List.of("owner-1", "user-2")))))
                .andExpect(status().isOk());

        verify(appChats).create(eq(new WeComAppChatService.CreateCommand(
                "corp-1", null, "运营群", "owner-1", List.of("owner-1", "user-2"))),
                eq(new WeComApiActor(ADMIN_ID, "trace-create")));
    }

    @Test
    @WithMockUser(username = "00000000-0000-0000-0000-000000000002", roles = "ADMIN")
    void mapsDirectoryMemberLookup() throws Exception {
        mvc.perform(get("/api/v1/wecom/installations/corp-1/directory/members/member-1")
                        .header("X-Trace-Id", "trace-member"))
                .andExpect(status().isOk());

        verify(directory).getMember("corp-1", "member-1",
                new WeComApiActor(ADMIN_ID, "trace-member"));
    }

    @Test
    @WithMockUser(username = "00000000-0000-0000-0000-000000000002", roles = "ADMIN")
    void mapsRemainingAppChatEndpoints() throws Exception {
        mvc.perform(get("/api/v1/wecom/installations/corp-1/app-chats/chat-1")
                        .header("X-Trace-Id", "trace-chat-get"))
                .andExpect(status().isOk());
        mvc.perform(patch("/api/v1/wecom/installations/corp-1/app-chats/chat-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Trace-Id", "trace-chat-update")
                        .content("{\"name\":\"新群名\",\"addUsers\":[\"user-3\"],\"removeUsers\":[\"user-2\"]}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/wecom/installations/corp-1/app-chats/chat-1/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Trace-Id", "trace-chat-send")
                        .content("{\"messageType\":\"text\",\"content\":{\"content\":\"你好\"},\"safe\":true}"))
                .andExpect(status().isOk());

        verify(appChats).get("corp-1", "chat-1", new WeComApiActor(ADMIN_ID, "trace-chat-get"));
        verify(appChats).update(eq(new WeComAppChatService.UpdateCommand("corp-1", "chat-1", "新群名",
                null, List.of("user-3"), List.of("user-2"))),
                eq(new WeComApiActor(ADMIN_ID, "trace-chat-update")));
        verify(appChats).send(eq(new WeComAppChatService.SendCommand("corp-1", "chat-1", "text",
                objectMapper.readTree("{\"content\":\"你好\"}"), true)),
                eq(new WeComApiActor(ADMIN_ID, "trace-chat-send")));
    }

    @Test
    @WithMockUser(username = "00000000-0000-0000-0000-000000000002", roles = "ADMIN")
    void mapsExternalContactAndCustomerGroupEndpoints() throws Exception {
        when(bindings.requireByUserId(ADMIN_ID)).thenReturn(new WeComUserBindingService.BoundIdentity(
                ADMIN_ID, "suite-1", "corp-1", "bound-member", "BOUND_EXISTING", null, "admin"));
        mvc.perform(get("/api/v1/wecom/installations/corp-1/external-contacts")
                        .param("userId", "forged-member")
                        .header("X-Trace-Id", "trace-contact-list"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/wecom/installations/corp-1/external-contacts/external-1")
                        .param("cursor", "next-1").header("X-Trace-Id", "trace-contact-get"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/wecom/installations/corp-1/external-contacts:batchGet")
                        .contentType(MediaType.APPLICATION_JSON).header("X-Trace-Id", "trace-contact-batch")
                        .content("{\"userIds\":[\"member-1\"],\"cursor\":\"next-1\",\"limit\":50}"))
                .andExpect(status().isOk());
        mvc.perform(patch("/api/v1/wecom/installations/corp-1/external-contacts/external-1/remark")
                        .param("userId", "member-1").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Trace-Id", "trace-contact-remark")
                        .content("{\"remark\":\"重点客户\",\"remarkMobiles\":[\"13800000000\"]}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/wecom/installations/corp-1/customer-groups:search")
                        .contentType(MediaType.APPLICATION_JSON).header("X-Trace-Id", "trace-group-list")
                        .content("{\"statusFilter\":0,\"ownerFilter\":[\"member-1\"],\"limit\":100}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/wecom/installations/corp-1/customer-groups/group-1")
                        .param("needName", "true").header("X-Trace-Id", "trace-group-get"))
                .andExpect(status().isOk());

        verify(externalContacts).list("corp-1", "bound-member",
                new WeComApiActor(ADMIN_ID, "trace-contact-list"));
        verify(externalContacts).get("corp-1", "external-1", "next-1",
                new WeComApiActor(ADMIN_ID, "trace-contact-get"));
        verify(externalContacts).batchGet(eq(new WeComExternalContactService.BatchGetCommand(
                "corp-1", List.of("member-1"), "next-1", 50)),
                eq(new WeComApiActor(ADMIN_ID, "trace-contact-batch")));
        verify(externalContacts).remark(eq(new WeComExternalContactService.RemarkCommand(
                "corp-1", "member-1", "external-1", "重点客户", null, null,
                List.of("13800000000"))), eq(new WeComApiActor(ADMIN_ID, "trace-contact-remark")));
        verify(externalContacts).groupList(eq(new WeComExternalContactService.GroupListCommand(
                "corp-1", 0, List.of("member-1"), null, 100)),
                eq(new WeComApiActor(ADMIN_ID, "trace-group-list")));
        verify(externalContacts).groupGet("corp-1", "group-1", true,
                new WeComApiActor(ADMIN_ID, "trace-group-get"));
    }

    @Test
    @WithMockUser(username = "00000000-0000-0000-0000-000000000002", roles = "ADMIN")
    void mapsRemainingDirectoryEndpoints() throws Exception {
        mvc.perform(get("/api/v1/wecom/installations/corp-1/directory/members")
                        .param("departmentId", "7").param("fetchChild", "true")
                        .header("X-Trace-Id", "trace-members"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/wecom/installations/corp-1/directory/departments")
                        .param("departmentId", "7").header("X-Trace-Id", "trace-departments"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/wecom/installations/corp-1/directory/tags")
                        .header("X-Trace-Id", "trace-tags"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/wecom/installations/corp-1/directory/tags/9")
                        .header("X-Trace-Id", "trace-tag"))
                .andExpect(status().isOk());

        verify(directory).listMembers("corp-1", 7, true,
                new WeComApiActor(ADMIN_ID, "trace-members"));
        verify(directory).listDepartments("corp-1", 7L,
                new WeComApiActor(ADMIN_ID, "trace-departments"));
        verify(directory).listTags("corp-1", new WeComApiActor(ADMIN_ID, "trace-tags"));
        verify(directory).getTag("corp-1", 9, new WeComApiActor(ADMIN_ID, "trace-tag"));
    }

    @Test
    @WithMockUser(username = "00000000-0000-0000-0000-000000000002", roles = "ADMIN")
    void adminCanTriggerBoundedProfileBackfill() throws Exception {
        when(profileBackfill.backfill("corp-1", 100)).thenReturn(
                new WeComProfileBackfillService.BackfillResult(2, 1, 1, 0));

        mvc.perform(post("/api/v1/wecom/installations/corp-1/profile-backfill")
                        .param("limit", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partiesAttempted").value(2))
                .andExpect(jsonPath("$.groupsNamed").value(1));

        verify(profileBackfill).backfill("corp-1", 100);
    }

    @Test
    @WithMockUser(username = "00000000-0000-0000-0000-000000000002", roles = "AGENT")
    void nonAdminCannotTriggerProfileBackfill() throws Exception {
        mvc.perform(post("/api/v1/wecom/installations/corp-1/profile-backfill"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "00000000-0000-0000-0000-000000000002", roles = "ADMIN")
    void mapsWeComErrorsWithoutLeakingProviderBody() throws Exception {
        when(directory.listTags(eq("corp-1"), any())).thenThrow(new WeComException(
                "WECOM_API_PERMISSION_DENIED", 403, "企业微信应用缺少所需权限",
                48002, "/cgi-bin/tag/list", 200, "safe-hint"));

        mvc.perform(get("/api/v1/wecom/installations/corp-1/directory/tags")
                        .header("X-Trace-Id", "trace-error"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WECOM_API_PERMISSION_DENIED"))
                .andExpect(jsonPath("$.message").value("企业微信应用缺少所需权限"))
                .andExpect(jsonPath("$.upstreamErrcode").doesNotExist())
                .andExpect(jsonPath("$.upstreamHint").doesNotExist());
    }

    @Test
    @WithMockUser(username = "00000000-0000-0000-0000-000000000002", roles = "ADMIN")
    void rejectsUnknownRequestFields() throws Exception {
        mvc.perform(post("/api/v1/wecom/installations/corp-1/app-chats")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"运营群\",\"owner\":\"owner-1\",\"userList\":[\"owner-1\"],\"token\":\"secret\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "00000000-0000-0000-0000-000000000002", roles = "ADMIN")
    void rejectsRequestBodyAbove256KiB() throws Exception {
        String oversized = "{\"name\":\"" + "x".repeat(270_000) + "\"}";
        mvc.perform(post("/api/v1/wecom/installations/corp-1/app-chats")
                        .contentType(MediaType.APPLICATION_JSON).content(oversized))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("WECOM_REQUEST_TOO_LARGE"));
    }
}
