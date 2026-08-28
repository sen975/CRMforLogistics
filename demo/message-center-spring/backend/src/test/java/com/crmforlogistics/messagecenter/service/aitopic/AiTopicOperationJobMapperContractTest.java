package com.crmforlogistics.messagecenter.service.aitopic;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AiTopicOperationJobMapperContractTest {
    @Test
    void reclaimsExpiredOperationLeasesAfterWorkerInterruption() throws IOException {
        String mapper = Files.readString(Path.of("src/main/java/com/crmforlogistics/messagecenter/mapper/AiTopicOperationJobMapper.java"));

        assertThat(mapper).contains("status='PROCESSING' and lease_until < #{now}")
                .contains("status='PENDING' or (status='PROCESSING' and lease_until < now())");
    }
}
