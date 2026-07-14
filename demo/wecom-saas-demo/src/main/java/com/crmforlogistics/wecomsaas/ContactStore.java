package com.crmforlogistics.wecomsaas;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class ContactStore {
    private final Path contactFile;

    public ContactStore(Config config) {
        this.contactFile = config.dataDir().resolve("contacts.jsonl");
    }

    public List<Contact> contacts() throws IOException {
        return JsonSupport.readJsonl(contactFile, Contact.class);
    }

    public Contact find(String contactId) throws IOException {
        for (Contact contact : contacts()) {
            if (contact.id != null && contact.id.equals(contactId)) {
                return contact;
            }
        }
        return null;
    }

    public void upsert(Contact contact) throws IOException {
        List<Contact> contacts = new ArrayList<>(contacts());
        boolean replaced = false;
        for (int index = 0; index < contacts.size(); index++) {
            if (contacts.get(index).id != null && contacts.get(index).id.equals(contact.id)) {
                contacts.set(index, contact);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            contacts.add(contact);
        }
        rewriteContacts(contacts);
    }

    public void ensureSeedContacts() throws IOException {
        if (!contacts().isEmpty()) {
            return;
        }
        JsonSupport.appendJsonl(contactFile, contact("contact-ext-001", "深圳海运客户", "external",
                "wm_external_001", "", "", List.of("重点客户", "海运"), List.of("wecom_app", "archive"),
                "会话存档：报价已确认", "2026-07-14T09:10:00Z",
                "{\"source\":\"seed\",\"external_userid\":\"wm_external_001\"}"));
        JsonSupport.appendJsonl(contactFile, contact("contact-kf-001", "官网访客 Mina", "kf_visitor",
                "", "", "kf_openid_001", List.of("微信客服"), List.of("wecom_kf"),
                "访客进线咨询报价", "2026-07-14T09:20:00Z",
                "{\"source\":\"seed\",\"kf_openid\":\"kf_openid_001\"}"));
        JsonSupport.appendJsonl(contactFile, contact("contact-member-001", "销售同事 Alex", "member",
                "", "alex", "", List.of("内部成员"), List.of("wecom_app", "archive"),
                "请跟进华南客户", "2026-07-14T09:30:00Z",
                "{\"source\":\"seed\",\"userid\":\"alex\"}"));
        JsonSupport.appendJsonl(contactFile, contact("contact-archive", "会话存档导入", "archive",
                "", "", "", List.of("存档"), List.of("archive"),
                "等待导入会话存档", "2026-07-14T09:40:00Z",
                "{\"source\":\"seed\",\"archive_bucket\":\"local_jsonl\"}"));
    }

    private static Contact contact(String id, String displayName, String type, String externalUserId,
                                   String memberUserId, String kfOpenId, List<String> tags,
                                   List<String> channels, String lastText, String lastAt, String rawJson) {
        Contact contact = new Contact();
        contact.id = id;
        contact.tenantId = "tenant-demo";
        contact.displayName = displayName;
        contact.type = type;
        contact.externalUserId = externalUserId;
        contact.memberUserId = memberUserId;
        contact.kfOpenId = kfOpenId;
        contact.tags = new ArrayList<>(tags);
        contact.channels = new ArrayList<>(channels);
        contact.lastText = lastText;
        contact.lastAt = lastAt;
        contact.rawJson = rawJson;
        return contact;
    }

    private void rewriteContacts(List<Contact> contacts) throws IOException {
        Files.deleteIfExists(contactFile);
        for (Contact contact : contacts) {
            JsonSupport.appendJsonl(contactFile, contact);
        }
    }
}
