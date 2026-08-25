package com.crmforlogistics.messagecenter.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FunAsrComposeContractTest {

    private static String read(String relativePath) throws Exception {
        return Files.readString(Path.of(relativePath));
    }

    @Test
    void composeOwnsTheFunAsrServiceAndPersistentModelCache() throws Exception {
        String compose = read("compose.yaml");

        assertThat(compose).contains(
                "  funasr:",
                "context: ./funasr-runtime",
                "image: crm-logistics-message-center-funasr:local",
                "127.0.0.1:${FUNASR_HOST_PORT:-8000}:8000",
                "FUNASR_MODEL: ${FUNASR_MODEL:-sensevoice}",
                "FUNASR_INTRAOP_THREADS: ${FUNASR_INTRAOP_THREADS:-1}",
                "FUNASR_INTEROP_THREADS: ${FUNASR_INTEROP_THREADS:-1}",
                "FUNASR_MAX_CONCURRENCY: ${FUNASR_MAX_CONCURRENCY:-1}",
                "/tmp:size=${FUNASR_TMPFS_SIZE:-512m}",
                "funasr_cache:/root/.cache",
                "funasr_cache:"
        );
        assertThat(compose).contains("http://localhost:8000/health");
        assertThat(compose).doesNotContain("/Users/z/FunASR");
    }

    @Test
    void runtimePinsFunAsrAndLoadsSenseVoiceVadAndCtPunc() throws Exception {
        String dockerfile = read("funasr-runtime/Dockerfile");
        String requirements = read("funasr-runtime/requirements.txt");
        String server = read("funasr-runtime/server.py");

        assertThat(dockerfile).contains(
                "FROM python:3.10.20-slim",
                "ARG PIP_VERSION=26.2.1",
                "--index-url https://download.pytorch.org/whl/cpu",
                "\"torch==2.8.0\"",
                "\"torchaudio==2.8.0\"",
                "pip install --requirement /app/requirements.txt"
        );
        assertThat(dockerfile).doesNotContain("nvidia-cuda", "nvidia-cudnn", "cuda-toolkit");
        assertThat(requirements).contains(
                "funasr==1.4.0",
                "fastapi==0.141.1",
                "uvicorn[standard]==0.52.1",
                "python-multipart==0.0.32"
        );
        assertThat(server).contains(
                "\"model\": \"iic/SenseVoiceSmall\"",
                "\"vad_model\": \"fsmn-vad\"",
                "\"punc_model\": \"ct-punc-c\"",
                "MAX_REQUEST_BYTES",
                "HTTP_ADMISSION_GATE",
                "FUNASR_MAX_CONCURRENCY",
                "asyncio.Semaphore"
        );
    }
}
