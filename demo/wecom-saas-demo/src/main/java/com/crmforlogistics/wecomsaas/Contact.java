package com.crmforlogistics.wecomsaas;

import java.util.ArrayList;
import java.util.List;

public class Contact {
    public String id;
    public String tenantId;
    public String displayName;
    public String type;
    public String externalUserId;
    public String memberUserId;
    public String kfOpenId;
    public List<String> tags = new ArrayList<>();
    public List<String> channels = new ArrayList<>();
    public String lastText;
    public String lastAt;
    public String rawJson;
}
