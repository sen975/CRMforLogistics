package com.crmforlogistics.messagecenter;

import java.util.ArrayList;
import java.util.List;

public class UnifiedContact {
    public String id;
    public String displayName;
    public String lastText;
    public String lastTime;
    public int messageCount;
    public List<String> channels = new ArrayList<>();
    public List<ContactPoint> points = new ArrayList<>();
}