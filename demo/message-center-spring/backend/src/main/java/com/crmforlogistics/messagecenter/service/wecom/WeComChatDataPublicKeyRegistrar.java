package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.ResolvedInstallation;
import com.crmforlogistics.messagecenter.channel.wecom.WeComChatDataPublicKeyGateway;
import com.crmforlogistics.messagecenter.channel.wecom.WeComInstallationService;
import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.config.ConditionalOnWeComEnabled;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

@Component
@ConditionalOnWeComEnabled
@ConditionalOnExpression("not '${app.wecom-suite-id:}'.isBlank() and '${app.wecom-chatdata-public-key-auto-register:false}' == 'true'")
public class WeComChatDataPublicKeyRegistrar {
    private static final Logger log = LoggerFactory.getLogger(WeComChatDataPublicKeyRegistrar.class);
    private final AppConfig config;
    private final WeComInstallationService installationService;
    private final WeComAccessTokenService accessTokens;
    private final WeComChatDataPublicKeyRegistrationStore registrationStore;
    private final WeComChatDataPublicKeyGateway gateway;
    private final WeComStartupGate startupGate;
    private final Supplier<WeComChatDataCrypto.PublicKeyMaterial> materialSupplier;
    private final ThreadPoolExecutor registrationExecutor;
    private final AtomicBoolean registrationQueued = new AtomicBoolean();
    private volatile WeComChatDataCrypto.PublicKeyMaterial cachedMaterial;

    public WeComChatDataPublicKeyRegistrar(AppConfig config,
                                           WeComInstallationService installationService,
                                           WeComAccessTokenService accessTokens,
                                           WeComChatDataPublicKeyRegistrationStore registrationStore,
                                           WeComChatDataPublicKeyGateway gateway,
                                           WeComStartupGate startupGate) {
        this(config, installationService, accessTokens, registrationStore, gateway, startupGate,
                null);
    }

    WeComChatDataPublicKeyRegistrar(AppConfig config,
                                    WeComInstallationService installationService,
                                    WeComAccessTokenService accessTokens,
                                    WeComChatDataPublicKeyRegistrationStore registrationStore,
                                    WeComChatDataPublicKeyGateway gateway,
                                    WeComStartupGate startupGate,
                                    Supplier<WeComChatDataCrypto.PublicKeyMaterial> materialSupplier) {
        this.config = config;
        this.installationService = installationService;
        this.accessTokens = accessTokens;
        this.registrationStore = registrationStore;
        this.gateway = gateway;
        this.startupGate = startupGate;
        this.materialSupplier = materialSupplier == null ? this::loadMaterial : materialSupplier;
        this.registrationExecutor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1), runnable -> {
                    Thread thread = new Thread(runnable, "wecom-public-key-registration");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    public void requestRegistration() {
        if (!registrationQueued.compareAndSet(false, true)) return;
        try {
            registrationExecutor.execute(() -> {
                try {
                    registerIfNeeded();
                } finally {
                    registrationQueued.set(false);
                }
            });
        } catch (RejectedExecutionException rejected) {
            registrationQueued.set(false);
            log.warn("WeCom chatdata public key registration signal rejected", rejected);
        }
    }

    @Scheduled(fixedDelayString = "${app.wecom-chatdata-auto-sync-interval-seconds:60}000",
            initialDelay = 5000)
    public void registerIfNeeded() {
        try {
            startupGate.requireOpen();
            ResolvedInstallation installation = installationService.resolveInstallation(
                    config.wecomSuiteId(), config.wecomLoginAuthCorpId());
            WeComChatDataCrypto.PublicKeyMaterial material = materialSupplier.get();
            if (registrationStore.isRegistered(config.wecomLoginAuthCorpId(),
                    material.version(), material.sha256())) {
                return;
            }
            String accessToken = accessTokens.accessToken(installation);
            gateway.register(accessToken, material);
            registrationStore.markRegistered(config.wecomLoginAuthCorpId(),
                    material.version(), material.sha256());
        } catch (Exception e) {
            log.warn("WeCom chatdata public key registration failed", e);
        }
    }

    private WeComChatDataCrypto.PublicKeyMaterial loadMaterial() {
        WeComChatDataCrypto.PublicKeyMaterial current = cachedMaterial;
        if (current != null) return current;
        current = new WeComChatDataCrypto(config).publicKeyMaterial();
        cachedMaterial = current;
        return current;
    }

    @PreDestroy
    public void close() {
        registrationExecutor.shutdown();
        try {
            if (!registrationExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                registrationExecutor.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            registrationExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
