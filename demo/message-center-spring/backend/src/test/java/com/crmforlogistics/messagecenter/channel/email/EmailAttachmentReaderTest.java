package com.crmforlogistics.messagecenter.channel.email;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EmailAttachmentReaderTest {

    @Test
    void readsInOrderAndClassifiesBoundedPayloads() {
        var reader = new EmailAttachmentReader(16, 20_971_520);
        var result = reader.read(List.of(
                new EmailAttachmentInput("../照片.png", "image/png", 0,
                        () -> new ByteArrayInputStream("png".getBytes(StandardCharsets.UTF_8))),
                new EmailAttachmentInput("voice.mp3", "audio/mpeg", 4,
                        () -> new ByteArrayInputStream("voice".getBytes(StandardCharsets.UTF_8)))
        ));

        assertEquals(List.of("照片.png", "voice.mp3"), result.stream()
                .map(EmailAttachmentPayload::fileName).toList());
        assertEquals("image", result.get(0).mediaKind());
        assertEquals("audio", result.get(1).mediaKind());
        assertEquals(3, result.get(0).bytes().length);
        assertEquals(5, result.get(1).bytes().length);
        assertEquals(64, result.get(0).sha256().length());
    }

    @Test
    void rejectsCountBeforeOpeningTheSeventeenthStream() {
        var opened = new int[1];
        var inputs = java.util.stream.IntStream.range(0, 17)
                .mapToObj(i -> new EmailAttachmentInput("f" + i, "text/plain", 1,
                        () -> { opened[0]++; return new ByteArrayInputStream(new byte[]{1}); }))
                .toList();

        var error = assertThrows(EmailException.class,
                () -> new EmailAttachmentReader(16, 20_971_520).read(inputs));

        assertEquals("EMAIL_ATTACHMENT_COUNT_LIMIT", error.code());
        assertEquals(0, opened[0]);
    }

    @Test
    void rejectsActualSizeBeyondBudget() {
        var error = assertThrows(EmailException.class,
                () -> new EmailAttachmentReader(16, 3).read(List.of(
                        new EmailAttachmentInput("large.bin", "application/octet-stream", 1,
                                () -> new ByteArrayInputStream(new byte[]{1, 2, 3, 4}))
                )));

        assertEquals("EMAIL_ATTACHMENT_SIZE_LIMIT", error.code());
    }

    @Test
    void keepsReadableAttachmentsWhenOneInputFails() {
        var result = new EmailAttachmentReader(16, 20_971_520).readAvailable(List.of(
                new EmailAttachmentInput("ok.txt", "text/plain", 2,
                        () -> new ByteArrayInputStream(new byte[]{1, 2})),
                new EmailAttachmentInput("broken.txt", "text/plain", 0,
                        () -> { throw new java.io.IOException("broken"); }),
                new EmailAttachmentInput("later.txt", "text/plain", 2,
                        () -> new ByteArrayInputStream(new byte[]{3, 4}))
        ));

        assertEquals(List.of("ok.txt", "later.txt"), result.stream()
                .map(EmailAttachmentPayload::fileName).toList());
    }

    @Test
    void decodesMimeEncodedFileName() {
        assertEquals("截屏2026-07-13 下午3.49.15.png", EmailAttachmentReader.normalizeFileName(
                "=?UTF-8?Q?=E6=88=AA=E5=B1=8F2026-07-13_=E4=B8=8B=E5=8D=883.49.15.png?="));
    }
}
