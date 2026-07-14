package com.crmforlogistics.wecomsaas;

import java.util.ArrayList;
import java.util.List;

public class Tenant {
    public String id;
    public String name;
    public String corpId;
    public String agentId;
    public String suiteId;
    public boolean installed;
    public List<String> scopes = new ArrayList<>();
    public String tokenStatus;
}
