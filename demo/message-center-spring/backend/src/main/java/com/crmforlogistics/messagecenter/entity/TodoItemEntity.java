package com.crmforlogistics.messagecenter.entity;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

public class TodoItemEntity {
    private UUID id;
    private UUID userId;
    private LocalDate dueDate;
    private String title;
    private LocalTime dueTime;
    private String note;
    private boolean completed;
    /** 「开始前 N 小时」提醒的发送标记；null 表示尚未提醒过。当天汇总不走这里。 */
    private Instant leadReminderSentAt;
    private Instant createdAt;
    private Instant updatedAt;
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public LocalDate getDueDate() { return dueDate; }
    public void setDueDate(LocalDate dueDate) { this.dueDate = dueDate; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public LocalTime getDueTime() { return dueTime; }
    public void setDueTime(LocalTime dueTime) { this.dueTime = dueTime; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public boolean isCompleted() { return completed; }
    public void setCompleted(boolean completed) { this.completed = completed; }
    public Instant getLeadReminderSentAt() { return leadReminderSentAt; }
    public void setLeadReminderSentAt(Instant leadReminderSentAt) { this.leadReminderSentAt = leadReminderSentAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
