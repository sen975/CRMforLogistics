package com.crmforlogistics.messagecenter.service.assistant;

import com.crmforlogistics.messagecenter.config.AssistantConfig;
import com.crmforlogistics.messagecenter.mapper.AssistantContactCandidateWindowMapper;
import com.crmforlogistics.messagecenter.mapper.TodoItemMapper;
import com.crmforlogistics.messagecenter.service.assistant.mcp.ToolInputValidator;
import com.crmforlogistics.messagecenter.service.assistant.mcp.ToolRegistry;
import com.crmforlogistics.messagecenter.service.todo.TodoItemService;
import com.crmforlogistics.messagecentertest.assistant.AssistantFixtures;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssistantCrossTurnContactTest {

    private static final UUID CONTACT = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    private static final String REF = ContactCandidates.idOf(CONTACT);

    @Test
    void contactDiscoveredInPreviousRequestCanBeBriefedInTheSameConversation() {
        ContactCandidateProvider contacts = mock(ContactCandidateProvider.class);
        ContactBriefProvider briefs = mock(ContactBriefProvider.class);
        AssistantContextBuilder contextBuilder = mock(AssistantContextBuilder.class);
        AssistantModelClient model = mock(AssistantModelClient.class);
        AssistantConversationLogService log = mock(AssistantConversationLogService.class);
        AssistantContactCandidateWindowMapper windows = mock(AssistantContactCandidateWindowMapper.class);
        AssistantConfig config = AssistantFixtures.config(2);
        ToolRegistry registry = AssistantFixtures.registryWithContacts(
                new TodoItemService(mock(TodoItemMapper.class)), mock(ConversationCandidateProvider.class),
                contacts, briefs);
        AssistantDecisionParser parser = new AssistantDecisionParser(
                registry, new ToolInputValidator(), AssistantFixtures.objectMapper());
        when(contextBuilder.build()).thenAnswer(ignored -> new AssistantContext(
                "Asia/Shanghai", AssistantFixtures.TODAY, "星期一", List.of()));
        ContactCandidates found = new ContactCandidates(20,
                List.of(new ContactCandidates.Item(REF, "守望", null)));
        when(contacts.search(AssistantFixtures.USER, "守望")).thenReturn(found);
        when(contacts.refresh(AssistantFixtures.USER, List.of(REF))).thenReturn(found);
        AtomicReference<String> saved = new AtomicReference<>();
        when(windows.findFresh(any(), any(), any())).thenAnswer(invocation -> saved.get());
        when(windows.upsert(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            saved.set(invocation.getArgument(2));
            return 1;
        });
        when(briefs.brief(AssistantFixtures.USER, CONTACT)).thenReturn(new ContactBrief(
                REF, "守望", null, null, List.of(), true, "CLEAN", null, null,
                List.of(), List.of(), List.of(), List.of()));
        when(model.complete(any(), any(), any())).thenReturn(
                new AssistantModelClient.ModelReply("""
                        {"decision":"call","tool":"contact.search","arguments":{"query":"守望"}}
                        """, "test", 10),
                new AssistantModelClient.ModelReply("{\"decision\":\"reply\",\"reply\":\"找到守望\"}", "test", 10),
                new AssistantModelClient.ModelReply("""
                        {"decision":"call","tool":"contact.brief","arguments":{"contactRef":"%s"}}
                        """.formatted(REF), "test", 10),
                new AssistantModelClient.ModelReply("{\"decision\":\"reply\",\"reply\":\"守望的简报\"}", "test", 10));

        AssistantContactCandidateWindowStore store = new AssistantContactCandidateWindowStore(
                windows, contacts, AssistantFixtures.objectMapper(),
                Clock.fixed(Instant.parse("2026-09-28T12:00:00Z"), ZoneOffset.UTC));

        AssistantConversationService service = new AssistantConversationService(contextBuilder,
                new AssistantPromptBuilder(registry, config, AssistantFixtures.objectMapper()), model, parser,
                new AssistantActionPolicy(), mock(AssistantPendingActionService.class),
                mock(AssistantAuditService.class), log, new AssistantRequestGuard(config), registry, config,
                null, store);

        AssistantTurnResult first = service.respond(AssistantFixtures.USER, AssistantFixtures.CONVERSATION,
                List.of(), "找守望");
        AssistantTurnResult second = service.respond(AssistantFixtures.USER, AssistantFixtures.CONVERSATION,
                List.of(), "守望这个人的简报");

        assertThat(first.kind()).isEqualTo(AssistantTurnResult.Kind.ANSWER);
        assertThat(second.kind()).isEqualTo(AssistantTurnResult.Kind.ANSWER);
        assertThat(second.message()).isEqualTo("守望的简报");
        verify(briefs).brief(AssistantFixtures.USER, CONTACT);
        verify(windows).upsert(any(), any(), any(), any(), any());
        verify(model, times(4)).complete(any(), any(), any());
    }

    @Test
    void newConversationDoesNotSeeAnotherConversationCandidateWindow() {
        AssistantContactCandidateWindowMapper windows = mock(AssistantContactCandidateWindowMapper.class);
        ContactCandidateProvider contacts = mock(ContactCandidateProvider.class);
        AssistantContactCandidateWindowStore store = new AssistantContactCandidateWindowStore(windows, contacts,
                AssistantFixtures.objectMapper(), Clock.fixed(Instant.parse("2026-09-28T12:00:00Z"), ZoneOffset.UTC));
        store.remember(AssistantFixtures.USER, AssistantFixtures.CONVERSATION,
                new ContactCandidates(20, List.of(new ContactCandidates.Item(REF, "守望", null))));

        assertThat(store.restore(AssistantFixtures.USER, UUID.randomUUID())).isNull();
        org.mockito.Mockito.verifyNoInteractions(contacts);
    }
}
