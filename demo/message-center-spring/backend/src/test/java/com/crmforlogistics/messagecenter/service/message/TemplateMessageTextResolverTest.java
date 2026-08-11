package com.crmforlogistics.messagecenter.service.message;

import com.crmforlogistics.messagecenter.entity.MessageEntity;
import com.crmforlogistics.messagecenter.entity.TemplateEntity;
import com.crmforlogistics.messagecenter.mapper.TemplateMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.List;
import java.time.Instant;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TemplateMessageTextResolverTest {

    @Mock TemplateMapper templateMapper;

    @Test
    void restoresHistoricalTemplateBodyFromMetadata() throws Exception {
        UUID accountId = UUID.randomUUID();
        MessageEntity message = new MessageEntity();
        message.setMessageKind("template");
        message.setChannelAccountId(accountId);
        message.setBodyText("[template]");
        message.setMetadataJsonb(new ObjectMapper().writeValueAsString(Map.of(
                "templateCode", "order_ready",
                "languageCode", "en_US",
                "templateParams", Map.of("customer", "Alice", "orderNo", "A-17"))));

        TemplateEntity template = new TemplateEntity();
        template.setBody("Hello {{customer}}, your order $(orderNo) is ready.");
        template.setStatus("REJECTED");
        when(templateMapper.findForDisplay(eq(accountId), eq("order_ready"), eq("en_US")))
                .thenReturn(Optional.of(template));

        TemplateMessageTextResolver resolver = new TemplateMessageTextResolver(
                templateMapper, new ObjectMapper());

        assertThat(resolver.resolve(message))
                .isEqualTo("Hello Alice, your order A-17 is ready.");
    }

    @Test
    void keepsStoredBodyWhenHistoricalTemplateCannotBeResolved() throws Exception {
        UUID accountId = UUID.randomUUID();
        MessageEntity message = new MessageEntity();
        message.setMessageKind("template");
        message.setChannelAccountId(accountId);
        message.setBodyText("[template] legacy");
        message.setMetadataJsonb(new ObjectMapper().writeValueAsString(Map.of(
                "templateCode", "missing_template", "languageCode", "en_US")));
        when(templateMapper.findForDisplay(eq(accountId), eq("missing_template"), eq("en_US")))
                .thenReturn(Optional.empty());

        TemplateMessageTextResolver resolver = new TemplateMessageTextResolver(
                templateMapper, new ObjectMapper());

        assertThat(resolver.resolve(message)).isEqualTo("[template] legacy");
    }

    @Test
    void extractsTheSamePlaceholdersUsedByRendering() {
        TemplateMessageTextResolver resolver = new TemplateMessageTextResolver(
                templateMapper, new ObjectMapper());

        assertThat(resolver.placeholders("Hi {{customer}}, order $(orderNo), owner (sales)."))
                .isEqualTo(List.of("customer", "orderNo", "sales"));
    }

    @ParameterizedTest
    @MethodSource("notSendableTemplates")
    void rejectsTemplateThatDoesNotSatisfyTheSendContract(TemplateState state) {
        UUID accountId = UUID.randomUUID();
        TemplateEntity template = new TemplateEntity();
        template.setBody("Hello {{customer}}");
        template.setStatus(state.status());
        template.setAllowSend(state.allowSend());
        template.setDeletedAt(state.deleted() ? Instant.now() : null);
        template.setChannelAccountId(state.wrongAccount() ? UUID.randomUUID() : accountId);
        when(templateMapper.findForSend(eq(accountId), eq("order_ready"), eq("en_US")))
                .thenReturn(Optional.of(template));
        TemplateMessageTextResolver resolver = new TemplateMessageTextResolver(
                templateMapper, new ObjectMapper());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> resolver.renderForSend(
                accountId, "order_ready", "en_US", Map.of("customer", "Alice")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("CHATAPP_TEMPLATE_NOT_SYNCED");
    }

    @Test
    void rejectsMissingOrExtraTemplateParametersBeforeSending() {
        UUID accountId = UUID.randomUUID();
        TemplateEntity template = new TemplateEntity();
        template.setChannelAccountId(accountId);
        template.setBody("Hello {{customer}}, order {{orderNo}} is ready.");
        template.setExamplesJsonb("{\"customer\":[\"Alice\"],\"orderNo\":[\"A-17\"]}");
        template.setStatus("APPROVED");
        template.setAllowSend(true);
        when(templateMapper.findForSend(accountId, "order_ready", "en_US"))
                .thenReturn(Optional.of(template));
        TemplateMessageTextResolver resolver = new TemplateMessageTextResolver(templateMapper, new ObjectMapper());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> resolver.renderForSend(
                accountId, "order_ready", "en_US", Map.of("customer", "Alice")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("CHATAPP_TEMPLATE_PARAMETERS_INVALID");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> resolver.renderForSend(
                accountId, "order_ready", "en_US",
                Map.of("customer", "Alice", "orderNo", "A-17", "extra", "value")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("CHATAPP_TEMPLATE_PARAMETERS_INVALID");
    }

    private static Stream<TemplateState> notSendableTemplates() {
        return Stream.of(
                new TemplateState("REJECTED", true, false, false),
                new TemplateState("PENDING", true, false, false),
                new TemplateState("SUSPENDED", true, false, false),
                new TemplateState("APPROVED", false, false, false),
                new TemplateState("APPROVED", true, true, false),
                new TemplateState("APPROVED", true, false, true));
    }

    private record TemplateState(String status, boolean allowSend, boolean deleted, boolean wrongAccount) {}
}
