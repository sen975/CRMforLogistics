package com.crmforlogistics.messagecenter.service.assistant;

import com.crmforlogistics.messagecenter.mapper.AssistantContactCandidateWindowMapper;
import com.crmforlogistics.messagecentertest.assistant.AssistantFixtures;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssistantContactCandidateWindowStoreTest {
    private static final Instant NOW = Instant.parse("2026-09-28T12:00:00Z");
    private final AssistantContactCandidateWindowMapper mapper = mock(AssistantContactCandidateWindowMapper.class);
    private final ContactCandidateProvider provider = mock(ContactCandidateProvider.class);
    private final AssistantContactCandidateWindowStore store = new AssistantContactCandidateWindowStore(
            mapper, provider, AssistantFixtures.objectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void storesOnlyBoundedReferencesAndRefreshesAuthorizationBeforeRestoring() {
        ContactCandidates found = AssistantFixtures.contactCandidates();
        String reference = found.items().get(0).id();
        store.remember(AssistantFixtures.USER, AssistantFixtures.CONVERSATION, found);
        verify(mapper).upsert(eq(AssistantFixtures.USER), eq(AssistantFixtures.CONVERSATION),
                eq("[\"" + reference + "\"]"), eq(NOW), eq(NOW.plusSeconds(1800)));

        when(mapper.findFresh(AssistantFixtures.USER, AssistantFixtures.CONVERSATION, NOW))
                .thenReturn("[\"" + reference + "\"]");
        when(provider.refresh(AssistantFixtures.USER, List.of(reference))).thenReturn(found);
        assertThat(store.restore(AssistantFixtures.USER, AssistantFixtures.CONVERSATION)).isEqualTo(found);
        verify(provider).refresh(AssistantFixtures.USER, List.of(reference));
    }

    @Test
    void expiredOrOtherConversationWindowsCannotSupplyReferences() {
        when(mapper.findFresh(AssistantFixtures.USER, AssistantFixtures.CONVERSATION, NOW)).thenReturn(null);
        assertThat(store.restore(AssistantFixtures.USER, AssistantFixtures.CONVERSATION)).isNull();
        assertThat(store.restore(AssistantFixtures.OTHER_USER, AssistantFixtures.CONVERSATION)).isNull();
        assertThat(store.restore(AssistantFixtures.USER, UUID.randomUUID())).isNull();
        verify(provider, never()).refresh(any(), any());
    }

    @Test
    void malformedOrOversizedStoredReferencesAreRejectedBeforeAuthorizationLookup() {
        when(mapper.findFresh(AssistantFixtures.USER, AssistantFixtures.CONVERSATION, NOW))
                .thenReturn("[\"WECOM_GROUP:11111111-1111-4111-8111-111111111111\"]",
                        "[" + "\"CONTACT:11111111-1111-4111-8111-111111111111\",".repeat(20)
                                + "\"CONTACT:22222222-2222-4222-8222-222222222222\"]");
        assertThat(store.restore(AssistantFixtures.USER, AssistantFixtures.CONVERSATION)).isNull();
        assertThat(store.restore(AssistantFixtures.USER, AssistantFixtures.CONVERSATION)).isNull();
        verify(provider, never()).refresh(any(), any());
    }

    @Test
    void revokedAccessRemovesTheCandidateFromTheNextPrompt() {
        String reference = AssistantFixtures.CONTACT_ZHOU_REF;
        when(mapper.findFresh(AssistantFixtures.USER, AssistantFixtures.CONVERSATION, NOW))
                .thenReturn("[\"" + reference + "\"]");
        when(provider.refresh(AssistantFixtures.USER, List.of(reference)))
                .thenReturn(new ContactCandidates(ContactCandidateProvider.LIMIT, List.of()));
        assertThat(store.restore(AssistantFixtures.USER, AssistantFixtures.CONVERSATION)).isNull();
    }

    @Test
    void windowWithMoreThanTwentyReferencesIsRejected() {
        List<ContactCandidates.Item> items = java.util.stream.IntStream.range(0, 21)
                .mapToObj(i -> new ContactCandidates.Item(ContactCandidates.idOf(new UUID(0, i)), "name", null))
                .toList();
        store.remember(AssistantFixtures.USER, AssistantFixtures.CONVERSATION, new ContactCandidates(21, items));
        verify(mapper, never()).upsert(any(), any(), any(), any(), any());
    }
}
