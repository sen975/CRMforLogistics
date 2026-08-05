package com.crmforlogistics.messagecenter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class EmailAttachmentStoreTest {
    @TempDir Path tempDir;

    @Test
    void publishesFilesInsideMessageRootAndComputesHash() throws Exception {
        Config config = new Config(Map.of("EMAIL_DATA_DIR", tempDir.toString()));
        EmailAttachmentStore store = new EmailAttachmentStore(config);
        StagedAttachment staged = store.stage(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)),
                "../escape.txt", "text/plain", new AttachmentBudget(16, 1024, 10240));
        List<EmailAttachment> published = store.publish("message-1", List.of(staged));
        assertEquals(1, published.size());
        EmailAttachment attachment = published.get(0);
        assertEquals("stored", attachment.state());
        assertTrue(tempDir.resolve(attachment.relativePath()).normalize()
                .startsWith(tempDir.resolve("attachments").resolve("message-1").normalize()));
        assertEquals(5L, attachment.sizeBytes());
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", staged.sha256());
        assertNotNull(attachment.id());
        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8),
                store.open("message-1", attachment.id()).readAllBytes());
    }

    @Test
    void stagesEmptyFileAndCleansTemporaryDirectoryAfterPublish() throws Exception {
        Config config = new Config(Map.of("EMAIL_DATA_DIR", tempDir.toString()));
        EmailAttachmentStore store = new EmailAttachmentStore(config);
        StagedAttachment staged = store.stage(new ByteArrayInputStream(new byte[0]), "empty.txt", "text/plain",
                new AttachmentBudget(1, 0, 10240));
        assertEquals(0, staged.sizeBytes());
        store.publish("message-2", List.of(staged));
        assertFalse(Files.exists(staged.temporaryPath().getParent()));
    }

    @Test
    void rejectsPathTraversalAndInvalidAttachmentLookup() throws Exception {
        Config config = new Config(Map.of("EMAIL_DATA_DIR", tempDir.toString()));
        EmailAttachmentStore store = new EmailAttachmentStore(config);
        StagedAttachment staged = store.stage(new ByteArrayInputStream(new byte[]{1}), "a\\b\u0000.txt", "application/octet-stream",
                new AttachmentBudget(1, 10, 10240));
        List<EmailAttachment> published = store.publish("message-3", List.of(staged));
        assertThrows(EmailAttachmentStoreException.class, () -> store.open("message-3", "../outside"));
        assertThrows(EmailAttachmentStoreException.class, () -> store.open("other-message", published.get(0).id()));
    }

    @Test
    void removesFailedStagingDirectoryWhenBudgetIsExceeded() throws Exception {
        Config config = new Config(Map.of("EMAIL_DATA_DIR", tempDir.toString()));
        EmailAttachmentStore store = new EmailAttachmentStore(config);
        EmailAttachmentStoreException error = assertThrows(EmailAttachmentStoreException.class,
                () -> store.stage(new ByteArrayInputStream(new byte[]{1, 2}), "too-big.bin", "application/octet-stream",
                        new AttachmentBudget(1, 1, 10240)));
        assertEquals("EMAIL_ATTACHMENT_SIZE_LIMIT", error.errorCode());
        assertTrue(Files.list(tempDir.resolve("attachment-tmp")).findAny().isEmpty());
    }

    @Test
    void rejectsStagingWhenPublishedAttachmentsReachStorageBudget() throws Exception {
        Config config = new Config(Map.of("EMAIL_DATA_DIR", tempDir.toString()));
        EmailAttachmentStore store = new EmailAttachmentStore(config);
        AttachmentBudget budget = new AttachmentBudget(16, 10, 3);
        StagedAttachment staged = store.stage(new ByteArrayInputStream(new byte[]{1, 2, 3}), "full.bin", "application/octet-stream", budget);
        store.publish("message-4", List.of(staged));
        EmailAttachmentStoreException error = assertThrows(EmailAttachmentStoreException.class,
                () -> store.stage(new ByteArrayInputStream(new byte[]{4}), "one-more.bin", "application/octet-stream", budget));
        assertEquals("EMAIL_ATTACHMENT_STORAGE_FULL", error.errorCode());
    }

    @Test
    void rejectsPublishedBatchAboveAggregateCountOrTotalBudget() throws Exception {
        EmailAttachmentStore store = new EmailAttachmentStore(new Config(Map.of("EMAIL_DATA_DIR", tempDir.toString())));
        AttachmentBudget countBudget = new AttachmentBudget(1, 10, 100);
        StagedAttachment first = store.stage(new ByteArrayInputStream(new byte[]{1}), "one.bin", "application/octet-stream", countBudget);
        StagedAttachment second = store.stage(new ByteArrayInputStream(new byte[]{2}), "two.bin", "application/octet-stream", countBudget);
        assertEquals("EMAIL_ATTACHMENT_SIZE_LIMIT", assertThrows(EmailAttachmentStoreException.class,
                () -> store.publish("too-many", List.of(first, second))).errorCode());

        AttachmentBudget totalBudget = new AttachmentBudget(2, 3, 100);
        StagedAttachment threeBytes = store.stage(new ByteArrayInputStream(new byte[]{1, 2}), "three.bin", "application/octet-stream", totalBudget);
        StagedAttachment oneByte = store.stage(new ByteArrayInputStream(new byte[]{3, 4}), "four.bin", "application/octet-stream", totalBudget);
        assertEquals("EMAIL_ATTACHMENT_SIZE_LIMIT", assertThrows(EmailAttachmentStoreException.class,
                () -> store.publish("too-large", List.of(threeBytes, oneByte))).errorCode());
    }

    @Test
    void reservesStagedBytesAndReleasesReservationAfterFailedPublish() throws Exception {
        EmailAttachmentStore store = new EmailAttachmentStore(new Config(Map.of("EMAIL_DATA_DIR", tempDir.toString())));
        AttachmentBudget budget = new AttachmentBudget(2, 10, 3);
        StagedAttachment staged = store.stage(new ByteArrayInputStream(new byte[]{1, 2, 3}), "reserved.bin", "application/octet-stream", budget);
        assertEquals("EMAIL_ATTACHMENT_STORAGE_FULL", assertThrows(EmailAttachmentStoreException.class,
                () -> store.stage(new ByteArrayInputStream(new byte[]{4}), "blocked.bin", "application/octet-stream", budget)).errorCode());
        Files.delete(staged.temporaryPath());
        assertEquals("EMAIL_ATTACHMENT_NOT_FOUND", assertThrows(EmailAttachmentStoreException.class,
                () -> store.publish("failed", List.of(staged))).errorCode());
        assertTrue(Files.list(tempDir.resolve("attachment-tmp")).findAny().isEmpty());
        store.stage(new ByteArrayInputStream(new byte[]{4}), "released.bin", "application/octet-stream", budget);
    }

    @Test
    void usesSafeMetadataAndStablePathErrors() throws Exception {
        EmailAttachmentStore store = new EmailAttachmentStore(new Config(Map.of("EMAIL_DATA_DIR", tempDir.toString())));
        StagedAttachment staged = store.stage(new ByteArrayInputStream(new byte[]{1}), "safe.bin", "text/plain|unsafe",
                new AttachmentBudget(1, 10, 100));
        EmailAttachment attachment = store.publish("metadata-message", List.of(staged)).get(0);
        assertArrayEquals(new byte[]{1}, store.open("metadata-message", attachment.id()).readAllBytes());
        for (String invalidMessageId : List.of("..", "bad/name", "bad\\name", "bad\u0000name")) {
            assertEquals("EMAIL_ATTACHMENT_PATH_INVALID", assertThrows(EmailAttachmentStoreException.class,
                    () -> store.open(invalidMessageId, attachment.id())).errorCode());
        }
        assertEquals("EMAIL_ATTACHMENT_PATH_INVALID", assertThrows(EmailAttachmentStoreException.class,
                () -> store.open(null, attachment.id())).errorCode());
        assertEquals("EMAIL_ATTACHMENT_PATH_INVALID", assertThrows(EmailAttachmentStoreException.class,
                () -> store.open("metadata-message", null)).errorCode());
    }

    @Test
    void capsLongFileNamesAndReportsBudgetAvailabilityAndBoundedReconciliation() throws Exception {
        Config config = new Config(Map.of("EMAIL_DATA_DIR", tempDir.toString()));
        EmailAttachmentStore store = new EmailAttachmentStore(config);
        String longName = "a".repeat(500) + ".txt";
        StagedAttachment staged = store.stage(new ByteArrayInputStream(new byte[]{1, 2}), longName, "text/plain",
                new AttachmentBudget(1, 10, 100));
        assertTrue(staged.fileName().getBytes(StandardCharsets.UTF_8).length <= 128);
        store.publish("availability", List.of(staged));
        assertEquals(config.emailAttachmentStorageMaxBytes() - 2, store.availableBytes());

        Files.createDirectories(tempDir.resolve("attachment-tmp/a"));
        Files.createDirectories(tempDir.resolve("attachment-tmp/b"));
        assertEquals(1, store.reconcile(1));
        assertEquals(1, Files.list(tempDir.resolve("attachment-tmp")).collect(Collectors.toList()).size());
    }
}
