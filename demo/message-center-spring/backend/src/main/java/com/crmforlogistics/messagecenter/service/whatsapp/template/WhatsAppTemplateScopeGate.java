package com.crmforlogistics.messagecenter.service.whatsapp.template;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Persistent startup gate for the shared-template migration. */
@Component
public class WhatsAppTemplateScopeGate {
    private static final String MIGRATION_KEY = "shared-template-v1";

    private final JdbcTemplate jdbcTemplate;

    public WhatsAppTemplateScopeGate(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void open(UUID providerScopeId) {
        jdbcTemplate.update("""
                insert into whatsapp_template_migration_state
                    (migration_key, status, provider_scope_id, report_jsonb, completed_at)
                values ('shared-template-v1', 'READY', ?::uuid, '{}'::jsonb, now())
                on conflict (migration_key) do update set
                    status = 'READY',
                    provider_scope_id = excluded.provider_scope_id,
                    report_jsonb = '{}'::jsonb,
                    completed_at = now(),
                    updated_at = now()
                """, providerScopeId);
    }

    public void fail(String code, String publicMessage) {
        String message = publicMessage == null || publicMessage.isBlank()
                ? "共享 WhatsApp 模板尚未完成初始化" : publicMessage.trim();
        jdbcTemplate.update("""
                insert into whatsapp_template_migration_state
                    (migration_key, status, report_jsonb, updated_at)
                values ('shared-template-v1', 'BLOCKED', jsonb_build_object('code', ?, 'message', ?), now())
                on conflict (migration_key) do update set
                    status = 'BLOCKED',
                    report_jsonb = jsonb_build_object('code', ?, 'message', ?),
                    updated_at = now()
                """, code, message, code, message);
    }

    public UUID requireReady() {
        List<GateState> states = jdbcTemplate.query("""
                        select status, provider_scope_id, report_jsonb ->> 'code' as error_code,
                               report_jsonb ->> 'message' as public_message
                        from whatsapp_template_migration_state
                        where migration_key = 'shared-template-v1'
                        """,
                (resultSet, rowNum) -> new GateState(
                        resultSet.getString("status"),
                        resultSet.getObject("provider_scope_id", UUID.class),
                        resultSet.getString("error_code"),
                        resultSet.getString("public_message")));
        if (states.isEmpty()) {
            throw unavailable("WHATSAPP_TEMPLATE_MIGRATION_PENDING", "共享 WhatsApp 模板正在初始化");
        }
        GateState state = states.get(0);
        if (!"READY".equals(state.status()) || state.providerScopeId() == null) {
            throw unavailable(
                    state.errorCode() == null || state.errorCode().isBlank()
                            ? "WHATSAPP_TEMPLATE_MIGRATION_PENDING" : state.errorCode(),
                    state.publicMessage() == null || state.publicMessage().isBlank()
                            ? "共享 WhatsApp 模板暂不可用" : state.publicMessage());
        }
        return state.providerScopeId();
    }

    private static WhatsAppTemplateException unavailable(String code, String message) {
        return new WhatsAppTemplateException(code, HttpStatus.SERVICE_UNAVAILABLE,
                message, Map.of(), null, true);
    }

    public record GateState(String status, UUID providerScopeId, String errorCode, String publicMessage) { }
}
