# 联系人合并后的跨渠道同标题 Topic 自动融合实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 联系人合并时，自动归并同一联系人下跨渠道的同标题个人 Topic；标题和摘要内容一致时直接融合，内容不一致时调用现有 AI 融合流程。

**Architecture:** `ContactGroupService` 只负责联系人事务和调用 `AiTopicContactMergeReconciler`；`AiTopicService` 作为 Topic 业务 owner，负责迁移来源、规范化标题分组、内容比较、直接融合和 AI 融合。直接融合复用现有 Topic 来源迁移及血缘写入结构，不复制来源；`STORED` 和 `WECOM_GROUP` 不参与。

**Tech Stack:** Java 17, Spring Boot, MyBatis-Plus, PostgreSQL, JUnit 5, Mockito, AssertJ, Maven.

## Global Constraints

- 标题规范化：去除首尾空格后使用 `Locale.ROOT` 转小写；不做模糊匹配、同义词匹配或渠道匹配。
- 内容比较使用规范化标题和去除首尾空格后的有效摘要；有效摘要优先 `confirmed_summary`，为空时使用 `ai_summary`。
- 仅 `READY` 与 `REVIEW_PENDING` 参与自动融合；`STORED` 与 `WECOM_GROUP` 不参与。
- 直接融合和 AI 融合都创建新 Topic ID、原位迁移全部来源、归档旧 Topic，并写入 `MERGED` / `MERGED_INTO` 血缘。
- 联系人合并、身份迁移和 Topic 归并必须在同一事务中完成；任一异常回滚整个合并。
- 保留当前用户未提交的 `created_by` owner 查询改动，不覆盖无关 WIP。

## File Map

- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicContactMergeReconciler.java` - 联系人合并到 Topic 归并的窄接口。
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicService.java` - 实现联系人合并归并、标题/摘要判断和直接融合。
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contact/ContactGroupService.java` - 在联系人合并事务内调用 Topic owner。
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/AiTopicMapper.java` - 提供来源 Topic 转移和目标联系人候选查询。
- Create: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicContactMergeReconciliationTest.java` - 直接融合、AI 条件和状态边界回归测试。
- Modify: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/contact/ContactGroupServiceTopicReconciliationTest.java` - 验证联系人服务调用 Topic owner 接口。
- Modify: `docs/superpowers/README.md` - 保持设计和计划索引完整。

### Task 1: Add the Topic-owner merge contract and mapper queries

**Files:**
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicContactMergeReconciler.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/AiTopicMapper.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/contact/ContactGroupServiceTopicReconciliationTest.java`

**Interfaces:**
- Produces `void reconcileAfterContactMerge(UUID sourceContactId, UUID targetContactId, UUID userId)`.
- Produces mapper methods `transferReviewPendingByContact(UUID, UUID)`, `listContactMergeCandidates(UUID)`, and retains `transferReadyByContact(UUID, UUID)` for compatibility tests and fallback construction paths.

- [ ] **Step 1: Write the failing contract tests**

Add reflection assertions for the new mapper SQL:

```java
@Test
void mergeCandidateQueryOnlyIncludesPersonalReadyAndPendingTopics() throws Exception {
    Method method = AiTopicMapper.class.getMethod("listContactMergeCandidates", UUID.class);
    String sql = String.join(" ", method.getAnnotation(Select.class).value());
    assertThat(sql).contains("owner_type='CONTACT'")
            .contains("status in ('READY','REVIEW_PENDING')")
            .doesNotContain("STORED");
}

@Test
void pendingMergeTransferKeepsReviewPendingSemantics() throws Exception {
    Method method = AiTopicMapper.class.getMethod("transferReviewPendingByContact", UUID.class, UUID.class);
    String sql = String.join(" ", method.getAnnotation(Update.class).value());
    assertThat(sql).contains("status='REVIEW_PENDING'")
            .contains("review_origin='MERGE_SOURCE'")
            .contains("owner_type='CONTACT'");
}
```

Add a service-owner delegation test using the seven-argument constructor plus the new reconciler dependency:

```java
@Test
void mergeDelegatesTopicReconciliationToTopicOwner() {
    AiTopicContactMergeReconciler reconciler = mock(AiTopicContactMergeReconciler.class);
    ContactGroupService service = new ContactGroupService(contacts, identities, null, topics, items,
            activities, null, reconciler);
    service.merge(sourceId, targetId, userId);
    verify(reconciler).reconcileAfterContactMerge(sourceId, targetId, userId);
}
```

- [ ] **Step 2: Run the focused tests and verify the expected compile failure**

Run:

```bash
mvn -q -Dtest='com.crmforlogistics.messagecenter.service.contact.ContactGroupServiceTopicReconciliationTest' test
```

Expected: compilation fails because the new interface, constructor overload, and mapper methods do not exist.

- [ ] **Step 3: Add the contract and mapper SQL**

Create the interface:

```java
package com.crmforlogistics.messagecenter.service.aitopic;

import java.util.UUID;

public interface AiTopicContactMergeReconciler {
    void reconcileAfterContactMerge(UUID sourceContactId, UUID targetContactId, UUID userId);
}
```

Add mapper methods:

```java
@Select("select * from ai_topics where owner_type='CONTACT' and owner_id=#{contactId}::uuid "
        + "and status in ('READY','REVIEW_PENDING') order by last_occurred_at, id")
List<AiTopicEntity> listContactMergeCandidates(@Param("contactId") UUID contactId);

@Update("update ai_topics set owner_id=#{targetContactId}::uuid, contact_id=#{targetContactId}::uuid, "
        + "review_origin='MERGE_SOURCE', review_source_contact_id=#{sourceContactId}::uuid, "
        + "review_source_topic_id=id, version=version+1, updated_at=now() "
        + "where owner_type='CONTACT' and owner_id=#{sourceContactId}::uuid and status='REVIEW_PENDING'")
int transferReviewPendingByContact(@Param("sourceContactId") UUID sourceContactId,
                                   @Param("targetContactId") UUID targetContactId);
```

Extend `ContactGroupService` with an optional `AiTopicContactMergeReconciler` field and an eight-argument constructor. In `merge`, call `reconcileAfterContactMerge` after identity migration when the dependency exists; otherwise retain the existing `transferReadyByContact` fallback used by legacy unit constructors. The production `@Autowired` constructor must receive the new interface.

- [ ] **Step 4: Run the focused contract tests**

Run the same Maven command. Expected: PASS, including existing `transferReadyByContact` assertions.

### Task 2: Add direct and conditional fusion behavior in `AiTopicService`

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicService.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicContactMergeReconciliationTest.java`

**Interfaces:**
- `AiTopicService` implements both `AiTopicSplitReconciler` and `AiTopicContactMergeReconciler`.
- Public package method: `@Transactional void reconcileAfterContactMerge(UUID sourceContactId, UUID targetContactId, UUID userId)`.

- [ ] **Step 1: Write failing behavior tests**

Create tests with mocked mappers and a full `AiTopicService` constructor:

```java
@Test
void identicalCrossChannelTopicsFuseWithoutCallingAi() {
    AiTopicEntity email = topic(emailId, targetId, "  报价  ", "报价摘要", "READY", 1L);
    AiTopicEntity phone = topic(phoneId, sourceId, "报价", " 报价摘要 ", "REVIEW_PENDING", 2L);
    when(topics.listContactMergeCandidates(targetId)).thenReturn(List.of(email, phone));
    when(items.listByTopic(emailId)).thenReturn(List.of(emailItem));
    when(items.listByTopic(phoneId)).thenReturn(List.of(phoneItem));
    when(items.listByTopic(any(UUID.class))).thenReturn(List.of());

    service.reconcileAfterContactMerge(sourceId, targetId, userId);

    verify(gateway, never()).fuse(any());
    verify(topics).insert(any(AiTopicEntity.class));
    verify(topics).archiveForFusion(emailId);
    verify(topics).archiveForFusion(phoneId);
    verify(items, times(2)).updateById(any(AiTopicItemEntity.class));
}

@Test
void sameTitleWithDifferentSummaryCallsAiFusion() {
    when(topics.listContactMergeCandidates(targetId)).thenReturn(List.of(
            topic(firstId, targetId, "报价", "摘要一", "READY", 1L),
            topic(secondId, targetId, " 报价 ", "摘要二", "REVIEW_PENDING", 1L)));
    when(reviews.listTopicSources(List.of(firstId, secondId))).thenReturn(List.of(
            new AiTopicReviewMapper.SourceRow(sourceId1, identityId, "MESSAGE", "email", occurredAt,
                    "inbound", "", "内容一", true, null),
            new AiTopicReviewMapper.SourceRow(sourceId2, identityId, "MESSAGE", "phone", occurredAt,
                    "inbound", "", "内容二", true, null)));
    when(gateway.fuse(any())).thenReturn(new AiTopicModels.GenerationOutput(List.of(
            new AiTopicModels.TopicAssignment("fusion", "报价综合", "AI 融合摘要", 1d,
                    List.of(sourceId1, sourceId2)))));

    service.reconcileAfterContactMerge(sourceId, targetId, userId);

    verify(gateway).fuse(any());
}

@Test
void storedAndGroupTopicsAreNotCandidates() {
    when(topics.listContactMergeCandidates(targetId)).thenReturn(List.of());
    service.reconcileAfterContactMerge(sourceId, targetId, userId);
    verify(gateway, never()).fuse(any());
    verify(topics, never()).insert(any(AiTopicEntity.class));
}
```

- [ ] **Step 2: Run the new tests and verify they fail for the missing behavior**

Run:

```bash
mvn -q -Dtest='com.crmforlogistics.messagecenter.service.aitopic.AiTopicContactMergeReconciliationTest' test
```

Expected: compilation fails because `AiTopicService` does not implement the new contract and the direct path does not exist.

- [ ] **Step 3: Implement the minimal reconciliation flow**

Implement these steps in `AiTopicService`:

```java
@Override
@Transactional
public void reconcileAfterContactMerge(UUID sourceContactId, UUID targetContactId, UUID userId) {
    topicMapper.transferReadyByContact(sourceContactId, targetContactId);
    topicMapper.transferReviewPendingByContact(sourceContactId, targetContactId);
    List<AiTopicEntity> candidates = topicMapper.listContactMergeCandidates(targetContactId);
    Map<String, List<AiTopicEntity>> groups = candidates.stream()
            .filter(topic -> "CONTACT".equals(normalizedOwnerType(topic)))
            .collect(Collectors.groupingBy(topic -> normalizeTitle(topic.getTitle()), LinkedHashMap::new, Collectors.toList()));
    for (List<AiTopicEntity> group : groups.values()) {
        if (group.size() < 2) continue;
        List<AiTopicEntity> topics = group.stream()
                .sorted(Comparator.comparing(AiTopicEntity::getFirstOccurredAt,
                        Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(AiTopicEntity::getId))
                .toList();
        Map<UUID, Long> versions = topics.stream().collect(Collectors.toMap(
                AiTopicEntity::getId, topic -> topic.getVersion() == null ? 1L : topic.getVersion()));
        AiTopicManualReviewResponse.Assignment assignment;
        if (sameCurrentContent(topics)) {
            AiTopicEntity first = topics.get(0);
            assignment = new AiTopicManualReviewResponse.Assignment(
                    "direct-merge", first.getTitle().trim(), effectiveSummary(first), 1d,
                    topicSourceIds(topics));
        } else {
            assignment = generateFusionAssignment(topics);
        }
        applyFusion(userId, topics, versions, assignment.title(), assignment.summary());
    }
}
```

Add `normalizeTitle`, `effectiveSummary`, `sameCurrentContent`, and `topicSourceIds` helpers. `sameCurrentContent` compares normalized title and trimmed effective summary. Direct groups must never call `generateFusionAssignment` or `topicAiGateway`.

Update `applyFusion` so callers can preserve a direct merge's effective summary without introducing a second migration implementation. The existing AI callers pass `null` for `confirmedSummary`; the direct caller passes the first non-null confirmed summary when the effective summaries are equal. The new Topic keeps status `READY`, owner `CONTACT`, earliest/latest source times, and existing `MERGED` / `MERGED_INTO` version writes.

- [ ] **Step 4: Run the new tests until green**

Run:

```bash
mvn -q -Dtest='com.crmforlogistics.messagecenter.service.aitopic.AiTopicContactMergeReconciliationTest' test
```

Expected: all direct, AI, and status-boundary tests pass.

### Task 3: Complete integration regression coverage and documentation

**Files:**
- Modify: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/contact/ContactGroupServiceTopicReconciliationTest.java`
- Modify: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicFusionMergeTest.java` only if shared constructor/setup requires it
- Modify: `docs/superpowers/README.md` only if the plan link is not already present

- [ ] **Step 1: Add transaction integration assertions**

Verify the contact service delegates after identity migration and still marks the source contact merged. Add a failure test where the reconciler throws `AiTopicException` and assert `contactMapper.updateById(source)` is never called, proving the service does not mark the source merged after Topic reconciliation failure.

- [ ] **Step 2: Run the focused cross-module suite**

Run:

```bash
mvn -q -Dtest='com.crmforlogistics.messagecenter.service.aitopic.AiTopicContactMergeReconciliationTest,com.crmforlogistics.messagecenter.service.aitopic.AiTopicFusionMergeTest,com.crmforlogistics.messagecenter.service.aitopic.AiTopicServiceRegressionTest,com.crmforlogistics.messagecenter.service.contact.ContactGroupServiceTopicReconciliationTest,com.crmforlogistics.messagecenter.service.contact.ContactGroupServiceReviewPendingTest' test
```

Expected: all new tests pass; any existing owner-isolation failures must be distinguished from this change and not masked.

- [ ] **Step 3: Compile and run diff checks**

Run:

```bash
mvn -q -DskipTests compile
git diff --check -- demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicContactMergeReconciler.java demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicService.java demo/message-center-spring/backend/src/main/java/com/crmforlogistics/message-center/service/contact/ContactGroupService.java demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/AiTopicMapper.java demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/aitopic/AiTopicContactMergeReconciliationTest.java demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/contact/ContactGroupServiceTopicReconciliationTest.java
```

Expected: compile exit code `0` and no whitespace errors in the touched files.

- [ ] **Step 4: Update the design index if needed and report remaining deployment risk**

Keep the approved design at `docs/superpowers/specs/2026-09-08-ai-topic-contact-merge-auto-fusion-design.md`. Report that no real database/container validation is available unless the local environment provides it; do not claim production trace validation from unit tests.
