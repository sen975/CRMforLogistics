package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.WeComException;
import com.crmforlogistics.messagecenter.mapper.WeComSourceParticipantMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WeComContactLinkServiceTest {
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private static WeComSourceParticipantMapper.WeComExternalContactLinkRow row(
            String externalUserId, UUID contactId, boolean accessible) {
        return new WeComSourceParticipantMapper.WeComExternalContactLinkRow(
                externalUserId, UUID.randomUUID(), contactId, accessible);
    }

    @Test
    void keepsTheFirstRowPerExternalUserBecauseTheQueryOrdersAccessibleIdentitiesFirst() {
        WeComSourceParticipantMapper mapper = mock(WeComSourceParticipantMapper.class);
        UUID owned = UUID.randomUUID();
        when(mapper.resolveExternalContactLinks(eq(USER_ID), any()))
                .thenReturn(List.of(row("ext-1", owned, true), row("ext-1", UUID.randomUUID(), false)));

        var links = new WeComContactLinkService(mapper).resolve(USER_ID, List.of("ext-1"));

        assertThat(links).hasSize(1);
        assertThat(links.get(0).externalUserId()).isEqualTo("ext-1");
        assertThat(links.get(0).contactId()).isEqualTo(owned);
        assertThat(links.get(0).accessible()).isTrue();
    }

    @Test
    void resolutionQueryOrdersAccessibleIdentitiesFirstAndScopesToWeComIdentities() throws NoSuchMethodException {
        var select = WeComSourceParticipantMapper.class
                .getMethod("resolveExternalContactLinks", UUID.class, List.class)
                .getAnnotation(org.apache.ibatis.annotations.Select.class);

        assertThat(select).isNotNull();
        assertThat(String.join("", select.value()))
                .contains("ci.channel_type = 'wecom'")
                .contains("contact_accessible desc");
    }

    @Test
    void trimsSplitsAndDeduplicatesTheRequestedIdsBeforeQuerying() {
        WeComSourceParticipantMapper mapper = mock(WeComSourceParticipantMapper.class);
        when(mapper.resolveExternalContactLinks(eq(USER_ID), any())).thenReturn(List.of());

        new WeComContactLinkService(mapper).resolve(USER_ID, List.of(" ext-1 , ext-2 ", "ext-1", "  "));

        verify(mapper).resolveExternalContactLinks(USER_ID, List.of("ext-1", "ext-2"));
    }

    @Test
    void doesNotQueryWithoutAnyUsableId() {
        WeComSourceParticipantMapper mapper = mock(WeComSourceParticipantMapper.class);

        assertThat(new WeComContactLinkService(mapper).resolve(USER_ID, List.of(" ", ""))).isEmpty();

        verifyNoInteractions(mapper);
    }

    @Test
    void reportsExternalContactsWithoutCrmContactAsInaccessible() {
        WeComSourceParticipantMapper mapper = mock(WeComSourceParticipantMapper.class);
        when(mapper.resolveExternalContactLinks(eq(USER_ID), any()))
                .thenReturn(List.of(row("ext-2", null, false)));

        var links = new WeComContactLinkService(mapper).resolve(USER_ID, List.of("ext-2"));

        assertThat(links).hasSize(1);
        assertThat(links.get(0).contactId()).isNull();
        assertThat(links.get(0).accessible()).isFalse();
    }

    @Test
    void rejectsRequestsBeyondTheBoundedIdCount() {
        WeComSourceParticipantMapper mapper = mock(WeComSourceParticipantMapper.class);
        List<String> ids = new ArrayList<>();
        for (int index = 0; index < 101; index++) ids.add("ext-" + index);

        assertThatThrownBy(() -> new WeComContactLinkService(mapper).resolve(USER_ID, ids))
                .isInstanceOf(WeComException.class)
                .hasMessageContaining("100");
        verifyNoInteractions(mapper);
    }
}
