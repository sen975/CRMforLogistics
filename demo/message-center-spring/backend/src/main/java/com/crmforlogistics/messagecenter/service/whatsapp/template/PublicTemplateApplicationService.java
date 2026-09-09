package com.crmforlogistics.messagecenter.service.whatsapp.template;

import com.crmforlogistics.messagecenter.channel.chatapp.template.ChatAppPublicTemplateGateway;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static com.crmforlogistics.messagecenter.service.whatsapp.template.PublicTemplateModels.*;

@Service
public class PublicTemplateApplicationService {
    private static final int MAX_PAGE_SIZE = 200;
    private static final int MAX_FILTER_VALUE_LENGTH = 120;
    private static final int MAX_CACHE_ENTRIES = 128;
    private static final long CACHE_TTL_SECONDS = 60;

    private final ChatAppPublicTemplateGateway gateway;
    private final Clock clock;
    private final WhatsAppProviderScopeService providerScopeService;
    private final Map<CacheKey, CacheEntry> cache = new LinkedHashMap<>(16, 0.75f, true);

    public PublicTemplateApplicationService(ChatAppPublicTemplateGateway gateway,
                                            Clock clock,
                                            WhatsAppProviderScopeService providerScopeService) {
        this.gateway = Objects.requireNonNull(gateway);
        this.clock = Objects.requireNonNull(clock);
        this.providerScopeService = Objects.requireNonNull(providerScopeService);
    }

    public Page listForUser(UUID actorUserId, Query query) {
        return list(providerScopeService.requireOwnedActive(actorUserId).account().getId(), query);
    }

    public Page list(UUID accountId, Query query) {
        providerScopeService.requireAccount(accountId);
        Query validated = validate(query);
        CacheKey key = new CacheKey(validated);
        Instant now = clock.instant();
        synchronized (cache) {
            CacheEntry cached = cache.get(key);
            if (cached != null && cached.expiresAt().isAfter(now)) return cached.page();
            if (cached != null) cache.remove(key);
        }
        Page page = gateway.list(accountId, validated);
        synchronized (cache) {
            cache.put(key, new CacheEntry(page, now.plusSeconds(CACHE_TTL_SECONDS)));
            while (cache.size() > MAX_CACHE_ENTRIES) cache.remove(cache.keySet().iterator().next());
        }
        return page;
    }

    private Query validate(Query query) {
        if (query == null || query.page() < 1 || query.size() < 1 || query.size() > MAX_PAGE_SIZE
                || query.industries().size() > 20 || query.usecases().size() > 20) {
            throw error("PUBLIC_TEMPLATE_QUERY_INVALID", HttpStatus.BAD_REQUEST,
                    "Public template query is invalid", false);
        }
        validateFilterValues(query.industries());
        validateFilterValues(query.usecases());
        return new Query(trim(query.name(), 120, "PUBLIC_TEMPLATE_QUERY_INVALID"),
                required(query.language(), "language", 24, "PUBLIC_TEMPLATE_QUERY_INVALID"),
                trim(query.category(), 40, "PUBLIC_TEMPLATE_QUERY_INVALID"), query.industries(), query.usecases(),
                query.page(), query.size());
    }

    private static void validateFilterValues(Iterable<String> values) {
        for (String value : values) {
            if (value == null || value.length() > MAX_FILTER_VALUE_LENGTH) {
                throw error("PUBLIC_TEMPLATE_QUERY_INVALID", HttpStatus.BAD_REQUEST,
                        "Public template query is invalid", false);
            }
        }
    }

    private static String required(String value, String field, int max, String errorCode) {
        String result = trim(value, max, errorCode);
        if (result == null || result.isBlank()) throw error(errorCode, HttpStatus.BAD_REQUEST,
                field + " is required", false);
        return result;
    }

    private static String trim(String value, int max, String errorCode) {
        if (value == null) return null;
        String result = value.trim();
        if (result.length() > max) throw error(errorCode, HttpStatus.BAD_REQUEST,
                "Public template field exceeds allowed length", false);
        return result;
    }

    private static WhatsAppTemplateException error(String code, HttpStatus status, String message, boolean retryable) {
        return new WhatsAppTemplateException(code, status, message, Map.of(), null, retryable);
    }

    private record CacheKey(Query query) {}
    private record CacheEntry(Page page, Instant expiresAt) {}
}
