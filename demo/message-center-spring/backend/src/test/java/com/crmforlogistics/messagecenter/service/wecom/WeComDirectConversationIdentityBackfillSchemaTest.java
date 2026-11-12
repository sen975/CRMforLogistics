package com.crmforlogistics.messagecenter.service.wecom;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeComDirectConversationIdentityBackfillSchemaTest {
    @Test
    void backfillOnlyBindsUnambiguousActiveExternalWeComIdentitiesToDirectConversations() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V38__backfill_wecom_direct_contact_identity.sql"));
        String compact = sql.replaceAll("\\s+", "").toLowerCase();

        assertTrue(compact.contains("sc.conversation_type='direct'"));
        assertTrue(compact.contains("sc.contact_identity_idisnull"));
        assertTrue(compact.contains("p.party_type='external_contact'"));
        assertTrue(compact.contains("ci.channel_type='wecom'"));
        assertTrue(compact.contains("ca.auth_status='active'"));
        assertTrue(compact.contains("count(distinctci.id)=1"));
        assertFalse(compact.contains("conversation_type='group'"));
    }
}
