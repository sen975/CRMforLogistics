package com.crmforlogistics.messagecenter;

import java.time.Duration;
import java.util.List;

interface ChatAppTemplateGateway extends AutoCloseable {
    TemplatePage listTemplates(int pageIndex, int pageSize, Duration timeout) throws Exception;

    TemplateStore.TemplateRecord getTemplateDetail(TemplateSummary summary, Duration timeout) throws Exception;

    @Override
    void close();

    record TemplatePage(List<TemplateSummary> templates, Integer total) {}

    record TemplateSummary(String templateCode, String templateName, String language, String templateType) {}

    interface Factory {
        ChatAppTemplateGateway open() throws Exception;
    }
}
