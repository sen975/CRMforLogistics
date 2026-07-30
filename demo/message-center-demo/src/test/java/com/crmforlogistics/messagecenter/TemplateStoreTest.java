package com.crmforlogistics.messagecenter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemplateStoreTest {
    @TempDir
    Path tempDir;

    Path templateFile;

    @BeforeEach
    void setUp() {
        templateFile = tempDir.resolve("templates.json");
    }

    @Test
    void replacesOnlyWhenBusinessContentChangesAndRemovesDeletedRecords() throws Exception {
        AtomicInteger moves = new AtomicInteger();
        TemplateStore store = new TemplateStore(templateFile,
                (source, target) -> {
                    moves.incrementAndGet();
                    Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                });

        assertTrue(store.replaceIfChanged(List.of(record("a", "first", "2026-07-01T00:00:00Z"),
                record("b", "second", "2026-07-01T00:00:00Z"))).changed());
        assertEquals(1, moves.get());
        assertFalse(store.replaceIfChanged(List.of(record("a", "first", "2026-07-30T00:00:00Z"),
                record("b", "second", "2026-07-30T00:00:00Z"))).changed());
        assertEquals(1, moves.get());

        TemplateStore.ReplaceResult result = store.replaceIfChanged(
                List.of(record("a", "changed", "2026-07-30T00:00:00Z")));
        assertTrue(result.changed());
        assertEquals(2, result.changedRecords());
        assertEquals(List.of("a"), store.readAll().stream().map(item -> item.templateCode).toList());
    }

    @Test
    void keepsExistingSnapshotWhenAtomicMoveFails() throws Exception {
        TemplateStore healthy = new TemplateStore(templateFile);
        healthy.replaceIfChanged(List.of(record("a", "stable", "2026-07-01T00:00:00Z")));
        String before = Files.readString(templateFile, StandardCharsets.UTF_8);
        TemplateStore failing = new TemplateStore(templateFile,
                (source, target) -> {
                    throw new IOException("forced move failure");
                });

        assertThrows(IOException.class,
                () -> failing.replaceIfChanged(List.of(record("a", "new", "2026-07-30T00:00:00Z"))));
        assertEquals(before, Files.readString(templateFile, StandardCharsets.UTF_8));
    }

    private static TemplateStore.TemplateRecord record(String code, String body, String updatedAt) {
        TemplateStore.TemplateRecord item = new TemplateStore.TemplateRecord();
        item.templateCode = code;
        item.templateName = code;
        item.languageCode = "zh_CN";
        item.body = body;
        item.raw = "{\"auditStatus\":\"pass\",\"body\":\"" + body + "\"}";
        item.updatedAt = updatedAt;
        return item;
    }
}
