package com.crmforlogistics.messagecenter;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Coalescing background owner for one configured enterprise's chatdata public key. */
public final class WeComChatDataPublicKeyRegistrar
        implements WeComChatDataPublicKeyRegistrationTrigger, AutoCloseable {
    private final boolean enabled;
    private final Config config;
    private final WeComAuthorizationStore authorizationStore;
    private final AccessTokenProvider accessTokens;
    private final PublicKeyMaterialProvider materialProvider;
    private final WeComChatDataPublicKeyRegistrationStore registrationStore;
    private final RegistrationGateway gateway;
    private final Consumer<String> eventLog;
    private final BlockingQueue<Boolean> signals;
    private final AtomicInteger pendingTasks = new AtomicInteger();
    private final Thread worker;
    private volatile WeComChatDataCrypto.PublicKeyMaterial cachedMaterial;
    private volatile boolean closed;

    public static WeComChatDataPublicKeyRegistrar open(Config config,
                                                       WeComAuthorizationStore authorizationStore,
                                                       WeComAccessTokenService accessTokens) {
        if (!config.wecomChatDataPublicKeyAutoRegister()) {
            return new WeComChatDataPublicKeyRegistrar();
        }
        if (authorizationStore == null || accessTokens == null
                || config.wecomSuiteId().isBlank() || config.wecomLoginAuthCorpId().isBlank()) {
            logOpenFailure(notConfigured());
            return new WeComChatDataPublicKeyRegistrar();
        }
        try {
            return new WeComChatDataPublicKeyRegistrar(config, authorizationStore,
                    accessTokens::accessToken,
                    () -> new WeComChatDataCrypto(config).publicKeyMaterial(),
                    new WeComChatDataPublicKeyRegistrationStore(config),
                    new WeComChatDataPublicKeyGateway(config)::register, System.err::println);
        } catch (RuntimeException exception) {
            logOpenFailure(exception);
            return new WeComChatDataPublicKeyRegistrar();
        }
    }

    private WeComChatDataPublicKeyRegistrar() {
        enabled = false;
        config = null;
        authorizationStore = null;
        accessTokens = null;
        materialProvider = null;
        registrationStore = null;
        gateway = null;
        eventLog = ignored -> {};
        signals = null;
        worker = null;
    }

    private WeComChatDataPublicKeyRegistrar(Config config,
                                            WeComAuthorizationStore authorizationStore,
                                            AccessTokenProvider accessTokens,
                                            PublicKeyMaterialProvider materialProvider,
                                            WeComChatDataPublicKeyRegistrationStore registrationStore,
                                            RegistrationGateway gateway,
                                            Consumer<String> eventLog) {
        this.enabled = true;
        this.config = config;
        this.authorizationStore = authorizationStore;
        this.accessTokens = accessTokens;
        this.materialProvider = materialProvider;
        this.registrationStore = registrationStore;
        this.gateway = gateway;
        this.eventLog = eventLog;
        this.signals = new ArrayBlockingQueue<>(1);
        this.worker = new Thread(this::runWorker, "wecom-chatdata-public-key-registrar");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    static WeComChatDataPublicKeyRegistrar forTests(
            Config config,
            WeComAuthorizationStore authorizationStore,
            AccessTokenProvider accessTokens,
            WeComChatDataCrypto.PublicKeyMaterial material,
            WeComChatDataPublicKeyRegistrationStore registrationStore,
            RegistrationGateway gateway,
            Consumer<String> eventLog) {
        return new WeComChatDataPublicKeyRegistrar(config, authorizationStore, accessTokens,
                () -> material, registrationStore, gateway, eventLog);
    }

    static WeComChatDataPublicKeyRegistrar forTests(
            Config config,
            WeComAuthorizationStore authorizationStore,
            AccessTokenProvider accessTokens,
            PublicKeyMaterialProvider materialProvider,
            WeComChatDataPublicKeyRegistrationStore registrationStore,
            RegistrationGateway gateway,
            Consumer<String> eventLog) {
        return new WeComChatDataPublicKeyRegistrar(config, authorizationStore, accessTokens,
                materialProvider, registrationStore, gateway, eventLog);
    }

    @Override
    public void requestRegistration() {
        if (!enabled || closed) return;
        pendingTasks.incrementAndGet();
        if (!signals.offer(Boolean.TRUE)) pendingTasks.decrementAndGet();
    }

    private void runWorker() {
        while (!closed || !signals.isEmpty()) {
            try {
                Boolean signal = signals.poll(250, TimeUnit.MILLISECONDS);
                if (signal == null) continue;
                try {
                    registerIfNeeded();
                } finally {
                    pendingTasks.decrementAndGet();
                }
            } catch (InterruptedException exception) {
                if (closed && signals.isEmpty()) return;
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException exception) {
                logFailure(exception);
            }
        }
    }

    private void registerIfNeeded() {
        try {
            WeComAuthorizationStore.ResolvedInstallation installation = authorizationStore.resolveActive(
                    config.wecomSuiteId(), config.wecomLoginAuthCorpId());
            WeComChatDataCrypto.PublicKeyMaterial material = material();
            if (registrationStore.isRegistered(config.wecomLoginAuthCorpId(),
                    material.version(), material.sha256())) return;
            String accessToken = accessTokens.accessToken(installation);
            gateway.register(accessToken, material);
            registrationStore.markRegistered(config.wecomLoginAuthCorpId(),
                    material.version(), material.sha256());
            safeLog("{\"event\":\"wecom.chatdata.public_key_registration\",\"status\":\"succeeded\"}");
        } catch (Exception exception) {
            logFailure(exception);
        }
    }

    private WeComChatDataCrypto.PublicKeyMaterial material() throws Exception {
        WeComChatDataCrypto.PublicKeyMaterial current = cachedMaterial;
        if (current != null) return current;
        current = materialProvider.load();
        cachedMaterial = current;
        return current;
    }

    private void logFailure(Throwable failure) {
        String code;
        Integer upstreamErrcode = null;
        String upstreamHint = null;
        if (failure instanceof WeComAuthorizationException authorization) {
            code = authorization.code();
            upstreamErrcode = authorization.upstreamErrcode();
            upstreamHint = authorization.upstreamHint();
        } else if (failure instanceof WeComChatDataException chatData) {
            code = chatData.code();
            upstreamErrcode = chatData.upstreamErrcode();
            upstreamHint = chatData.upstreamHint();
        } else {
            code = "WECOM_CHATDATA_PUBLIC_KEY_REGISTRATION_FAILED";
        }
        if (code == null || !code.matches("[A-Z0-9_]{1,128}")) {
            code = "WECOM_CHATDATA_PUBLIC_KEY_REGISTRATION_FAILED";
        }
        String upstreamField = upstreamErrcode == null ? ""
                : ",\"upstreamErrcode\":" + upstreamErrcode;
        String hintField = upstreamHint == null ? ""
                : ",\"upstreamHint\":\"" + upstreamHint + "\"";
        safeLog("{\"event\":\"wecom.chatdata.public_key_registration\",\"status\":\"failed\",\"code\":\""
                + code + "\"" + upstreamField + hintField + "}");
    }

    private void safeLog(String event) {
        try {
            eventLog.accept(event);
        } catch (RuntimeException ignored) {
            // Observability failure must not change credential or callback state.
        }
    }

    int pendingCount() {
        return enabled ? pendingTasks.get() : 0;
    }

    @Override
    public void close() {
        if (!enabled) return;
        closed = true;
        try {
            worker.join(25_000);
            if (worker.isAlive()) {
                worker.interrupt();
                worker.join(1_000);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static WeComChatDataException notConfigured() {
        return new WeComChatDataException("WECOM_CHATDATA_PUBLIC_KEY_REGISTRATION_NOT_CONFIGURED", 500,
                "企业微信会话存档公钥自动注册配置不完整");
    }

    private static void logOpenFailure(Throwable failure) {
        String code = failure instanceof WeComChatDataException chatData
                ? chatData.code() : "WECOM_CHATDATA_PUBLIC_KEY_REGISTRATION_FAILED";
        if (code == null || !code.matches("[A-Z0-9_]{1,128}")) {
            code = "WECOM_CHATDATA_PUBLIC_KEY_REGISTRATION_FAILED";
        }
        System.err.println("{\"event\":\"wecom.chatdata.public_key_registration\","
                + "\"status\":\"failed\",\"code\":\"" + code + "\"}");
    }

    @FunctionalInterface
    interface AccessTokenProvider {
        String accessToken(WeComAuthorizationStore.ResolvedInstallation installation) throws Exception;
    }

    @FunctionalInterface
    interface PublicKeyMaterialProvider {
        WeComChatDataCrypto.PublicKeyMaterial load() throws Exception;
    }

    @FunctionalInterface
    interface RegistrationGateway {
        void register(String accessToken, WeComChatDataCrypto.PublicKeyMaterial material) throws Exception;
    }
}

@FunctionalInterface
interface WeComChatDataPublicKeyRegistrationTrigger {
    void requestRegistration();
}
