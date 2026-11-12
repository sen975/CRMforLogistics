package com.crmforlogistics.messagecenter.service.whatsapp.template;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateDisplayNameTest {
    @Test
    void usesRemarkBeforeOfficialName() {
        assertThat(TemplateDisplayName.format("delivery_notice", "发货提醒"))
                .isEqualTo("发货提醒（delivery_notice）");
    }

    @Test
    void fallsBackToOfficialNameWhenRemarkIsBlank() {
        assertThat(TemplateDisplayName.format("delivery_notice", "  "))
                .isEqualTo("delivery_notice");
    }
}
