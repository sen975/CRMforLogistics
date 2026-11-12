package com.crmforlogistics.messagecenter;

import com.crmforlogistics.messagecenter.service.wecom.WeComInstallationImportService;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppCommandTest {
    @Test
    void parsesTheRequiredImportFileOption() {
        assertThat(AppCommand.importFile(new String[]{
                "wecom-import-install", "--file", "/secure/wecom-install.properties"}))
                .isEqualTo(Path.of("/secure/wecom-install.properties"));
    }

    @Test
    void rejectsMissingOrRepeatedImportFileOption() {
        assertThatThrownBy(() -> AppCommand.importFile(new String[]{"wecom-import-install"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("--file is required exactly once");
        assertThatThrownBy(() -> AppCommand.importFile(new String[]{
                "wecom-import-install", "--file", "/a", "--file", "/b"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("--file is required exactly once");
    }

    @Test
    void rendersOnlyNonSensitiveImportResultFields() {
        var result = new WeComInstallationImportService.ImportResult(
                "created", "installation-1", "suite-1", "ww-corp", "1000247", 1L);

        String json = AppCommand.render(result);

        assertThat(json).contains("\"action\":\"created\"");
        assertThat(json).contains("\"agentId\":\"1000247\"");
        assertThat(json).doesNotContain("permanentCode", "permanent_code");
    }
}
