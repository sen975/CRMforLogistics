package com.crmforlogistics.messagecenter.service.wecom;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WeComDailySummaryRetirementContractTest {
    private static final Path ROOT = Path.of("src");

    @Test
    void keepsOnlyTheMessageLevelSummaryRuntimeAndDropsItsRetiredTables() throws Exception {
        String application = Files.readString(ROOT.resolve("main/resources/application.yml"));
        String worker = Files.readString(ROOT.resolve(
                "main/java/com/crmforlogistics/messagecenter/service/wecom/WeComMessageSummaryWorker.java"));
        Path migrationPath = ROOT.resolve("main/resources/db/migration/V31__drop_wecom_daily_summary.sql");
        assertThat(migrationPath).exists();
        String migration = Files.readString(migrationPath);

        assertThat(application)
                .contains("wecom-message-summary-enabled: ${WECOM_MESSAGE_SUMMARY_ENABLED:false}")
                .contains("wecom-message-summary-ability-id: ${WECOM_MESSAGE_SUMMARY_ABILITY_ID:conversation_daily_summary}")
                .doesNotContain("wecom-daily-summary");
        assertThat(worker).contains("app.wecom-message-summary-enabled:false");
        assertThat(migration)
                .contains("DROP TABLE IF EXISTS wecom_daily_summaries")
                .contains("DROP TABLE IF EXISTS wecom_daily_summary_jobs");

        List<Path> retiredSources = List.of(
                ROOT.resolve("main/java/com/crmforlogistics/messagecenter/channel/wecom/WeComDailySummaryEntity.java"),
                ROOT.resolve("main/java/com/crmforlogistics/messagecenter/channel/wecom/WeComDailySummaryJobEntity.java"),
                ROOT.resolve("main/java/com/crmforlogistics/messagecenter/mapper/WeComDailySummaryMapper.java"),
                ROOT.resolve("main/java/com/crmforlogistics/messagecenter/service/wecom/MyBatisWeComDailySummaryRepository.java"),
                ROOT.resolve("main/java/com/crmforlogistics/messagecenter/service/wecom/WeComDailySummaryBatcher.java"),
                ROOT.resolve("main/java/com/crmforlogistics/messagecenter/service/wecom/WeComDailySummaryRepository.java"),
                ROOT.resolve("main/java/com/crmforlogistics/messagecenter/service/wecom/WeComDailySummaryScheduler.java"),
                ROOT.resolve("main/java/com/crmforlogistics/messagecenter/service/wecom/WeComDailySummaryService.java"));
        assertThat(retiredSources).noneMatch(Files::exists);
    }
}
