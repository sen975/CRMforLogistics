package com.crmforlogistics.messagecenter.channel.email;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenSslImapClientTest {
    @TempDir Path tempDir;

    @Test
    void rejectsOversizedLiteralBeforeAllocatingItsPayload() {
        byte[] response = "* 1 FETCH (RFC822 {26214401}\r\n".getBytes(StandardCharsets.US_ASCII);

        assertThatThrownBy(() -> OpenSslImapClient.extractLiterals(
                new ByteArrayInputStream(response), "A004"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("size limit");
    }

    @Test
    void totalDeadlineDestroysAProcessBlockedWaitingForItsGreeting() throws Exception {
        Path fakeOpenSsl = tempDir.resolve("fake-openssl");
        Files.writeString(fakeOpenSsl, "#!/bin/sh\nexec sleep 10\n");
        Files.setPosixFilePermissions(fakeOpenSsl,
                EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE));
        EmailSyncSettings settings = new EmailSyncSettings("imap.example.test", "993", "u", "p",
                true, 10, "139", true, fakeOpenSsl.toString(), "INBOX", "Sent");
        OpenSslImapClient client = new OpenSslImapClient(settings, Duration.ofMillis(100));
        long started = System.nanoTime();

        assertThatThrownBy(() -> client.fetchLatest("INBOX", 1))
                .isInstanceOf(EmailException.class)
                .extracting(error -> ((EmailException) error).code())
                .isEqualTo("EMAIL_IMAP_TIMEOUT");

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
    }
}
