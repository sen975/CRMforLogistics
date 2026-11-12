package com.crmforlogistics.messagecenter.service.callrecord;

import com.crmforlogistics.messagecenter.config.CallRecordConfig;
import com.crmforlogistics.messagecenter.infrastructure.MinioStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.eq;

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

    @Test
    void openRangeDelegatesOffsetAndLengthWithoutReadingTheWholeObject() throws Exception {
        MinioStorage minioStorage = mock(MinioStorage.class);
        MinioAudioStore store = new MinioAudioStore(config(temporaryRoot), minioStorage);
        MinioAudioStore.AudioAsset asset = new MinioAudioStore.AudioAsset(
                "audio/" + UUID.randomUUID() + ".mp3", "recording.mp3", 10,
                "a".repeat(64), "audio/mpeg", 1.0, "call-records/range.mp3");
        java.io.InputStream expected = new java.io.ByteArrayInputStream(new byte[]{1, 2, 3});
        when(minioStorage.getRange("call-records/range.mp3", 2, 3)).thenReturn(expected);

        org.junit.jupiter.api.Assertions.assertSame(expected, store.open(asset, 2, 3));
        verify(minioStorage).getRange(eq("call-records/range.mp3"), eq(2L), eq(3L));
    }

    @Test
    void deleteRemovesBothLocalAndRemoteObjects() throws Exception {
        Path audioDirectory = temporaryRoot.resolve("audio");
        Files.createDirectories(audioDirectory);
        UUID id = UUID.randomUUID();
        Path local = audioDirectory.resolve(id + ".mp3");
        Files.write(local, new byte[]{1});
        MinioStorage minioStorage = mock(MinioStorage.class);
        MinioAudioStore store = new MinioAudioStore(config(temporaryRoot), minioStorage);
        MinioAudioStore.AudioAsset asset = new MinioAudioStore.AudioAsset(
                "audio/" + id + ".mp3", "recording.mp3", 1,
                "a".repeat(64), "audio/mpeg", 1.0, "call-records/" + id + ".mp3");

        store.delete(asset);

        assertThat(Files.exists(local)).isFalse();
        verify(minioStorage).remove("call-records/" + id + ".mp3");
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
                256,
                105906176L);
    }
}
