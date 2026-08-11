package com.crmforlogistics.messagecenter.service.callrecord;

import com.crmforlogistics.messagecenter.config.CallRecordConfig;
import com.crmforlogistics.messagecenter.infrastructure.MinioStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MinioAudioStoreTest {

    @TempDir
    Path temporaryRoot;

    @Test
    void publishStoresAudioUnderTheObjectKeyPersistedWithTheRecord() throws Exception {
        Path stagingDirectory = temporaryRoot.resolve("tmp");
        Files.createDirectories(stagingDirectory);
        Files.createDirectories(temporaryRoot.resolve("audio"));

        byte[] audio = {1, 2, 3};
        Path stagedPath = stagingDirectory.resolve(UUID.randomUUID() + ".upload");
        Files.write(stagedPath, audio);

        MinioStorage minioStorage = mock(MinioStorage.class);
        MinioAudioStore store = new MinioAudioStore(config(temporaryRoot), minioStorage);
        UUID callRecordId = UUID.randomUUID();
        String expectedObjectKey = "call-records/" + callRecordId + ".mp3";
        when(minioStorage.store(expectedObjectKey, audio, "audio/mpeg"))
                .thenReturn(expectedObjectKey);

        MinioAudioStore.AudioAsset asset = store.publish(callRecordId,
                new MinioAudioStore.StagedAudio(
                        stagedPath,
                        "recording.mp3",
                        audio.length,
                        "a".repeat(64),
                        1.0));

        assertEquals(expectedObjectKey, asset.objectKey());
        verify(minioStorage).store(expectedObjectKey, audio, "audio/mpeg");
    }

    private static CallRecordConfig config(Path root) {
        return new CallRecordConfig(
                root.toString(),
                1_024,
                60,
                10_240,
                100,
                64,
                1,
                2_100,
                3,
                10_485_760,
                20_000,
                20,
                300,
                8,
                256);
    }
}
