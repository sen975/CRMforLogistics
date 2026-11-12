package com.crmforlogistics.wecomchatdata;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZoneRuntimeResourcePatchTest {
    @Test
    void rootfsBuildBindsTheTwoAbilitiesWithoutDockerEnvironmentMetadata() throws Exception {
        String dockerfile = Files.readString(Path.of("Dockerfile"));
        String buildScript = Files.readString(Path.of("build-image.sh"));

        assertFalse(dockerfile.contains("ENV WECOM_CHATDATA_ABILITY_ID"));
        assertFalse(dockerfile.contains("ARG WECOM_CHATDATA_ABILITY_ID"));
        assertTrue(buildScript.contains("conversation_viewer_sync"));
        assertTrue(buildScript.contains("conversation_daily_summary"));
        assertFalse(buildScript.contains("ABILITY_ID=${WECOM_CHATDATA_ABILITY_ID"));
    }

    @Test
    void buildPatchBoundsOfficialDemoRuntimeResources() throws Exception {
        String prepare = Files.readString(Path.of("prepare-official-sdk.sh"));
        String patch = Files.readString(Path.of("patches/runtime-resource-limits.patch"));

        assertTrue(prepare.contains("runtime-resource-limits.patch"));
        assertTrue(patch.contains("HttpObjectAggregator(2 * 1024 * 1024)"));
        assertTrue(patch.contains("io_gorup_size = 4"));
        assertTrue(patch.contains("business_gorup_size = 4"));
        assertTrue(patch.contains("Class.forName(\"mytype.mycom.mygroup.DataBaseUtils\")"));
    }

    @Test
    void startupScriptForwardsOfficialDebugArguments() throws Exception {
        String start = Files.readString(Path.of("start"));
        assertTrue(start.contains("wecom-chatdata-zone-program.jar \"$@\""));
    }
}
