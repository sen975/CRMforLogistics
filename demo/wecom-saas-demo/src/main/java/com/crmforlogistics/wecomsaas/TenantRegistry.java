package com.crmforlogistics.wecomsaas;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class TenantRegistry {
    private final Path tenantFile;

    public TenantRegistry(Config config) {
        this.tenantFile = config.dataDir().resolve("tenants.jsonl");
    }

    public List<Tenant> tenants() throws IOException {
        return JsonSupport.readJsonl(tenantFile, Tenant.class);
    }

    public void ensureSeedTenants() throws IOException {
        if (!tenants().isEmpty()) {
            return;
        }
        Tenant tenant = new Tenant();
        tenant.id = "tenant-demo";
        tenant.name = "悦为物流演示企业";
        tenant.corpId = "ww_demo_corp";
        tenant.agentId = "1000002";
        tenant.suiteId = "suite_demo";
        tenant.installed = true;
        tenant.scopes = new ArrayList<>(List.of("customer", "message", "kf", "archive", "data_api"));
        tenant.tokenStatus = "local_mock";
        JsonSupport.appendJsonl(tenantFile, tenant);
    }
}
