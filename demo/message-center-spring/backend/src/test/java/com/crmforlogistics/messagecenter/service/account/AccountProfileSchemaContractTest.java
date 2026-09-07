package com.crmforlogistics.messagecenter.service.account;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AccountProfileSchemaContractTest {

    @Test
    void migrationAddsBoundedPrivateAvatarMetadata() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V46__account_profile_avatar.sql"));

        assertThat(sql)
                .contains("avatar_object_key")
                .contains("avatar_mime_type")
                .contains("avatar_size_bytes")
                .contains("avatar_updated_at")
                .contains("2097152")
                .contains("user-avatars/");
    }
}
