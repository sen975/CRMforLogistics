package com.crmforlogistics.messagecenter.service.channel;

import com.crmforlogistics.messagecenter.channel.chatapp.ChatAppAccountCredentials;
import com.crmforlogistics.messagecenter.channel.chatapp.ChatAppAccountCredentialsResolver;
import com.crmforlogistics.messagecenter.channel.chatapp.ChatAppCapabilityProbe;
import com.crmforlogistics.messagecenter.dto.response.ChatAppCapabilityReport;
import com.crmforlogistics.messagecenter.entity.ChannelAccountEntity;
import com.crmforlogistics.messagecenter.entity.ChatAppCapabilityResultEntity;
import com.crmforlogistics.messagecenter.mapper.ChannelAccountMapper;
import com.crmforlogistics.messagecenter.mapper.ChatAppCapabilityResultMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class ChatAppCapabilityService {
    private final ChannelAccountMapper accounts;
    private final ChatAppAccountCredentialsResolver credentialsResolver;
    private final ChatAppCapabilityProbe probe;
    private final ChatAppCapabilityResultMapper results;

    public ChatAppCapabilityService(ChannelAccountMapper accounts,
                                    ChatAppAccountCredentialsResolver credentialsResolver,
                                    ChatAppCapabilityProbe probe,
                                    ChatAppCapabilityResultMapper results) {
        this.accounts = accounts;
        this.credentialsResolver = credentialsResolver;
        this.probe = probe;
        this.results = results;
    }

    public ChatAppCapabilityReport probeReadOnly(UUID accountId) {
        Context context = context(accountId);
        return persist(attach(probe.probeReadOnly(context.credentials(),
                context.credentials().chatappFrom()), context.account(), null));
    }

    public ChatAppCapabilityReport addNumber(UUID accountId, ChatAppCapabilityProbe.AddNumberCommand command) {
        Context context = context(accountId);
        return persist(attach(probe.addTestNumber(context.credentials(), command),
                context.account(), command.phoneNumber()), command.phoneNumber());
    }

    public ChatAppCapabilityReport sendCode(UUID accountId, ChatAppCapabilityProbe.SendCodeCommand command) {
        Context context = context(accountId);
        return persist(attach(probe.sendVerificationCode(context.credentials(), command),
                context.account(), command.phoneNumber()), command.phoneNumber());
    }

    public ChatAppCapabilityReport verify(UUID accountId, ChatAppCapabilityProbe.VerifyCommand command) {
        Context context = context(accountId);
        return persist(attach(probe.verifyAndRegister(context.credentials(), command),
                context.account(), command.phoneNumber()), command.phoneNumber());
    }

    public ChatAppCapabilityReport migrationReview() {
        return persist(probe.migrationReview());
    }

    public ChatAppCapabilityReport latest() {
        List<ChatAppCapabilityResultEntity> rows = results.findLatestReport();
        if (rows == null || rows.isEmpty()) {
            return new ChatAppCapabilityReport(null, null, null, "READ_ONLY", false, null, List.of());
        }
        ChatAppCapabilityResultEntity first = rows.get(0);
        List<ChatAppCapabilityReport.ActionResult> actions = rows.stream().map(row ->
                new ChatAppCapabilityReport.ActionResult(row.getAction(), row.getStatus(),
                        value(row.getProviderRequestId()), value(row.getDiagnosticCode()),
                        value(row.getDiagnosticMessage()))).toList();
        return new ChatAppCapabilityReport(first.getReportId(), first.getChannelAccountId(),
                first.getProviderScopeId(), first.getPhase(),
                actions.stream().allMatch(action -> "VERIFIED".equals(action.status())),
                first.getTestedAt(), actions);
    }

    private Context context(UUID accountId) {
        ChannelAccountEntity account = accountId == null ? null : accounts.selectById(accountId);
        if (account == null || account.getDeletedAt() != null
                || !("chatapp".equalsIgnoreCase(account.getChannelType())
                || "whatsapp".equalsIgnoreCase(account.getChannelType()))) {
            throw new ChannelAccountException("WHATSAPP_ACCOUNT_REQUIRED", HttpStatus.NOT_FOUND);
        }
        return new Context(account, credentialsResolver.resolve(account));
    }

    private ChatAppCapabilityReport attach(ChatAppCapabilityReport report,
                                           ChannelAccountEntity account, String phoneNumber) {
        return new ChatAppCapabilityReport(report.id(), account.getId(), account.getProviderScopeId(),
                report.phase(), report.ready(), report.testedAt(), report.results());
    }

    private ChatAppCapabilityReport persist(ChatAppCapabilityReport report) {
        return persist(report, null);
    }

    private ChatAppCapabilityReport persist(ChatAppCapabilityReport report, String testedPhone) {
        for (ChatAppCapabilityReport.ActionResult action : report.results()) {
            ChatAppCapabilityResultEntity entity = new ChatAppCapabilityResultEntity();
            entity.setId(UUID.randomUUID());
            entity.setReportId(report.id());
            entity.setChannelAccountId(report.accountId());
            entity.setProviderScopeId(report.scopeId());
            entity.setPhase(report.phase());
            entity.setAction(action.action());
            entity.setStatus(action.status());
            entity.setProviderRequestId(emptyToNull(action.providerRequestId()));
            entity.setDiagnosticCode(emptyToNull(action.diagnosticCode()));
            entity.setDiagnosticMessage(emptyToNull(action.diagnosticMessage()));
            entity.setTestedPhoneLast4(last4(testedPhone));
            entity.setTestedAt(report.testedAt() == null ? Instant.now() : report.testedAt());
            results.insert(entity);
        }
        return report;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    private static String last4(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.isBlank()) return null;
        String digits = phoneNumber.replaceAll("\\D", "");
        return digits.substring(Math.max(0, digits.length() - 4));
    }

    private record Context(ChannelAccountEntity account, ChatAppAccountCredentials credentials) { }
}
