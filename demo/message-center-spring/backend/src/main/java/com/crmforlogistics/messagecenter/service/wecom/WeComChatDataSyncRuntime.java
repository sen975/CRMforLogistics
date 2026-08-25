package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.ResolvedInstallation;
import com.crmforlogistics.messagecenter.channel.wecom.WeComInstallationService;
import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.mapper.WeComUserBindingMapper;
import com.crmforlogistics.messagecenter.config.ConditionalOnWeComEnabled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

@Component
@ConditionalOnWeComEnabled
@ConditionalOnExpression("not '${app.wecom-suite-id:}'.isBlank()")
@ConditionalOnProperty(name = "app.wecom-chatdata-auto-sync-enabled",
        havingValue = "true", matchIfMissing = true)
public class WeComChatDataSyncRuntime {
    private static final Logger log = LoggerFactory.getLogger(WeComChatDataSyncRuntime.class);
    private final AppConfig config;
    private final WeComInstallationService installationService;
    private final WeComChatDataSyncService syncService;
    private final WeComStartupGate startupGate;
    private final WeComUserBindingMapper bindingMapper;

    @Autowired
    public WeComChatDataSyncRuntime(AppConfig config, WeComInstallationService installationService,
                                    WeComChatDataSyncService syncService,
                                    WeComStartupGate startupGate,
                                    ObjectProvider<WeComUserBindingMapper> bindingMapperProvider) {
        this.config = config;
        this.installationService = installationService;
        this.syncService = syncService;
        this.startupGate = startupGate;
        this.bindingMapper = bindingMapperProvider.getIfAvailable();
    }

    public WeComChatDataSyncRuntime(AppConfig config, WeComInstallationService installationService,
                                    WeComChatDataSyncService syncService,
                                    WeComStartupGate startupGate) {
        this.config = config;
        this.installationService = installationService;
        this.syncService = syncService;
        this.startupGate = startupGate;
        this.bindingMapper = null;
    }

    @Scheduled(fixedDelayString = "${app.wecom-chatdata-auto-sync-interval-seconds:60}000",
            initialDelay = 1000)
    public void runOnce() {
        try {
            startupGate.requireOpen();
            if (!isConfigured()) {
                log.warn("WeCom chatdata auto-sync skipped: not configured");
                return;
            }
            ResolvedInstallation installation = installationService.resolveInstallation(
                    config.wecomSuiteId(), config.wecomLoginAuthCorpId());
            String viewerWecomUserId = bindingMapper == null ? "" : bindingMapper.findLatestByInstallation(
                    config.wecomSuiteId(), config.wecomLoginAuthCorpId())
                    .map(binding -> binding.getWecomUserId())
                    .orElse("");
            ViewerSyncContext context = new ViewerSyncContext(viewerWecomUserId, installation);
            WeComChatDataSyncService.SyncResult result = syncService.sync(context);
            log.info("WeCom chatdata auto-sync succeeded pages={} stored={} skipped={}",
                    result.pages(), result.stored(), result.skipped());
        } catch (Exception e) {
            log.warn("WeCom chatdata auto-sync failed", e);
        }
    }

    private boolean isConfigured() {
        return !config.localDevMode()
                && !config.wecomLoginAuthCorpId().isBlank()
                && !config.wecomChatDataProgramId().isBlank()
                && !config.wecomChatDataAbilityId().isBlank()
                && Files.isRegularFile(Path.of(config.wecomChatDataPrivateKeyFile()));
    }
}
