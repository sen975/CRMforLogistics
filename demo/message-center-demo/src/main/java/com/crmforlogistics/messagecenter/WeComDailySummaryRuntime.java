package com.crmforlogistics.messagecenter;

public final class WeComDailySummaryRuntime implements AutoCloseable {
    private final Database database;
    private final WeComDailySummaryScheduler scheduler;

    private WeComDailySummaryRuntime(Database database, WeComDailySummaryScheduler scheduler) {
        this.database = database;
        this.scheduler = scheduler;
    }

    public static WeComDailySummaryRuntime open(Config config,
                                                 WeComAuthorizationStore authorizationStore,
                                                 WeComAccessTokenService accessTokens)
            throws WeComDailySummaryStartupException {
        if (!config.wecomDailySummaryEnabled()) {
            return new WeComDailySummaryRuntime(null, null);
        }
        Database database = null;
        try {
            if (authorizationStore == null || accessTokens == null
                    || config.wecomSuiteId().isBlank()
                    || config.wecomLoginAuthCorpId().isBlank()
                    || config.wecomChatDataProgramId().isBlank()) {
                throw new IllegalStateException("daily summary dependencies unavailable");
            }
            config.wecomDailySummaryAbilityId();
            database = Database.open(config);
            database.migrate();
            WeComDailySummaryRepository repository =
                    new JdbcWeComDailySummaryRepository(database);
            WeComDailySummaryBatcher batcher = new WeComDailySummaryBatcher(
                    config, new WeComChatDataStore(config));
            WeComDailySummaryService service = new WeComDailySummaryService(
                    config, authorizationStore, batcher,
                    new WeComSummaryGateway(config, accessTokens), repository);
            WeComDailySummaryScheduler scheduler = new WeComDailySummaryScheduler(config, service);
            scheduler.start();
            return new WeComDailySummaryRuntime(database, scheduler);
        } catch (Exception exception) {
            if (database != null) database.close();
            throw new WeComDailySummaryStartupException(
                    "WECOM_DAILY_SUMMARY_STARTUP_FAILED", 503,
                    "企业微信每日摘要启动失败", exception);
        }
    }

    public boolean enabled() {
        return scheduler != null;
    }

    @Override
    public void close() {
        if (scheduler != null) scheduler.close();
        if (database != null) database.close();
    }
}
