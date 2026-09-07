package com.crmforlogistics.messagecenter.service.chatapp;

import com.crmforlogistics.messagecenter.dto.response.TemplateResponse;
import com.crmforlogistics.messagecenter.entity.TemplateEntity;
import com.crmforlogistics.messagecenter.mapper.TemplateMapper;
import com.crmforlogistics.messagecenter.service.message.TemplateMessageTextResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatAppTemplateServiceTest {

    @Mock TemplateMapper templateMapper;
    @Mock TemplateMessageTextResolver templateTextResolver;

    @Test
    void selectorReturnsOnlyTheMapperApprovedAndSendableVersions() {
        UUID fixedAccountId = UUID.randomUUID();
        TemplateEntity approved = template("delivery_ready", "APPROVED", true);
        approved.setChannelAccountId(fixedAccountId);
        approved.setName("delivery_notice");
        approved.setRemark("发货提醒");
        TemplateEntity rejected = template("delivery_rejected", "REJECTED", true);
        TemplateEntity pending = template("delivery_pending", "PENDING", true);
        TemplateEntity suspended = template("delivery_suspended", "SUSPENDED", true);
        TemplateEntity paused = template("delivery_paused", "APPROVED", false);
        TemplateEntity deleted = template("delivery_deleted", "APPROVED", true);
        deleted.setDeletedAt(java.time.Instant.now());
        TemplateEntity wrongAccount = template("delivery_wrong_account", "APPROVED", true);
        wrongAccount.setChannelAccountId(UUID.randomUUID());

        when(templateMapper.findGloballySendable()).thenReturn(List.of(
                approved, rejected, pending, suspended, paused, deleted, wrongAccount));
        approved.setCategory("UTILITY");
        approved.setComponentsJsonb("[{\"type\":\"HEADER\",\"headerFormat\":\"TEXT\","
                + "\"text\":\"Order $(orderNo)\"},{\"type\":\"BODY\","
                + "\"text\":\"Hello $(customer)\"}]");
        approved.setExamplesJsonb("{\"orderNo\":[\"A-17\"],\"customer\":[\"Alice\"]}");

        ChatAppTemplateService service = new ChatAppTemplateService(
                templateMapper, templateTextResolver, new ObjectMapper());

        List<TemplateResponse> response = service.listAll();

        assertThat(response).extracting(TemplateResponse::templateCode)
                .containsExactly("delivery_ready", "delivery_wrong_account");
        assertThat(response.get(0).templateName()).isEqualTo("delivery_notice");
        assertThat(response.get(0).displayName()).isEqualTo("发货提醒（delivery_notice）");
        assertThat(response.get(0).category()).isEqualTo("UTILITY");
        assertThat(response.get(0).components().toString()).contains("BODY");
        assertThat(response.get(0).placeholders()).containsExactly("orderNo", "customer");
        assertThat(response.get(0).variableDefinitions())
                .containsEntry("orderNo", List.of("A-17"))
                .containsEntry("customer", List.of("Alice"));
        verify(templateMapper).findGloballySendable();
        verify(templateMapper, never()).selectList(any());
    }

    private static TemplateEntity template(String code, String status, boolean allowSend) {
        TemplateEntity template = new TemplateEntity();
        template.setProviderTemplateId(code);
        template.setName(code);
        template.setLanguageCode("en_US");
        template.setBody("Hello $(customer)");
        template.setStatus(status);
        template.setAllowSend(allowSend);
        return template;
    }
}
