package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.WeComException;
import com.crmforlogistics.messagecenter.mapper.WeComCredentialMigrationMapper;
import com.crmforlogistics.messagecenter.mapper.WeComCredentialRow;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public final class WeComCredentialMigrationService {
    private static final int BATCH_SIZE = 200;
    private static final String MARKER = "wecom-credentials-v1";

    private final WeComCredentialMigrationMapper mapper;
    private final WeComCredentialProtector protector;

    public WeComCredentialMigrationService(WeComCredentialMigrationMapper mapper,
                                           WeComCredentialProtector protector) {
        this.mapper = mapper;
        this.protector = protector;
    }

    public void migrateAll() {
        try {
            if (mapper.markerExists(MARKER)) return;
            migrateInstallations();
            migrateChatDataMessages();
            mapper.insertMarker(MARKER);
        } catch (WeComException exception) {
            throw exception;
        } catch (Exception exception) {
            throw failed(exception);
        }
    }

    private void migrateInstallations() {
        UUID afterId = null;
        while (true) {
            List<WeComCredentialRow> rows = mapper.nextInstallations(afterId, BATCH_SIZE);
            if (rows == null || rows.isEmpty()) return;
            if (rows.size() > BATCH_SIZE) throw failed();
            for (WeComCredentialRow row : rows) {
                migrate(row, true);
                afterId = row.id();
            }
        }
    }

    private void migrateChatDataMessages() {
        UUID afterId = null;
        while (true) {
            List<WeComCredentialRow> rows = mapper.nextChatDataMessages(afterId, BATCH_SIZE);
            if (rows == null || rows.isEmpty()) return;
            if (rows.size() > BATCH_SIZE) throw failed();
            for (WeComCredentialRow row : rows) {
                migrate(row, false);
                afterId = row.id();
            }
        }
    }

    private void migrate(WeComCredentialRow row, boolean installation) {
        if (row == null || row.id() == null || row.value() == null || row.value().isBlank()
                || row.value().length() > 512) throw failed();
        if (protector.isEnvelope(row.value())) return;
        if (looksLikeEnvelope(row.value())) throw failed();
        String encrypted = installation ? protector.protectPermanentCode(row.value())
                : protector.protectSecretKey(row.value());
        int changed = installation
                ? mapper.replaceInstallation(row.id(), row.value(), encrypted)
                : mapper.replaceChatDataMessage(row.id(), row.value(), encrypted);
        if (changed != 1) throw failed();
    }

    private static boolean looksLikeEnvelope(String value) {
        String trimmed = value.trim();
        return trimmed.startsWith("{") && trimmed.endsWith("}")
                && (trimmed.contains("\"algorithm\"") || trimmed.contains("\"keyVersion\"")
                || trimmed.contains("\"nonce\"") || trimmed.contains("\"ciphertext\""));
    }

    private static WeComException failed() {
        return failed(null);
    }

    private static WeComException failed(Throwable cause) {
        return new WeComException("WECOM_CREDENTIAL_MIGRATION_FAILED", 500,
                "企业微信凭据迁移失败", cause);
    }
}
