package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WeComChannelAccountLifecycleTest {

    @Test
    void authorizationEnsuresAnActiveAccountForTheAuthorizedCorp() {
        ChannelAccountMapper mapper = mock(ChannelAccountMapper.class);
        when(mapper.upsertWeComAccount(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq("ww-corp"),
                org.mockito.ArgumentMatchers.eq("企业微信"))).thenReturn(1);
        WeComChannelAccountLifecycle lifecycle = new WeComChannelAccountLifecycle(mapper);

        lifecycle.ensureActive("ww-corp");

        ArgumentCaptor<UUID> id = ArgumentCaptor.forClass(UUID.class);
        verify(mapper).upsertWeComAccount(id.capture(),
                org.mockito.ArgumentMatchers.eq("ww-corp"),
                org.mockito.ArgumentMatchers.eq("企业微信"));
        assertThat(id.getValue()).isNotNull();
    }

    @Test
    void cancellationDisablesOnlyTheMatchingCorpAccount() {
        ChannelAccountMapper mapper = mock(ChannelAccountMapper.class);
        when(mapper.disableWeComAccount("ww-corp")).thenReturn(1);
        WeComChannelAccountLifecycle lifecycle = new WeComChannelAccountLifecycle(mapper);

        lifecycle.disable("ww-corp");

        verify(mapper).disableWeComAccount("ww-corp");
    }

    @Test
    void projectionResolvesTheAccountByAuthorizedCorp() {
        ChannelAccountMapper mapper = mock(ChannelAccountMapper.class);
        ChannelAccountEntity expected = new ChannelAccountEntity();
        expected.setId(UUID.randomUUID());
        when(mapper.selectActiveWeComAccount("ww-corp")).thenReturn(expected);
        WeComChannelAccountLifecycle lifecycle = new WeComChannelAccountLifecycle(mapper);

        assertThat(lifecycle.requireActive("ww-corp")).isSameAs(expected);
    }
}
