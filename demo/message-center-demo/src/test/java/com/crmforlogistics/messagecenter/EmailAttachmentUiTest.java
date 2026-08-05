package com.crmforlogistics.messagecenter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class EmailAttachmentUiTest {
    @Test
    void generatedPageKeepsTheMultiAttachmentComposerContract() throws Exception {
        Path directory = Files.createTempDirectory("email-attachment-ui");
        Path html = directory.resolve("page.html");
        Files.writeString(html, App.pageHtml(), StandardCharsets.UTF_8);
        Process process = new ProcessBuilder("node", "src/test/resources/email-attachment-ui-probe.mjs", html.toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(10, TimeUnit.SECONDS), "email attachment UI probe timed out");
        assertEquals(0, process.exitValue(), output);
    }
}
