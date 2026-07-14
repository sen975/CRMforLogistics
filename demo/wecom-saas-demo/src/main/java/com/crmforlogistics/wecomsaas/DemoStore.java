package com.crmforlogistics.wecomsaas;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class DemoStore {
    private final TenantRegistry tenantRegistry;
    private final ContactStore contactStore;
    private final MessageStore messageStore;
    private final Path syncJobFile;

    public DemoStore(Config config) {
        this.tenantRegistry = new TenantRegistry(config);
        this.contactStore = new ContactStore(config);
        this.messageStore = new MessageStore(config);
        this.syncJobFile = config.dataDir().resolve("sync-jobs.jsonl");
    }

    public void ensureSeedData() throws IOException {
        tenantRegistry.ensureSeedTenants();
        contactStore.ensureSeedContacts();
        messageStore.ensureSeedMessages();
        ensureSeedSyncJobs();
    }

    public List<Tenant> tenants() throws IOException {
        return tenantRegistry.tenants();
    }

    public List<Contact> contacts() throws IOException {
        return contactStore.contacts();
    }

    public Contact findContact(String contactId) throws IOException {
        return contactStore.find(contactId);
    }

    public void upsertContact(Contact contact) throws IOException {
        contactStore.upsert(contact);
    }

    public List<MessageRecord> messagesForContact(String contactId) throws IOException {
        return messageStore.messagesForContact(contactId);
    }

    public MessageRecord findMessage(String id) throws IOException {
        return messageStore.findMessage(id);
    }

    public void appendMessage(MessageRecord message) throws IOException {
        messageStore.appendMessage(message);
    }

    public List<AttachmentRecord> attachments() throws IOException {
        return messageStore.attachments();
    }

    public void appendAttachment(AttachmentRecord attachment) throws IOException {
        messageStore.appendAttachment(attachment);
    }

    public List<SyncJob> syncJobs() throws IOException {
        return JsonSupport.readJsonl(syncJobFile, SyncJob.class);
    }

    public void upsertSyncJob(SyncJob job) throws IOException {
        List<SyncJob> jobs = new ArrayList<>(syncJobs());
        boolean replaced = false;
        for (int index = 0; index < jobs.size(); index++) {
            if (jobs.get(index).name != null && jobs.get(index).name.equals(job.name)) {
                jobs.set(index, job);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            jobs.add(job);
        }
        rewriteSyncJobs(jobs);
    }

    private void ensureSeedSyncJobs() throws IOException {
        if (!syncJobs().isEmpty()) {
            return;
        }
        JsonSupport.appendJsonl(syncJobFile, syncJob("external_contact_sync", "客户数据 API 同步",
                "idle", "cursor-0", "2026-07-14T09:35:00Z",
                "{\"contacts\":4,\"groups\":1,\"tags\":4}", ""));
        JsonSupport.appendJsonl(syncJobFile, syncJob("archive_import", "会话内容存档导入",
                "idle", "seq-0", "2026-07-14T09:45:00Z",
                "{\"messages\":1,\"source\":\"seed\"}", ""));
    }

    private void rewriteSyncJobs(List<SyncJob> jobs) throws IOException {
        Files.deleteIfExists(syncJobFile);
        for (SyncJob job : jobs) {
            JsonSupport.appendJsonl(syncJobFile, job);
        }
    }

    private static SyncJob syncJob(String name, String label, String status, String cursor,
                                   String lastSyncAt, String lastResult, String error) {
        SyncJob job = new SyncJob();
        job.name = name;
        job.label = label;
        job.status = status;
        job.cursor = cursor;
        job.lastSyncAt = lastSyncAt;
        job.lastResult = lastResult;
        job.error = error;
        return job;
    }
}
