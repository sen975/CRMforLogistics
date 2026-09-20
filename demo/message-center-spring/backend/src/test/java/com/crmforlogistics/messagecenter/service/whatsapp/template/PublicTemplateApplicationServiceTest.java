package com.crmforlogistics.messagecenter.service.whatsapp.template;

import com.crmforlogistics.messagecenter.service.whatsapp.template.ChatAppPublicTemplateGateway;
import com.crmforlogistics.messagecenter.entity.WhatsAppProviderScopeEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static com.crmforlogistics.messagecenter.service.whatsapp.template.PublicTemplateModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PublicTemplateApplicationServiceTest {
    @Mock WhatsAppProviderScopeService providerScopeService;
    @Mock ChatAppPublicTemplateGateway gateway;

    private PublicTemplateApplicationService service;
    private final UUID providerScopeId = UUID.randomUUID();
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-14T00:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() throws ReflectiveOperationException {
        Constructor<?> constructor = Arrays.stream(PublicTemplateApplicationService.class.getConstructors())
                .findFirst()
                .orElseThrow();
        Object[] arguments = Arrays.stream(constructor.getParameterTypes())
                .map(this::dependencyFor)
                .toArray();
        service = (PublicTemplateApplicationService) constructor.newInstance(arguments);
    }

    private Object dependencyFor(Class<?> dependencyType) {
        if (dependencyType == ChatAppPublicTemplateGateway.class) return gateway;
        if (dependencyType == Clock.class) return clock;
        if (dependencyType == WhatsAppProviderScopeService.class) return providerScopeService;
        return mock(dependencyType);
    }

    @Test
    void listsProjectedTemplatesForTheNamedSpace() {
        Query query = new Query("", "zh_CN", "UTILITY", List.of(), List.of(), 1, 20);
        Page providerPage = new Page(List.of(new PublicTemplate("code-1", "name-1", "zh_CN", "UTILITY",
                List.of(), "ORDER_MANAGEMENT", "ORDER", new Content("name-1", "name-1", "external-1",
                "zh_CN", "UTILITY", List.of(new MessagePage("page1", "Hello $(name)", List.of(
                        new Button("Open", "visitWebsite", "https://example.com")))), List.of()))), 1, 1, 20);
        when(providerScopeService.requireScope(providerScopeId)).thenReturn(space("cams-1"));
        when(gateway.list(TemplateCredentialSource.space(providerScopeId), query)).thenReturn(providerPage);

        Page result = service.list(providerScopeId, query);

        verify(providerScopeService).requireScope(providerScopeId);
        verify(gateway).list(TemplateCredentialSource.space(providerScopeId), query);
        assertThat(result).isEqualTo(providerPage);
    }

    @Test
    void sharesPublicTemplateListCacheAcrossSpacesRegisteredInTheSameCustSpace() {
        Query query = new Query(null, "zh_CN", null, List.of(), List.of(), 1, 20);
        Page providerPage = new Page(List.of(), 1, 0, 20);
        UUID secondScopeId = UUID.randomUUID();
        when(providerScopeService.requireScope(any())).thenReturn(space("cams-1"));
        when(gateway.list(TemplateCredentialSource.space(providerScopeId), query)).thenReturn(providerPage);

        assertThat(service.list(providerScopeId, query)).isEqualTo(providerPage);
        assertThat(service.list(secondScopeId, query)).isEqualTo(providerPage);

        verify(providerScopeService).requireScope(providerScopeId);
        verify(providerScopeService).requireScope(secondScopeId);
        verify(gateway).list(TemplateCredentialSource.space(providerScopeId), query);
        verify(gateway, never()).list(TemplateCredentialSource.space(secondScopeId), query);
    }

    /** Each CAMS space has its own catalogue, so a cached page must never answer for another space. */
    @Test
    void neverAnswersAcrossCustSpaces() {
        Query query = new Query(null, "zh_CN", null, List.of(), List.of(), 1, 20);
        UUID otherScopeId = UUID.randomUUID();
        when(providerScopeService.requireScope(providerScopeId)).thenReturn(space("cams-1"));
        when(providerScopeService.requireScope(otherScopeId)).thenReturn(space("cams-2"));
        when(gateway.list(TemplateCredentialSource.space(providerScopeId), query))
                .thenReturn(new Page(List.of(), 1, 0, 20));
        when(gateway.list(TemplateCredentialSource.space(otherScopeId), query))
                .thenReturn(new Page(List.of(), 1, 0, 20));

        service.list(providerScopeId, query);
        service.list(otherScopeId, query);

        verify(gateway).list(TemplateCredentialSource.space(providerScopeId), query);
        verify(gateway).list(TemplateCredentialSource.space(otherScopeId), query);
    }

    private static WhatsAppProviderScopeEntity space(String custSpaceId) {
        WhatsAppProviderScopeEntity scope = new WhatsAppProviderScopeEntity();
        scope.setId(UUID.randomUUID());
        scope.setExternalScopeId(custSpaceId);
        return scope;
    }

    @Test
    void rejectsInvalidPaginationBeforeCallingProvider() {
        Query query = new Query("", "zh_CN", "UTILITY", List.of(), List.of(), 0, 201);

        assertThatThrownBy(() -> service.list(providerScopeId, query))
                .isInstanceOf(WhatsAppTemplateException.class)
                .extracting("code").isEqualTo("PUBLIC_TEMPLATE_QUERY_INVALID");
        verifyNoInteractions(gateway);
    }

    @Test
    void exposesNoPublicCopyOperation() {
        assertThat(Arrays.stream(PublicTemplateApplicationService.class.getDeclaredMethods())
                .filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers()))
                .map(Method::getName))
                .doesNotContain("copy");
    }

    @Test
    void runtimeOperationTypesRetireCopy() {
        assertThat(Arrays.stream(WhatsAppTemplateModels.OperationType.values()).map(Enum::name))
                .contains("RETIRED")
                .doesNotContain("COPY");
    }

    @Test
    void constructorContainsOnlyPublicTemplateListDependencies() {
        List<Constructor<?>> constructors = Arrays.asList(PublicTemplateApplicationService.class.getConstructors());

        assertThat(constructors).singleElement().satisfies(constructor ->
                assertThat(constructor.getParameterTypes()).containsExactly(
                        ChatAppPublicTemplateGateway.class,
                        Clock.class,
                        WhatsAppProviderScopeService.class));
    }

    @Test
    void rejectsOverlongIndustryAndUsecaseValuesBeforeCallingProvider() {
        Query overlongIndustry = new Query(null, "zh_CN", null, List.of("x".repeat(121)), List.of(), 1, 20);
        Query overlongUsecase = new Query(null, "zh_CN", null, List.of(), List.of("x".repeat(121)), 1, 20);

        assertThatThrownBy(() -> service.list(providerScopeId, overlongIndustry))
                .isInstanceOf(WhatsAppTemplateException.class)
                .extracting("code").isEqualTo("PUBLIC_TEMPLATE_QUERY_INVALID");
        assertThatThrownBy(() -> service.list(providerScopeId, overlongUsecase))
                .isInstanceOf(WhatsAppTemplateException.class)
                .extracting("code").isEqualTo("PUBLIC_TEMPLATE_QUERY_INVALID");

        verifyNoInteractions(gateway);
    }

    @Test
    void rejectsInvalidScalarListFiltersWithStableQueryError() {
        List<Query> invalidQueries = List.of(
                new Query(null, "   ", null, List.of(), List.of(), 1, 20),
                new Query(null, "x".repeat(25), null, List.of(), List.of(), 1, 20),
                new Query("x".repeat(121), "zh_CN", null, List.of(), List.of(), 1, 20),
                new Query(null, "zh_CN", "x".repeat(41), List.of(), List.of(), 1, 20));

        for (Query query : invalidQueries) {
            assertThatThrownBy(() -> service.list(providerScopeId, query))
                    .isInstanceOf(WhatsAppTemplateException.class)
                    .extracting("code").isEqualTo("PUBLIC_TEMPLATE_QUERY_INVALID");
        }
        verifyNoInteractions(gateway);
    }

}
