package com.crmforlogistics.messagecenter.service.wecom;

import com.crmforlogistics.messagecenter.channel.wecom.WeComException;
import com.crmforlogistics.messagecenter.channel.wecom.WeComInstallationEntity;
import com.crmforlogistics.messagecenter.config.ConditionalOnWeComEnabled;
import com.crmforlogistics.messagecenter.mapper.WeComInstallationMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Properties;
import java.util.UUID;

@Service
@ConditionalOnWeComEnabled
@ConditionalOnExpression("not '${app.wecom-suite-id:}'.isBlank()")
public class WeComInstallationImportService {
    private static final int MAX_FILE_BYTES = 16 * 1024;
    private static final int MAX_CORP_ID_LENGTH = 128;
    private static final int MAX_AGENT_ID_LENGTH = 32;
    private static final int MAX_PERMANENT_CODE_LENGTH = 512;

    private final WeComInstallationMapper mapper;
    private final WeComCredentialProtector protector;
    private final WeComChannelAccountLifecycle channelAccounts;

    @Autowired
    public WeComInstallationImportService(WeComInstallationMapper mapper,
                                           WeComCredentialProtector protector,
                                           WeComChannelAccountLifecycle channelAccounts) {
        this.mapper = mapper;
        this.protector = protector;
        this.channelAccounts = channelAccounts;
    }

    WeComInstallationImportService(WeComInstallationMapper mapper,
                                   WeComCredentialProtector protector) {
        this(mapper, protector, null);
    }

    @Transactional
    public ImportResult importFile(Path file, String suiteId) {
        String normalizedSuiteId = value(suiteId, "suiteId", MAX_CORP_ID_LENGTH);
        Values values = read(file);
        WeComInstallationEntity existing = mapper.findBySuiteAndAuthCorpId(
                normalizedSuiteId, values.authCorpId());
        if (existing != null) {
            String currentPermanentCode = plaintext(existing.getPermanentCode());
            if ("ACTIVE".equals(existing.getAuthStatus())
                    && existing.getDeletedAt() == null
                    && values.agentId().equals(existing.getAgentId())
                    && values.permanentCode().equals(currentPermanentCode)) {
                ensureChannelAccount(values.authCorpId());
                return result("unchanged", existing, normalizedSuiteId, values.agentId(), version(existing));
            }
            String encrypted = values.permanentCode().equals(currentPermanentCode)
                    ? existing.getPermanentCode()
                    : protector.protectPermanentCode(values.permanentCode());
            long expectedVersion = version(existing);
            if (existing.getId() == null || mapper.updateImported(existing.getId(), values.agentId(), encrypted,
                    "ACTIVE", Instant.now(), expectedVersion) != 1) {
                throw failure("安装记录版本冲突，请重新执行导入");
            }
            ensureChannelAccount(values.authCorpId());
            return result("updated", existing, normalizedSuiteId, values.agentId(), expectedVersion + 1);
        }

        String encrypted = protector.protectPermanentCode(values.permanentCode());
        WeComInstallationEntity created = new WeComInstallationEntity();
        created.setId(UUID.randomUUID());
        created.setSuiteId(normalizedSuiteId);
        created.setAuthCorpId(values.authCorpId());
        created.setAgentId(values.agentId());
        created.setPermanentCode(encrypted);
        created.setAuthStatus("ACTIVE");
        created.setAuthorizedAt(Instant.now());
        created.setVersion(1L);
        if (mapper.insert(created) != 1) {
            throw failure("安装记录写入失败，可能存在并发导入");
        }
        ensureChannelAccount(values.authCorpId());
        return result("created", created, normalizedSuiteId, values.agentId(), 1L);
    }

    private void ensureChannelAccount(String authCorpId) {
        if (channelAccounts != null) channelAccounts.ensureActive(authCorpId);
    }

    private String plaintext(String stored) {
        if (stored == null || stored.isBlank()) return "";
        return protector.isEnvelope(stored) ? protector.revealPermanentCode(stored) : stored;
    }

    private Values read(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            throw invalid("导入文件不存在或不是普通文件");
        }
        try (InputStream input = Files.newInputStream(file)) {
            byte[] bytes = readBounded(input);
            Properties properties = new Properties();
            properties.load(new java.io.ByteArrayInputStream(bytes));
            return new Values(
                    value(properties.getProperty("authCorpId"), "authCorpId", MAX_CORP_ID_LENGTH),
                    value(properties.getProperty("agentId"), "agentId", MAX_AGENT_ID_LENGTH),
                    value(properties.getProperty("permanentCode"), "permanentCode", MAX_PERMANENT_CODE_LENGTH));
        } catch (WeComException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new WeComException("WECOM_INSTALLATION_IMPORT_INVALID", 400,
                    "企业微信安装 Properties 文件无法读取", exception);
        }
    }

    private static byte[] readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            total += read;
            if (total > MAX_FILE_BYTES) throw invalid("企业微信安装文件超过 16 KiB 限制");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static String value(String raw, String name, int maxLength) {
        String normalized = raw == null ? "" : raw.trim();
        if (normalized.isBlank() || normalized.length() > maxLength) {
            throw invalid(name + " 缺失或长度无效");
        }
        return normalized;
    }

    private static long version(WeComInstallationEntity entity) {
        return entity.getVersion() == null ? 0L : entity.getVersion();
    }

    private static ImportResult result(String action, WeComInstallationEntity entity,
                                       String suiteId, String agentId, long version) {
        UUID id = entity.getId();
        if (id == null) throw failure("安装记录缺少 ID");
        return new ImportResult(action, id.toString(), suiteId, entity.getAuthCorpId(), agentId, version);
    }

    private static WeComException invalid(String message) {
        return new WeComException("WECOM_INSTALLATION_IMPORT_INVALID", 400, message);
    }

    private static WeComException failure(String message) {
        return new WeComException("WECOM_INSTALLATION_IMPORT_FAILED", 500, message);
    }

    private record Values(String authCorpId, String agentId, String permanentCode) {}

    public record ImportResult(String action, String installationId, String suiteId,
                               String authCorpId, String agentId, long version) {}
}
