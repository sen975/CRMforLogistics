package com.crmforlogistics.messagecenter.service.wecom;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class WeComMediaDescriptorSchemaContractTest {
    @Test
    void migrationAddsBoundedMediaDescriptorColumn() throws Exception {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V35__wecom_media_descriptor.sql"))
                .toLowerCase();
        assertThat(sql).contains("alter table wecom_chatdata_messages")
                .contains("add column media_json jsonb")
                .contains("provider media identifiers");
    }
}
