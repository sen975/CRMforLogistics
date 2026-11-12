package com.crmforlogistics.messagecenter.service.whatsapp.template;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class WhatsAppSharedTemplateNoLegacyPathTest {
    private static final Path MAIN = Path.of("src/main/java/com/crmforlogistics/messagecenter");

    @Test
    void removesAccountScopedTemplateManagementPaths() throws Exception {
        assertThat(Files.exists(MAIN.resolve("service/whatsapp/template/WhatsAppTemplatePermissionReconciliationService.java")))
                .isFalse();
        assertThat(Files.exists(MAIN.resolve("service/whatsapp/template/WhatsAppTemplateRemarkService.java")))
                .isFalse();

        String mapper = Files.readString(MAIN.resolve("mapper/TemplateMapper.java"));
        assertThat(mapper)
                .doesNotContain("findForSend(")
                .doesNotContain("findForDisplay(")
                .doesNotContain("findGloballySendable(")
                .doesNotContain("findSendableForChannelAccount(");
    }
}
