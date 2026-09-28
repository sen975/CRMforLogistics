package com.crmforlogistics.messagecenter.service.assistant;

import com.crmforlogistics.messagecenter.entity.AssistantActionAuditEntity;
import com.crmforlogistics.messagecenter.mapper.AssistantActionAuditMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 助手审计：把「模型想做什么、策略怎么判、最后发生了什么」写成一行追加记录。
 *
 * <h2>写失败不抛给用户</h2>
 * 审计失败时只打 ERROR 日志，<b>不</b>让这一轮变成失败。理由是一个具体的时序问题：
 * 工具可能已经执行成功，此时再抛异常会告诉用户「失败了」，而数据其实已经改了 ——
 * 那比「少一条审计」严重得多。审计表与业务表不在一个事务里（工具执行也不在事务里），
 * 因此没有一种写法能同时保证两者原子；选择保住「用户看到的结论是真的」。
 *
 * <p>这条选择的代价必须写清楚：<b>极端情况下会出现「动作生效了但没有审计行」</b>。
 * 这不是可以忽略的角落 —— 验收标准第 3 条要求「能回答 AI 做了什么」。
 * 因此这里的失败日志用 ERROR 级别并带上非敏感操作元数据，以便从日志侧定位。
 *
 * <p>原话只存长度和摘要，参数只存顶层键名与值类型；规范化参数摘要用于动作对账，
 * 但低熵值的摘要并不提供匿名化保证。历史审计记录仍需单独治理。
 */
@Component
public class AssistantAuditService {

    private static final Logger log = LoggerFactory.getLogger(AssistantAuditService.class);

    private static final int AUDIT_KEYS_MAX = 32;

    private final AssistantActionAuditMapper mapper;
    private final ObjectMapper objectMapper;

    public AssistantAuditService(AssistantActionAuditMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    public void record(Entry entry) {
        try {
            AssistantActionAuditEntity entity = new AssistantActionAuditEntity();
            entity.setUserId(entry.userId());
            entity.setConversationId(entry.conversationId());
            entity.setUtterance(entry.utterance() == null ? null
                    : "chars:" + entry.utterance().length() + ";sha256:" + sha256Hex(entry.utterance()));
            entity.setDecision(entry.decision());
            entity.setToolName(entry.toolName());
            entity.setArgumentsJson(writeJson(argumentShape(entry.arguments())));
            entity.setArgumentsDigest(digest(entry.arguments()));
            entity.setPolicy(entry.policy());
            entity.setOutcome(entry.outcome());
            entity.setErrorCode(entry.errorCode());
            entity.setModel(entry.model());
            entity.setLatencyMs(entry.latencyMs());
            entity.setTurnIndex(entry.turnIndex());
            mapper.insert(entity);
        } catch (RuntimeException e) {
            log.error("event=assistant.audit_write_failed userId={} conversationId={} decision={} "
                            + "policy={} outcome={} errorCode={} exceptionType={}",
                    entry.userId(), entry.conversationId(), entry.decision(), entry.policy(),
                    entry.outcome(), entry.errorCode(), e.getClass().getName());
        }
    }

    /** 该用户的审计流水，倒序。用于排障与验收（「能回答 AI 做了什么」）。 */
    public List<AssistantActionAuditEntity> listByUser(UUID userId, int limit) {
        return mapper.listByUser(userId, Math.max(1, Math.min(limit, 200)));
    }

    /**
     * 一段会话的完整轨迹，按轮次正序。用来回答「模型依次看了什么、最后做了什么」——
     * 这是 {@code turn_index} 存在的全部理由，只按 {@code created_at} 排是排不出来的
     * （同一轮内的两行可能落在同一毫秒）。
     *
     * <p>与 {@link #listByUser} 一样带 {@code userId}：会话号只是分组标签、不是凭证，
     * 归属一律按登录用户判定，所以拿别人的会话号来查只会得到空列表。
     *
     * <p>{@code conversationId} 为 null（前端没带会话号）时返回空：SQL 里
     * {@code conversation_id = null} 恒不成立，不必在这里再判一次。
     */
    public List<AssistantActionAuditEntity> listByConversation(UUID userId, UUID conversationId) {
        return mapper.listByConversation(userId, conversationId);
    }

    /**
     * 一行审计。字段全部来自调用方 —— 审计服务自己不推断任何东西，
     * 否则「谁决定了这条记录是 CANCELLED」就说不清了。
     *
     * <p>{@code turnIndex} 是本次请求内的轮次（从 0 起）：它对「一次 respond 里的第几轮」有意义，
     * 对**之后另一次请求**里发生的确认 / 取消 / 过期没有意义。
     */
    public record Entry(UUID userId, UUID conversationId, String utterance, String decision,
                        String toolName, Map<String, Object> arguments, String policy, String outcome,
                        String errorCode, String model, Integer latencyMs, Integer turnIndex) {

        /**
         * 不带轮次的构造器，给确认 / 取消 / 过期三条路径用。
         *
         * <p>这三条发生在轮次序列之外，{@code turnIndex} 本就该是 null。留这个重载不是为了让调用点
         * 少写一个参数，而是为了让「这里不传轮次」成为一个**有意义的默认**：翻代码的人看到 11 个参数
         * 就知道这条记录不属于任何一次 respond，而看到一个显式的 {@code null} 只会怀疑是漏填。
         */
        public Entry(UUID userId, UUID conversationId, String utterance, String decision,
                     String toolName, Map<String, Object> arguments, String policy, String outcome,
                     String errorCode, String model, Integer latencyMs) {
            this(userId, conversationId, utterance, decision, toolName, arguments, policy, outcome,
                    errorCode, model, latencyMs, null);
        }
    }

    private String writeJson(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            // 空 map 存 null 而不是 {}：ask / reply 两轮本来就「没有参数」，
            // 与「调用了一个不带参数的工具」不是一回事。
            return null;
        }
        try {
            return objectMapper.writeValueAsString(arguments);
        } catch (Exception e) {
            return null;
        }
    }

    private static Map<String, Object> argumentShape(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) return null;
        Map<String, Object> shape = new TreeMap<>();
        for (Map.Entry<String, Object> entry : arguments.entrySet()) {
            if (shape.size() >= AUDIT_KEYS_MAX) break;
            String key = entry.getKey();
            if (key == null || !key.matches("[A-Za-z][A-Za-z0-9_]{0,63}")) continue;
            Object value = entry.getValue();
            shape.put(key, value == null ? "null" : value instanceof Map ? "object"
                    : value instanceof List ? "array" : value instanceof String ? "string"
                    : value instanceof Number ? "number" : value instanceof Boolean ? "boolean" : "omitted");
        }
        return shape;
    }

    /** 规范化 JSON 的 sha256。键递归排序，保证「同一组参数」永远得到同一个摘要。 */
    String digest(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return null;
        }
        try {
            return sha256Hex(objectMapper.writeValueAsString(canonicalise(arguments)));
        } catch (Exception e) {
            return null;
        }
    }

    /** 递归按 key 排序，让 map 的插入顺序不影响序列化结果。 */
    @SuppressWarnings("unchecked")
    private static Object canonicalise(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                sorted.put(String.valueOf(entry.getKey()), canonicalise(entry.getValue()));
            }
            return new LinkedHashMap<>(sorted);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(AssistantAuditService::canonicalise).toList();
        }
        return value;
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
