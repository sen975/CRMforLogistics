package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.entity.ContactAiLabelEntity;
import com.crmforlogistics.messagecenter.entity.ContactEntity;
import com.crmforlogistics.messagecenter.entity.ContactMemoryAttemptEntity;
import com.crmforlogistics.messagecenter.entity.ContactMemoryStateEntity;
import com.crmforlogistics.messagecenter.entity.ContactProfileVersionEntity;
import com.crmforlogistics.messagecenter.mapper.ContactMapper;
import com.crmforlogistics.messagecenter.mapper.ContactMemoryMapper;
import com.crmforlogistics.messagecenter.mapper.ContactMemoryStateMapper;
import com.crmforlogistics.messagecenter.mapper.ContactTagMapper;
import com.crmforlogistics.messagecenter.dto.response.ContactTagResponse;
import com.crmforlogistics.messagecenter.service.contactmemory.ContactMemoryQueryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ContactMemoryController.class)
@Import(ContactMemoryQueryService.class)
@AutoConfigureMockMvc(addFilters = false)
class ContactMemoryControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean ContactMapper contactMapper;
    @MockitoBean ContactMemoryMapper memoryMapper;
    @MockitoBean ContactMemoryStateMapper stateMapper;
    @MockitoBean ContactTagMapper tagMapper;

    private UUID userId;

    @BeforeEach
    void authenticate() {
        userId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), "", List.of()));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void memoryEndpointRejectsContactOwnedByAnotherUser() throws Exception {
        UUID contactId = UUID.randomUUID();
        when(contactMapper.findByIdAndOwner(contactId, userId)).thenReturn(Optional.empty());

        mvc.perform(get("/api/contacts/{contactId}/memory", contactId))
                .andExpect(status().isNotFound());
    }

    @Test
    void memoryEndpointKeepsManualTagsSeparateAndHidesInactiveAiLabels() throws Exception {
        UUID contactId = UUID.randomUUID();
        Instant successAt = Instant.parse("2026-09-11T00:00:00Z");
        ContactEntity contact = new ContactEntity();
        contact.setId(contactId);
        contact.setCreatedBy(userId);
        when(contactMapper.findByIdAndOwner(contactId, userId)).thenReturn(Optional.of(contact));

        ContactMemoryStateEntity state = new ContactMemoryStateEntity();
        state.setStatus("DIRTY");
        state.setLastFailureCode("LLM_TIMEOUT");
        state.setLastFailureMessage("provider secret must not be returned");
        state.setLastInboundAt(Instant.parse("2026-09-11T01:00:00Z"));
        when(stateMapper.findByOwnerAndContact(userId, contactId)).thenReturn(Optional.of(state));

        ContactProfileVersionEntity profile = new ContactProfileVersionEntity();
        profile.setId(UUID.randomUUID());
        profile.setVersion(2L);
        profile.setContent("客户关注冷链报价，沟通偏好简洁，当前处于评估阶段。");
        profile.setCreatedAt(successAt);
        when(memoryMapper.findCurrentProfile(userId, contactId)).thenReturn(profile);

        ContactAiLabelEntity stale = aiLabel("PRODUCT_INTEREST", "冷链", "green", "STALE");
        ContactAiLabelEntity inactive = aiLabel("RISK", "价格敏感", "red", "INACTIVE");
        stale.setLastSeenAt(successAt);
        when(memoryMapper.listVisibleLabelsPage(userId, contactId, null, null, 101))
                .thenReturn(List.of(stale));

        ContactMemoryAttemptEntity attempt = new ContactMemoryAttemptEntity();
        attempt.setCompletedAt(successAt);
        when(memoryMapper.findLatestSuccessfulAttempt(userId, contactId)).thenReturn(attempt);
        when(tagMapper.findActiveByContactIdAndOwner(contactId, userId)).thenReturn(List.of(
                new ContactTagResponse(UUID.randomUUID(), "重点跟进", "blue")));

        mvc.perform(get("/api/contacts/{contactId}/memory", contactId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profile.content").value(profile.getContent()))
                .andExpect(jsonPath("$.humanTags[0].name").value("重点跟进"))
                .andExpect(jsonPath("$.aiTags.length()").value(1))
                .andExpect(jsonPath("$.aiTags[0].name").value("冷链"))
                .andExpect(jsonPath("$.aiTags[0].colorToken").value("green"))
                .andExpect(jsonPath("$.aiTags[0].status").value("STALE"))
                .andExpect(jsonPath("$.state").value("DIRTY"))
                .andExpect(jsonPath("$.pendingInbound").value(true))
                .andExpect(jsonPath("$.lastFailureCode").value("LLM_TIMEOUT"))
                .andExpect(jsonPath("$.lastFailureMessage").doesNotExist())
                .andExpect(jsonPath("$.lastSuccessAt").value(successAt.toString()));
    }

    @Test
    void aiLabelsArePagedWithoutChangingUnlimitedStorageSemantics() throws Exception {
        UUID contactId = UUID.randomUUID();
        ContactEntity contact = new ContactEntity();
        contact.setId(contactId);
        contact.setCreatedBy(userId);
        when(contactMapper.findByIdAndOwner(contactId, userId)).thenReturn(Optional.of(contact));

        ContactAiLabelEntity first = aiLabel("PRODUCT_INTEREST", "海运", "green", "ACTIVE");
        first.setLastSeenAt(Instant.parse("2026-09-12T00:00:00Z"));
        ContactAiLabelEntity second = aiLabel("NEED", "时效", "blue", "STALE");
        second.setLastSeenAt(Instant.parse("2026-09-11T00:00:00Z"));
        when(memoryMapper.listVisibleLabelsPage(eq(userId), eq(contactId), isNull(), isNull(), eq(2)))
                .thenReturn(List.of(first, second));

        mvc.perform(get("/api/contacts/{contactId}/memory", contactId)
                        .param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiTags.length()").value(1))
                .andExpect(jsonPath("$.aiTags[0].name").value("海运"))
                .andExpect(jsonPath("$.aiTagsHasMore").value(true))
                .andExpect(jsonPath("$.aiTagsNextCursor").isNotEmpty());
    }

    @Test
    void invalidAiLabelCursorIsRejected() throws Exception {
        UUID contactId = UUID.randomUUID();
        ContactEntity contact = new ContactEntity();
        contact.setId(contactId);
        contact.setCreatedBy(userId);
        when(contactMapper.findByIdAndOwner(contactId, userId)).thenReturn(Optional.of(contact));

        mvc.perform(get("/api/contacts/{contactId}/memory", contactId)
                        .param("cursor", "not-a-cursor"))
                .andExpect(status().isBadRequest());
    }

    private static ContactAiLabelEntity aiLabel(String category, String name,
                                                  String colorToken, String status) {
        ContactAiLabelEntity label = new ContactAiLabelEntity();
        label.setId(UUID.randomUUID());
        label.setCategory(category);
        label.setNormalizedName(name);
        label.setDisplayName(name);
        label.setColorToken(colorToken);
        label.setStatus(status);
        label.setConfidence(BigDecimal.valueOf(0.8));
        return label;
    }
}
