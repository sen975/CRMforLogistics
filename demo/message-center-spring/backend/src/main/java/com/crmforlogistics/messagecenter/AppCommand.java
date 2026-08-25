package com.crmforlogistics.messagecenter;

import com.crmforlogistics.messagecenter.service.wecom.WeComInstallationImportService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

final class AppCommand {
    static final String IMPORT_INSTALL = "wecom-import-install";

    private AppCommand() {}

    static Path importFile(String[] args) {
        int count = 0;
        String value = null;
        for (int index = 1; index < args.length; index++) {
            if ("--file".equals(args[index])) {
                count++;
                if (index + 1 < args.length) value = args[++index];
            }
        }
        if (count != 1 || value == null || value.isBlank()) {
            throw new IllegalArgumentException("--file is required exactly once");
        }
        return Path.of(value).toAbsolutePath().normalize();
    }

    static String render(WeComInstallationImportService.ImportResult result) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("action", result.action());
        output.put("installationId", result.installationId());
        output.put("suiteId", result.suiteId());
        output.put("authCorpId", result.authCorpId());
        output.put("agentId", result.agentId());
        output.put("version", result.version());
        try {
            return new ObjectMapper().writeValueAsString(output);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("导入结果序列化失败", exception);
        }
    }
}
