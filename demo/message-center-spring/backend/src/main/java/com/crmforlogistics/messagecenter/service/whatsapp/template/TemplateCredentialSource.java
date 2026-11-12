package com.crmforlogistics.messagecenter.service.whatsapp.template;

import java.util.UUID;

/**
 * Whose AccessKey answers a CAMS template call. A CAMS space is a credential holder in its own right —
 * an administrator enters its AccessKey when the space is registered — so the shared library of a space
 * is reached with that space's credentials, which is what lets one space differ from the next. A
 * Business App account keeps its own credentials and reaches its private templates with those.
 */
public sealed interface TemplateCredentialSource {

    static TemplateCredentialSource space(UUID providerScopeId) {
        return new Space(providerScopeId);
    }

    static TemplateCredentialSource account(UUID accountId) {
        return new Account(accountId);
    }

    record Space(UUID providerScopeId) implements TemplateCredentialSource {
    }

    record Account(UUID accountId) implements TemplateCredentialSource {
    }
}
