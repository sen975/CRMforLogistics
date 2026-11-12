package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.ResolvedInstallation;
import com.crmforlogistics.messagecenter.channel.wecom.WeComAppChatGateway;
import com.crmforlogistics.messagecenter.channel.wecom.WeComException;
import com.crmforlogistics.messagecenter.channel.wecom.WeComInstallationService;
import com.crmforlogistics.messagecenter.config.AppConfig;
import com.crmforlogistics.messagecenter.config.ConditionalOnWeComEnabled;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
@ConditionalOnWeComEnabled
@ConditionalOnExpression("not '${app.wecom-suite-id:}'.isBlank()")
public class WeComAppChatService {
    private static final Set<String> MESSAGE_TYPES = Set.of(
            "text", "markdown", "image", "voice", "video", "file", "news", "mpnews", "textcard");
    private static final int MAX_CONTENT_BYTES = 256 * 1024;

    private final AppConfig config;
    private final WeComInstallationService installations;
    private final WeComAppChatGateway gateway;
    private final WeComApiAuditTrail audit;
    private final ObjectMapper objectMapper;

    public WeComAppChatService(AppConfig config, WeComInstallationService installations,
                               WeComAppChatGateway gateway, WeComApiAuditTrail audit,
                               ObjectMapper objectMapper) {
        this.config = config;
        this.installations = installations;
        this.gateway = gateway;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    public JsonNode create(CreateCommand command, WeComApiActor actor) {
        String authCorpId = required(command.authCorpId(), "authCorpId", 128);
        String name = required(command.name(), "name", 50);
        String owner = required(command.owner(), "owner", 128);
        List<String> users = members(command.userList());
        if (!users.contains(owner)) throw new IllegalArgumentException("owner must be in userList");
        ResolvedInstallation installation = resolve(authCorpId);
        return execute(installation, actor, "wecom.api.appchat.create", "/cgi-bin/appchat/create",
                () -> gateway.create(installation, optional(command.chatId(), 128), name,
                        owner, users, timeout()));
    }

    public JsonNode get(String authCorpId, String chatId, WeComApiActor actor) {
        ResolvedInstallation installation = resolve(required(authCorpId, "authCorpId", 128));
        String normalizedChatId = required(chatId, "chatId", 128);
        return execute(installation, actor, "wecom.api.appchat.get", "/cgi-bin/appchat/get",
                () -> gateway.get(installation, normalizedChatId, timeout()));
    }

    public JsonNode update(UpdateCommand command, WeComApiActor actor) {
        String authCorpId = required(command.authCorpId(), "authCorpId", 128);
        String chatId = required(command.chatId(), "chatId", 128);
        String name = command.name() == null ? null : required(command.name(), "name", 50);
        String owner = command.owner() == null ? null : required(command.owner(), "owner", 128);
        List<String> add = members(command.addUsers());
        List<String> remove = members(command.removeUsers());
        Set<String> overlap = new LinkedHashSet<>(add);
        overlap.retainAll(remove);
        if (!overlap.isEmpty()) throw new IllegalArgumentException("addUsers and removeUsers overlap");
        if (name == null && owner == null && add.isEmpty() && remove.isEmpty()) {
            throw new IllegalArgumentException("appchat update has no changes");
        }
        ResolvedInstallation installation = resolve(authCorpId);
        return execute(installation, actor, "wecom.api.appchat.update", "/cgi-bin/appchat/update",
                () -> gateway.update(installation, chatId, name, owner, add, remove, timeout()));
    }

    public JsonNode send(SendCommand command, WeComApiActor actor) {
        String authCorpId = required(command.authCorpId(), "authCorpId", 128);
        String chatId = required(command.chatId(), "chatId", 128);
        String type = required(command.messageType(), "messageType", 32);
        if (!MESSAGE_TYPES.contains(type)) throw new IllegalArgumentException("messageType is unsupported");
        if (command.content() == null || !command.content().isObject()) {
            throw new IllegalArgumentException("content must be an object");
        }
        try {
            if (objectMapper.writeValueAsBytes(command.content()).length > MAX_CONTENT_BYTES) {
                throw new IllegalArgumentException("message content is too large");
            }
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("message content is invalid", exception);
        }
        ResolvedInstallation installation = resolve(authCorpId);
        return execute(installation, actor, "wecom.api.appchat.send", "/cgi-bin/appchat/send",
                () -> gateway.send(installation, chatId, type, command.content(),
                        Boolean.TRUE.equals(command.safe()), timeout()));
    }

    private ResolvedInstallation resolve(String authCorpId) {
        return installations.resolveInstallation(config.wecomSuiteId(), authCorpId);
    }

    private JsonNode execute(ResolvedInstallation installation, WeComApiActor actor, String action,
                             String path, Operation operation) {
        WeComApiAuditTrail.Attempt attempt = audit.begin(actor.userId(), installation,
                action, path, actor.traceId());
        try {
            JsonNode result = operation.run();
            audit.success(attempt);
            return result;
        } catch (WeComException exception) {
            audit.failed(attempt, exception);
            throw exception;
        }
    }

    private Duration timeout() {
        return Duration.ofSeconds(config.wecomApiTimeoutSeconds());
    }

    private static List<String> members(List<String> values) {
        if (values == null || values.isEmpty() || values.size() > 2000) {
            throw new IllegalArgumentException("user list must contain 1-2000 members");
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String value : values) unique.add(required(value, "userId", 128));
        return List.copyOf(unique);
    }

    private static String required(String value, String field, int max) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value.trim();
    }

    private static String optional(String value, int max) {
        if (value == null || value.isBlank()) return null;
        return required(value, "chatId", max);
    }

    @FunctionalInterface
    private interface Operation { JsonNode run(); }

    public record CreateCommand(String authCorpId, String chatId, String name,
                                String owner, List<String> userList) {}

    public record UpdateCommand(String authCorpId, String chatId, String name,
                                String owner, List<String> addUsers, List<String> removeUsers) {}

    public record SendCommand(String authCorpId, String chatId, String messageType,
                              JsonNode content, Boolean safe) {}
}
