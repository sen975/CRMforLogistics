# 联系人标签搜索实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在统一会话列表、渠道通讯录和发送页选择器三处提供「联系人」/「标签」搜索模式，标签模式下只按标签名模糊匹配联系人。

**Architecture:** 三个列表接口各新增可选参数 `searchMode`，值透传为 mapper 的 `boolean tagSearch`，在现有搜索 SQL 里用 `<choose>` 二选一分支替换匹配条件；命中标签名由 `ContactTagMatchResolver` 用一条批量查询回填到响应行的 `matchedTags` 字段。联系人模式走 `<otherwise>` 分支，逐字保留现有条件，行为零变化。

**Tech Stack:** Java 21 + Spring Boot + MyBatis（注解 SQL，`<script>` + OGNL）、PostgreSQL 17 + Flyway、JUnit 5 + Testcontainers；React 18 + TypeScript + antd 5 + TanStack Query + Vitest。

## Global Constraints

- `searchMode` 取值只有 `contact` 和 `tag`，缺省（不传/空串）等于 `contact`。
- 未知取值必须返回 HTTP 400，错误消息为 `CONTACT_SEARCH_MODE_INVALID`。本仓库的既有约定是抛 `IllegalArgumentException("CODE")`，由 `GlobalExceptionHandler.handleBadRequest` 映射为 `ApiError(code="BAD_REQUEST", message="CODE")`（先例见 `ContactService:122` 的 `CHATAPP_CONTACT_ACCOUNT_INACCESSIBLE`），照此办理。
- 标签归属恒为当前登录用户的 `contact_tags.owner_user_id`，管理员不例外。
- 只匹配 `status = 'active'` 的标签。
- 联系人模式的行为必须与改动前逐字一致。
- 标签模式下企微群聊不出现在结果中。
- 关键词为 null 或空白时，两个模式都走「无搜索」分支。
- 关键词里的 `%`、`_` 保持通配符语义（现有行为，不修）。
- 不新增数据库迁移，`contact_tags` / `contact_taggings` 表已存在。
- 不改变任何列表的排序规则。

---

## 文件结构

**后端新建**

| 文件 | 职责 |
| --- | --- |
| `backend/src/main/java/com/crmforlogistics/messagecenter/dto/request/SearchMode.java` | 搜索模式枚举与解析/校验 |
| `backend/src/main/java/com/crmforlogistics/messagecenter/service/contact/ContactTagMatchResolver.java` | 给定 owner + 本页联系人 id + 关键词，返回命中的标签名 |
| `backend/src/test/java/com/crmforlogistics/messagecentertest/mapper/ContactTagMapperTestConfiguration.java` | 集成测试用的 ContactTagMapper bean |
| `backend/src/test/java/com/crmforlogistics/messagecenter/service/contact/ContactTagMatchResolverIntegrationTest.java` | 回填逻辑的真库测试 |
| `backend/src/test/java/com/crmforlogistics/messagecenter/mapper/ConversationMapperTagSearchIntegrationTest.java` | 统一会话列表标签搜索的真库测试 |
| `backend/src/test/java/com/crmforlogistics/messagecenter/mapper/ContactMapperTagSearchIntegrationTest.java` | 通讯录与联系人列表标签搜索的真库测试 |

**后端修改**

| 文件 | 改动 |
| --- | --- |
| `mapper/ConversationMapper.java:84-86`（CONTACT 搜索）、`:151-153`（GROUP 搜索）、`:180-186`（`listUnified` 签名） | 加 `tagSearch` 分支 |
| `mapper/ContactMapper.java:66-71` / `:76-80`（listAddressBookByOwner 搜索与签名）、`:147-149` / `:165-172`（listForUser 搜索与签名） | 加 `tagSearch` 分支 |
| `mapper/ContactTagMapper.java` | 新增 `findMatchedByContactIds` 与 `MatchedTagRow` |
| `dto/response/ConversationListItemResponse.java` | 加 `matchedTags` + `withMatchedTags` |
| `dto/response/ChannelAddressBookItem.java` | 加 `matchedTags` + `withMatchedTags` |
| `dto/response/ContactResponse.java` | 加 `matchedTags` + `withMatchedTags` |
| `service/conversation/UnifiedConversationService.java:18-38` | 透传 `searchMode`、回填 |
| `service/conversation/ConversationPreferenceService.java:66-67` | 传 `false` |
| `service/contact/ChannelAddressBookService.java:28-54`、`:99-101`、`:272` | 透传 `searchMode`、回填 |
| `service/contact/ContactService.java:57-91`（构造器）、`:108-142`（listForUser）、`:235`（toResponse） | 透传 `searchMode`、回填 |
| `web/ConversationController.java:32-38` | 解析 `searchMode` |
| `web/ChannelAddressBookController.java:31-38` | 解析 `searchMode` |
| `web/ContactController.java:55-67` | 解析 `searchMode` |
| `test/.../service/conversation/UnifiedConversationServiceTest.java` | stub 签名 |
| `test/.../service/conversation/ConversationPreferenceServiceTest.java:99` | stub 签名 |
| `test/.../service/contact/ContactServiceAuthorizationTest.java:71,96,174,237,246` | stub 与反射签名（`:217` 反射的是被保留的八参重载，不动） |
| `test/.../service/contact/ChannelAddressBookServiceTest.java:31,38` | stub 签名 |
| `test/.../mapper/ContactMapperChatAppFilterIntegrationTest.java:92,95` | 调用签名 |

**前端新建**

| 文件 | 职责 |
| --- | --- |
| `frontend/src/components/SearchModeSwitch.tsx` | 「联系人 ▾ / 标签 ▾」模式选择控件 |
| `frontend/src/components/SearchModeSwitch.test.tsx` | 控件测试 |
| `frontend/src/components/ContactCard.matched-tags.test.tsx` | 命中标签 chip 渲染测试 |
| `frontend/src/components/ChannelAddressBookList.matched-tags.test.tsx` | 通讯录命中标签 chip 渲染测试 |
| `frontend/src/pages/ContactsPage.search-mode.test.tsx` | 模式切换、关键词清空、空态文案测试 |

**前端修改**

| 文件 | 改动 |
| --- | --- |
| `api/types.ts` | `SearchMode` 类型；三个响应类型加 `matchedTags?` |
| `api/endpoints.ts:452,463,492` | 三个函数加 `searchMode` 参数 |
| `hooks/useContacts.ts` | 三个 hook 加 `searchMode` 参数与 query key |
| `components/ContactCard.tsx` | 渲染命中标签 chip |
| `pages/ContactsPage.tsx` | 模式状态、控件、placeholder、空态文案 |
| `pages/ChannelAddressBookPage.tsx` | 同上 |
| `components/ChannelAddressBookList.tsx` | 「联系人」列渲染命中标签 chip |
| `pages/SendPage.tsx` | 模式状态、控件、选项 chip、空态文案 |
| `pages/ContactsPage.test.tsx:44` | `useUnifiedConversations` 断言补第二参 |
| `pages/ChannelAddressBookPage.test.tsx:68,79` | `useChannelAddressBook` 断言补第四参 |
| `pages/SendPage.test.tsx` | 新增模式切换用例 |

---

## Task 1: 标签匹配基础（SearchMode + 命中标签查询）

**Files:**
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/request/SearchMode.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ContactTagMapper.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contact/ContactTagMatchResolver.java`
- Create: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecentertest/mapper/ContactTagMapperTestConfiguration.java`
- Create: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/dto/request/SearchModeTest.java`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/contact/ContactTagMatchResolverIntegrationTest.java`

**Interfaces:**
- Consumes: 无（首个任务）
- Produces:
  - `enum SearchMode { CONTACT, TAG }`，静态方法 `SearchMode parse(String raw)`，实例方法 `boolean isTag()`
  - `ContactTagMapper.findMatchedByContactIds(UUID ownerId, Collection<UUID> contactIds, String search) -> List<ContactTagMapper.MatchedTagRow>`
  - `record ContactTagMapper.MatchedTagRow(UUID contactId, String name)`
  - `ContactTagMatchResolver(UUID ownerId, Collection<UUID> contactIds, String search, SearchMode searchMode) -> Map<UUID, List<String>>`，方法名 `matchNamesByContact`

- [ ] **Step 1: 写失败的测试**

创建 `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecentertest/mapper/ContactTagMapperTestConfiguration.java`：

```java
package com.crmforlogistics.messagecentertest.mapper;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.crmforlogistics.messagecenter.mapper.ContactTagMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration(proxyBeanMethods = false)
@ImportAutoConfiguration({
        DataSourceAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class,
        JdbcTemplateAutoConfiguration.class,
        MybatisPlusAutoConfiguration.class
})
public class ContactTagMapperTestConfiguration {
    @Bean
    MapperFactoryBean<ContactTagMapper> contactTagMapper(SqlSessionFactory sqlSessionFactory) {
        MapperFactoryBean<ContactTagMapper> mapperFactory = new MapperFactoryBean<>(ContactTagMapper.class);
        mapperFactory.setSqlSessionFactory(sqlSessionFactory);
        return mapperFactory;
    }
}
```

创建 `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/contact/ContactTagMatchResolverIntegrationTest.java`：

```java
package com.crmforlogistics.messagecenter.service.contact;

import com.crmforlogistics.messagecenter.dto.request.SearchMode;
import com.crmforlogistics.messagecenter.mapper.ContactTagMapper;
import com.crmforlogistics.messagecentertest.mapper.ContactTagMapperTestConfiguration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = ContactTagMapperTestConfiguration.class)
@Testcontainers
class ContactTagMatchResolverIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.5")
            .withDatabaseName("message_center")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("mybatis-plus.type-handlers-package",
                () -> "com.crmforlogistics.messagecenter.typehandler");
    }

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();
    }

    @Autowired ContactTagMapper tagMapper;
    @Autowired JdbcTemplate jdbc;

    private UUID ownerId;
    private UUID otherUserId;

    @BeforeEach
    void setUp() {
        jdbc.update("truncate table contact_taggings, contact_tags, contacts, users cascade");
        ownerId = UUID.randomUUID();
        otherUserId = UUID.randomUUID();
        insertUser(ownerId, "owner");
        insertUser(otherUserId, "other");
    }

    @Test
    void returnsOnlyMatchedActiveTagsOfTheCurrentOwner() {
        // 标签名刻意用「A级客户」「B类客户」这种 ASCII 前缀：命中顺序断言要依赖
        // order by lower(t.name)，而中文与拉丁字母的相对顺序随数据库 collation 变化，
        // 用 ASCII 前缀才能在任何 collation 下稳定。
        UUID contactId = insertContact("张经理", ownerId);
        attachTag(contactId, insertTag(ownerId, "A级客户", "active"));
        attachTag(contactId, insertTag(ownerId, "B类客户", "active"));
        attachTag(contactId, insertTag(ownerId, "上海", "active"));
        ContactTagMatchResolver resolver = new ContactTagMatchResolver(tagMapper);

        assertThat(resolver.matchNamesByContact(ownerId, List.of(contactId), "客户", SearchMode.TAG))
                .containsExactlyInAnyOrderEntriesOf(
                        java.util.Map.of(contactId, List.of("A级客户", "B类客户")));
    }

    @Test
    void returnsNothingOutsideTagModeAndForBlankOrMissingInput() {
        UUID contactId = insertContact("张经理", ownerId);
        attachTag(contactId, insertTag(ownerId, "VIP客户", "active"));
        ContactTagMatchResolver resolver = new ContactTagMatchResolver(tagMapper);

        assertThat(resolver.matchNamesByContact(ownerId, List.of(contactId), "客户", SearchMode.CONTACT))
                .isEmpty();
        assertThat(resolver.matchNamesByContact(ownerId, List.of(contactId), "   ", SearchMode.TAG))
                .isEmpty();
        assertThat(resolver.matchNamesByContact(ownerId, List.of(contactId), null, SearchMode.TAG))
                .isEmpty();
        assertThat(resolver.matchNamesByContact(ownerId, List.of(), "客户", SearchMode.TAG))
                .isEmpty();
    }

    @Test
    void ignoresDisabledTagsAndTagsOwnedByAnotherUser() {
        UUID contactId = insertContact("李四", ownerId);
        attachTag(contactId, insertTag(ownerId, "停用客户", "disabled"));
        attachTag(contactId, insertTag(otherUserId, "客户", "active"));
        ContactTagMatchResolver resolver = new ContactTagMatchResolver(tagMapper);

        assertThat(resolver.matchNamesByContact(ownerId, List.of(contactId), "客户", SearchMode.TAG))
                .isEmpty();
    }

    private void insertUser(UUID id, String name) {
        jdbc.update("insert into users (id, username, username_normalized, password_hash, display_name) "
                + "values (?, ?, ?, 'hash', ?)", id, name + id, name + id, name);
    }

    private UUID insertContact(String name, UUID createdBy) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into contacts (id, display_name, created_by) values (?, ?, ?)",
                id, name, createdBy);
        return id;
    }

    private UUID insertTag(UUID ownerId, String name, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into contact_tags (id, owner_user_id, name, color, status) "
                + "values (?, ?, ?, 'blue', ?)", id, ownerId, name, status);
        return id;
    }

    private void attachTag(UUID contactId, UUID tagId) {
        jdbc.update("insert into contact_taggings (contact_id, tag_id) values (?, ?)", contactId, tagId);
    }
}
```

创建 `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/dto/request/SearchModeTest.java`（纯单测，不起容器）：

```java
package com.crmforlogistics.messagecenter.dto.request;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SearchModeTest {

    @Test
    void defaultsToContactWhenAbsentOrBlank() {
        assertThat(SearchMode.parse(null)).isEqualTo(SearchMode.CONTACT);
        assertThat(SearchMode.parse("")).isEqualTo(SearchMode.CONTACT);
        assertThat(SearchMode.parse("   ")).isEqualTo(SearchMode.CONTACT);
    }

    @Test
    void parsesBothKnownValuesCaseInsensitively() {
        assertThat(SearchMode.parse("contact")).isEqualTo(SearchMode.CONTACT);
        assertThat(SearchMode.parse("CONTACT")).isEqualTo(SearchMode.CONTACT);
        assertThat(SearchMode.parse(" tag ")).isEqualTo(SearchMode.TAG);
        assertThat(SearchMode.parse("TAG")).isEqualTo(SearchMode.TAG);
        assertThat(SearchMode.parse("TAG").isTag()).isTrue();
        assertThat(SearchMode.parse("contact").isTag()).isFalse();
    }

    @Test
    void rejectsUnknownValuesInsteadOfSilentlyFallingBackToContact() {
        assertThatThrownBy(() -> SearchMode.parse("tags"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("CONTACT_SEARCH_MODE_INVALID");
        assertThatThrownBy(() -> SearchMode.parse("label"))
                .hasMessage("CONTACT_SEARCH_MODE_INVALID");
    }
}
```

> 这个用例锁住规范里「未知取值返回 HTTP 400 + `CONTACT_SEARCH_MODE_INVALID`、不静默降级」那条：抛出 `IllegalArgumentException("CONTACT_SEARCH_MODE_INVALID")` 后，`GlobalExceptionHandler.handleBadRequest`（`web/GlobalExceptionHandler.java:67-72`）会把它映射成 HTTP 400 与 `ApiError(code="BAD_REQUEST", message="CONTACT_SEARCH_MODE_INVALID")`。

- [ ] **Step 2: 运行测试确认失败**

Run: `cd demo/message-center-spring/backend && mvn -q -Dtest='SearchModeTest,ContactTagMatchResolverIntegrationTest' test`
Expected: 编译失败，`cannot find symbol: class SearchMode` 与 `cannot find symbol: class ContactTagMatchResolver`

- [ ] **Step 3: 新建 SearchMode**

创建 `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/request/SearchMode.java`：

```java
package com.crmforlogistics.messagecenter.dto.request;

import java.util.Locale;

/** 列表搜索的匹配范围：联系人字段，或联系人标签。 */
public enum SearchMode {
    CONTACT,
    TAG;

    public static SearchMode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return CONTACT;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "contact" -> CONTACT;
            case "tag" -> TAG;
            default -> throw new IllegalArgumentException("CONTACT_SEARCH_MODE_INVALID");
        };
    }

    public boolean isTag() {
        return this == TAG;
    }
}
```

- [ ] **Step 4: 给 ContactTagMapper 加批量命中查询**

在 `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ContactTagMapper.java` 的类体内、最后一个方法之后追加（同时新增 `import java.util.Collection;`）：

```java
    @Select("""
            <script>
            select ct.contact_id as contact_id, t.name as name
            from contact_taggings ct
            join contact_tags t on t.id = ct.tag_id
            where t.owner_user_id = #{ownerId}::uuid
              and t.status = 'active'
              and t.name ilike '%' || #{search} || '%'
              and ct.contact_id in
              <foreach item="contactId" collection="contactIds" open="(" separator="," close=")">
                #{contactId}::uuid
              </foreach>
            order by lower(t.name), t.id
            </script>
            """)
    List<MatchedTagRow> findMatchedByContactIds(@Param("ownerId") UUID ownerId,
                                                 @Param("contactIds") Collection<UUID> contactIds,
                                                 @Param("search") String search);

    record MatchedTagRow(UUID contactId, String name) {}
```

- [ ] **Step 5: 新建 ContactTagMatchResolver**

创建 `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contact/ContactTagMatchResolver.java`：

```java
package com.crmforlogistics.messagecenter.service.contact;

import com.crmforlogistics.messagecenter.dto.request.SearchMode;
import com.crmforlogistics.messagecenter.mapper.ContactTagMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 标签模式下，把本页联系人命中的标签名批量取回，用于在结果行上解释命中原因。
 * 非标签模式、空关键词、空 id 列表都不查库。
 */
@Service
public class ContactTagMatchResolver {

    private final ContactTagMapper tagMapper;

    public ContactTagMatchResolver(ContactTagMapper tagMapper) {
        this.tagMapper = tagMapper;
    }

    public Map<UUID, List<String>> matchNamesByContact(UUID ownerId, Collection<UUID> contactIds,
                                                       String search, SearchMode searchMode) {
        if (!searchMode.isTag() || search == null || search.isBlank() || contactIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<String>> matched = new LinkedHashMap<>();
        for (ContactTagMapper.MatchedTagRow row
                : tagMapper.findMatchedByContactIds(ownerId, contactIds, search)) {
            matched.computeIfAbsent(row.contactId(), key -> new ArrayList<>()).add(row.name());
        }
        return matched;
    }
}
```

- [ ] **Step 6: 运行测试确认通过**

Run: `cd demo/message-center-spring/backend && mvn -q -Dtest='SearchModeTest,ContactTagMatchResolverIntegrationTest' test`
Expected: `SearchModeTest` 3 个用例通过，`ContactTagMatchResolverIntegrationTest` 3 个用例通过，`Failures: 0, Errors: 0`，BUILD SUCCESS

- [ ] **Step 7: 提交**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/request/SearchMode.java \
        demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contact/ContactTagMatchResolver.java \
        demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ContactTagMapper.java \
        demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecentertest/mapper/ContactTagMapperTestConfiguration.java \
        demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/dto/request/SearchModeTest.java \
        demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/contact/ContactTagMatchResolverIntegrationTest.java
git commit -m "feat: add contact tag match resolver"
```

---

## Task 2: 统一会话列表支持标签搜索

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ConversationMapper.java`（`listUnified` 的 CONTACT 搜索 84-86 行、GROUP 搜索 151-153 行、方法签名 180-186 行）
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/ConversationListItemResponse.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/conversation/UnifiedConversationService.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/conversation/ConversationPreferenceService.java:66`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/ConversationController.java:32-38`
- Modify: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/conversation/UnifiedConversationServiceTest.java`
- Modify: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/conversation/ConversationPreferenceServiceTest.java:99`
- Modify: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/mapper/ConversationMapperSourceConversationSqlTest.java`（追加一个 SQL 渲染用例，锁住标签模式下群聊被排除）
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/mapper/ConversationMapperTagSearchIntegrationTest.java`

**Interfaces:**
- Consumes: Task 1 的 `SearchMode`、`ContactTagMatchResolver.matchNamesByContact(UUID, Collection<UUID>, String, SearchMode)`
- Produces:
  - `ConversationMapper.listUnified(UUID userId, String search, boolean tagSearch, Boolean cursorPinned, Long cursorRank, String cursorAt, String cursorKey, int limit) -> List<UnifiedConversationRow>`
  - `ConversationListItemResponse` 多一个末位组件 `List<String> matchedTags`，以及 `ConversationListItemResponse withMatchedTags(List<String> matchedTags)`
  - `UnifiedConversationService.list(UUID userId, String search, SearchMode searchMode, String cursor, int limit)`

- [ ] **Step 1: 写失败的真库测试**

创建 `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/mapper/ConversationMapperTagSearchIntegrationTest.java`：

```java
package com.crmforlogistics.messagecenter.mapper;

import com.crmforlogistics.messagecentertest.mapper.ConversationMapperAssignmentTestConfiguration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = ConversationMapperAssignmentTestConfiguration.class)
@Testcontainers
class ConversationMapperTagSearchIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.5")
            .withDatabaseName("message_center")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("mybatis-plus.type-handlers-package",
                () -> "com.crmforlogistics.messagecenter.typehandler");
    }

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();
    }

    @Autowired ConversationMapper conversationMapper;
    @Autowired JdbcTemplate jdbc;

    private UUID userId;

    @BeforeEach
    void setUp() {
        jdbc.update("truncate table contact_taggings, contact_tags, contact_identities, contacts, "
                + "users cascade");
        userId = UUID.randomUUID();
        jdbc.update("insert into users (id, username, username_normalized, password_hash, display_name) "
                + "values (?, ?, ?, 'hash', 'agent')", userId, "agent" + userId, "agent" + userId);
    }

    @Test
    void tagModeMatchesOnlyTaggedContactsAndIgnoresNameMatches() {
        UUID tagged = insertContact("张经理", userId);
        attachTag(tagged, insertTag(userId, "VIP客户", "active"));
        UUID namedLikeTag = insertContact("VIP", userId);

        List<UUID> tagModeIds = idsOf(conversationMapper.listUnified(
                userId, "VIP", true, null, null, null, null, 20));
        List<UUID> contactModeIds = idsOf(conversationMapper.listUnified(
                userId, "VIP", false, null, null, null, null, 20));

        assertThat(tagModeIds).containsExactly(tagged);
        assertThat(contactModeIds).containsExactly(namedLikeTag);
    }

    @Test
    void tagModeIgnoresDisabledTagsAndTagsOwnedByAnotherUser() {
        UUID target = insertContact("李四", userId);
        attachTag(target, insertTag(userId, "停用客户", "disabled"));

        assertThat(idsOf(conversationMapper.listUnified(
                userId, "客户", true, null, null, null, null, 20))).isEmpty();

        UUID otherUser = UUID.randomUUID();
        jdbc.update("insert into users (id, username, username_normalized, password_hash, display_name) "
                + "values (?, ?, ?, 'hash', 'other')", otherUser, "other" + otherUser,
                "other" + otherUser);
        attachTag(target, insertTag(otherUser, "客户", "active"));

        assertThat(idsOf(conversationMapper.listUnified(
                userId, "客户", true, null, null, null, null, 20))).isEmpty();
    }

    private List<UUID> idsOf(List<ConversationMapper.UnifiedConversationRow> rows) {
        return rows.stream()
                .filter(row -> "CONTACT".equals(row.type()))
                .map(ConversationMapper.UnifiedConversationRow::id)
                .toList();
    }

    private UUID insertContact(String name, UUID createdBy) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into contacts (id, display_name, created_by) values (?, ?, ?)",
                id, name, createdBy);
        jdbc.update("insert into contact_identities (id, contact_id, channel_type, identity_scope, "
                + "identity_value, normalized_value) values (?, ?, 'email', 'global', ?, ?)",
                UUID.randomUUID(), id, name + "@example.com", name + "@example.com");
        return id;
    }

    private UUID insertTag(UUID ownerId, String name, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into contact_tags (id, owner_user_id, name, color, status) "
                + "values (?, ?, ?, 'blue', ?)", id, ownerId, name, status);
        return id;
    }

    private void attachTag(UUID contactId, UUID tagId) {
        jdbc.update("insert into contact_taggings (contact_id, tag_id) values (?, ?)", contactId, tagId);
    }
}
```

> 说明：`contact_tags` 上 V1 迁移建的 `ux_contact_tags_name`（全局唯一 `lower(name)`）没有在 V47 被删除，所以同一个标签名不能给两个用户各建一份——上面第二个测试改用不同名字规避，断言点仍是「不属于当前用户的标签搜不到」。

再往 `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/mapper/ConversationMapperSourceConversationSqlTest.java` 末尾（最后一个 `}` 之前）追加一个用例。这个文件已有 `unifiedListAnnotationIsValidMyBatisXml`，用 `XMLLanguageDriver` 渲染动态 SQL，正好可以廉价地锁住「标签模式下群聊分支被 `and 1 = 0` 关掉」这条验收项——真库造一个企微群需要 installations / bindings / participants / parties 一整套夹具，代价远大于收益：

```java
    @Test
    void tagSearchModeExcludesGroupsAndDropsTheNameBranch() throws Exception {
        java.util.Map<String, Object> params = new java.util.HashMap<>();
        params.put("userId", UUID.randomUUID());
        params.put("search", "客户");
        params.put("tagSearch", true);
        params.put("cursorPinned", null);
        params.put("cursorRank", null);
        params.put("cursorAt", null);
        params.put("cursorKey", null);
        params.put("limit", 20);

        String tagSql = renderUnifiedList(params);
        assertThat(tagSql).contains("and 1 = 0");
        assertThat(tagSql).contains("from contact_taggings ct");
        assertThat(tagSql).contains("t.owner_user_id = ?::uuid");
        assertThat(tagSql).doesNotContain("coalesce(c.remark, '') ilike");

        params.put("tagSearch", false);
        String contactSql = renderUnifiedList(params);
        assertThat(contactSql).doesNotContain("and 1 = 0");
        assertThat(contactSql).contains("coalesce(c.remark, '') ilike");
        assertThat(contactSql).doesNotContain("from contact_taggings ct");
    }

    private static String renderUnifiedList(java.util.Map<String, Object> params) throws Exception {
        String script = String.join(" ", java.util.Arrays.stream(ConversationMapper.class.getMethods())
                .filter(method -> method.getName().equals("listUnified"))
                .findFirst().orElseThrow()
                .getAnnotation(Select.class)
                .value());
        var sqlSource = new XMLLanguageDriver()
                .createSqlSource(new Configuration(), script, java.util.Map.class);
        return sqlSource.getBoundSql(params).getSql().toLowerCase().replaceAll("\\s+", " ");
    }
```

- [ ] **Step 2: 运行确认失败**

Run: `cd demo/message-center-spring/backend && mvn -q -Dtest='ConversationMapperTagSearchIntegrationTest,ConversationMapperSourceConversationSqlTest' test`
Expected:
- `ConversationMapperTagSearchIntegrationTest` 编译失败，`listUnified` 参数个数不匹配（实际 7 个，期望 8 个）
- `ConversationMapperSourceConversationSqlTest` 的 `tagSearchModeExcludesGroupsAndDropsTheNameBranch` 失败：渲染结果里没有 `and 1 = 0`（尚未加 `<when test="tagSearch">` 分支）

- [ ] **Step 3: 给 listUnified 加 tagSearch 参数与分支**

在 `ConversationMapper.java` 中，把 CONTACT 分支的搜索条件（当前 84-86 行）：

```java
            <if test="search != null and search != ''">
              and (c.display_name ilike '%' || #{search} || '%' or coalesce(c.remark, '') ilike '%' || #{search} || '%')
            </if>
```

替换为：

```java
            <if test="search != null and search != ''">
              and ( <choose>
                <when test="tagSearch">
                  exists (select 1 from contact_taggings ct
                          join contact_tags t on t.id = ct.tag_id
                          where ct.contact_id = c.id
                            and t.status = 'active'
                            and t.owner_user_id = #{userId}::uuid
                            and t.name ilike '%' || #{search} || '%')
                </when>
                <otherwise>
                  c.display_name ilike '%' || #{search} || '%' or coalesce(c.remark, '') ilike '%' || #{search} || '%'
                </otherwise>
              </choose> )
            </if>
```

把 GROUP 分支的搜索条件（当前 151-153 行）：

```java
            <if test="search != null and search != ''">
            and """ + " " + SAFE_GROUP_DISPLAY_NAME + " ilike '%' || #{search} || '%'\n" + """
            </if>
```

替换为：

```java
            <if test="search != null and search != ''">
            <choose>
              <when test="tagSearch">
              and 1 = 0
              </when>
              <otherwise>
              and """ + " " + SAFE_GROUP_DISPLAY_NAME + " ilike '%' || #{search} || '%'\n" + """
              </otherwise>
            </choose>
            </if>
```

把方法签名（当前 180-186 行）改为：

```java
    List<UnifiedConversationRow> listUnified(@Param("userId") UUID userId,
                                             @Param("search") String search,
                                             @Param("tagSearch") boolean tagSearch,
                                             @Param("cursorPinned") Boolean cursorPinned,
                                             @Param("cursorRank") Long cursorRank,
                                             @Param("cursorAt") String cursorAt,
                                             @Param("cursorKey") String cursorKey,
                                             @Param("limit") int limit);
```

- [ ] **Step 4: 运行确认 SQL 测试通过**

Run: `cd demo/message-center-spring/backend && mvn -q -Dtest='ConversationMapperTagSearchIntegrationTest,ConversationMapperSourceConversationSqlTest' test`
Expected: BUILD SUCCESS，两个类全部通过（标签模式既排除了群聊，也走到了标签子查询）

- [ ] **Step 5: 给 ConversationListItemResponse 加 matchedTags**

把 `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/ConversationListItemResponse.java` 整体替换为：

```java
package com.crmforlogistics.messagecenter.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Discriminated item used by the unified conversation workspace. */
public record ConversationListItemResponse(
        String type,
        UUID id,
        String displayName,
        String remark,
        String avatarUrl,
        List<String> channelTypes,
        Instant lastMessageAt,
        String lastText,
        int messageCount,
        int unreadCount,
        String providerConversationKey,
        int participantCount,
        boolean pinned,
        List<String> matchedTags
) {
    public static ConversationListItemResponse contact(UUID id, String displayName,
                                                        Instant lastMessageAt, String lastText,
                                                        int messageCount, int unreadCount) {
        return contact(id, displayName, null, lastMessageAt, lastText, messageCount, unreadCount);
    }

    public static ConversationListItemResponse contact(UUID id, String displayName, String remark,
                                                        Instant lastMessageAt, String lastText,
                                                        int messageCount, int unreadCount) {
        return new ConversationListItemResponse("CONTACT", id, displayName, remark, null, List.of(),
                lastMessageAt, lastText, messageCount, unreadCount, null, 0, false, List.of());
    }

    public static ConversationListItemResponse group(UUID id, String displayName,
                                                      String providerConversationKey,
                                                      Instant lastMessageAt, String lastText,
                                                      int messageCount, int unreadCount,
                                                      int participantCount) {
        return new ConversationListItemResponse("WECOM_GROUP", id, displayName, null, null, List.of("wecom"),
                lastMessageAt, lastText, messageCount, unreadCount,
                providerConversationKey, participantCount, false, List.of());
    }

    public ConversationListItemResponse withMatchedTags(List<String> tags) {
        return new ConversationListItemResponse(type, id, displayName, remark, avatarUrl, channelTypes,
                lastMessageAt, lastText, messageCount, unreadCount, providerConversationKey,
                participantCount, pinned, tags == null ? List.of() : tags);
    }
}
```

同时把 `ConversationMapper.java` 中 `UnifiedConversationRow.toResponse()` 里的构造调用末尾补上 `, List.of()`：

```java
        public ConversationListItemResponse toResponse() {
            List<String> channels = channelTypes == null || channelTypes.isBlank()
                    ? List.of() : Arrays.stream(channelTypes.split(","))
                    .filter(value -> !value.isBlank()).sorted().toList();
            return new ConversationListItemResponse(type, id, displayName, remark, avatarUrl, channels,
                    lastMessageAt, lastText, messageCount, unreadCount,
                    providerConversationKey, participantCount, pinned, List.of());
        }
```

- [ ] **Step 6: 把 UnifiedConversationService 与 ConversationPreferenceService 接上**

把 `UnifiedConversationService.java` 的**第 1-38 行**（package 到 `list` 方法结束）整体替换为下面这段。`encodeCursor`（第 40 行起）、`decodeCursor`（第 49 行起）、`CursorKey`（第 72 行）一行都不动：

```java
package com.crmforlogistics.messagecenter.service.conversation;

import com.crmforlogistics.messagecenter.dto.request.SearchMode;
import com.crmforlogistics.messagecenter.dto.response.ConversationListItemResponse;
import com.crmforlogistics.messagecenter.dto.response.ConversationPageResponse;
import com.crmforlogistics.messagecenter.mapper.ConversationMapper;
import com.crmforlogistics.messagecenter.service.contact.ContactTagMatchResolver;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class UnifiedConversationService {
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final ConversationMapper conversationMapper;
    private final ContactTagMatchResolver tagMatchResolver;

    public UnifiedConversationService(ConversationMapper conversationMapper,
                                      ContactTagMatchResolver tagMatchResolver) {
        this.conversationMapper = conversationMapper;
        this.tagMatchResolver = tagMatchResolver;
    }

    public ConversationPageResponse list(UUID userId, String search, SearchMode searchMode,
                                         String cursor, int limit) {
        int safeLimit = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        CursorKey key = decodeCursor(cursor);
        List<ConversationMapper.UnifiedConversationRow> rows = conversationMapper.listUnified(
                userId, search, searchMode.isTag(), key.pinned(), key.sortRank(), key.sortAt(),
                key.sortKey(), safeLimit + 1);
        boolean hasMore = rows.size() > safeLimit;
        List<ConversationMapper.UnifiedConversationRow> visibleRows = hasMore
                ? rows.subList(0, safeLimit) : rows;
        List<ConversationListItemResponse> records = visibleRows.stream()
                .map(ConversationMapper.UnifiedConversationRow::toResponse)
                .toList();
        records = withMatchedTags(userId, search, searchMode, records);
        String nextCursor = hasMore && !visibleRows.isEmpty()
                ? encodeCursor(visibleRows.get(visibleRows.size() - 1)) : null;
        return new ConversationPageResponse(records, records.size(), safeLimit, 1, 1, nextCursor);
    }

    private List<ConversationListItemResponse> withMatchedTags(
            UUID userId, String search, SearchMode searchMode,
            List<ConversationListItemResponse> records) {
        List<UUID> contactIds = records.stream()
                .filter(record -> "CONTACT".equals(record.type()))
                .map(ConversationListItemResponse::id)
                .toList();
        Map<UUID, List<String>> matched =
                tagMatchResolver.matchNamesByContact(userId, contactIds, search, searchMode);
        if (matched.isEmpty()) {
            return records;
        }
        return records.stream()
                .map(record -> record.withMatchedTags(matched.getOrDefault(record.id(), List.of())))
                .toList();
    }
```

在 `ConversationPreferenceService.java` 第 66-67 行的 `conversations.listUnified(` 调用里，在第二个实参（`search`，该处为 `null`）之后插入 `false`：

```java
        List<ConversationMapper.UnifiedConversationRow> current = conversations.listUnified(
                userId, null, false, null, null, null, null, MAX_REORDERABLE_CONVERSATIONS + 1);
```

- [ ] **Step 7: 修改 ConversationController**

把 `ConversationController.java` 的 `list` 方法替换为：

```java
    @GetMapping
    public ConversationPageResponse list(
            @RequestParam(value = "search", required = false) String search,
            @RequestParam(value = "searchMode", required = false) String rawSearchMode,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        UUID userId = SecurityUtil.currentUserId();
        return service.list(userId, search, SearchMode.parse(rawSearchMode), cursor, limit);
    }
```

并新增 import：`import com.crmforlogistics.messagecenter.dto.request.SearchMode;`

- [ ] **Step 8: 更新受影响的单测 stub**

`UnifiedConversationServiceTest.java` 共 4 处，逐条改：

1. 第 29 行 stub：`when(mapper.listUnified(eq(userId), eq(null), eq(null), eq(null), eq(null), eq(null), eq(11)))` 改为

```java
        when(mapper.listUnified(eq(userId), eq(null), eq(false), eq(null), eq(null), eq(null), eq(null), eq(11)))
```

2. 第 35 行构造服务：`new UnifiedConversationService(mapper)` 改为

```java
        UnifiedConversationService service = new UnifiedConversationService(mapper,
                new ContactTagMatchResolver(mock(ContactTagMapper.class)));
```

3. 第 36 行调用：`service.list(userId, null, null, 10)` 改为 `service.list(userId, null, SearchMode.CONTACT, null, 10)`

4. 第二个用例：第 55-56 行的 stub `when(mapper.listUnified(eq(userId), eq("alice"), eq(true), eq(7L), ...))` 在 `eq("alice")` 之后插入 `eq(false)`；第 59 行的 `new UnifiedConversationService(mapper)` 同 2 补上 resolver 实参；第 60 行 `service.list(userId, "alice", cursor, 20)` 改为 `service.list(userId, "alice", SearchMode.CONTACT, cursor, 20)`；第 62-63 行的 verify 改为

```java
        org.mockito.Mockito.verify(mapper).listUnified(
                userId, "alice", false, true, 7L, "2026-08-25T10:00:00Z", "CONTACT:" + rowId, 21);
```

并新增 import：`import com.crmforlogistics.messagecenter.dto.request.SearchMode;`、`import com.crmforlogistics.messagecenter.service.contact.ContactTagMatchResolver;`、`import com.crmforlogistics.messagecenter.mapper.ContactTagMapper;`。（`mock` 已由 `static org.mockito.Mockito.mock` 提供。）

> 注意第 55 行原本的第 3 个实参 `eq(true)` 是 `cursorPinned`，不是 `tagSearch`；`tagSearch` 是插在 `eq("alice")` 与它之间的新实参。

`ConversationPreferenceServiceTest.java:99`：`when(conversations.listUnified(userId, null, null, null, null, null, 201))` 改为 `when(conversations.listUnified(userId, null, false, null, null, null, null, 201))`。

- [ ] **Step 9: 运行测试与编译**

Run: `cd demo/message-center-spring/backend && mvn -q -Dtest='UnifiedConversationServiceTest,ConversationPreferenceServiceTest,ConversationMapperTagSearchIntegrationTest,ConversationMapperSourceConversationSqlTest' test`
Expected: BUILD SUCCESS，全部通过

- [ ] **Step 10: 提交**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ConversationMapper.java \
        demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/ConversationListItemResponse.java \
        demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/conversation/ \
        demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/ConversationController.java \
        demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/mapper/ConversationMapperTagSearchIntegrationTest.java \
        demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/conversation/
git commit -m "feat: support tag search in unified conversation list"
```

---

## Task 3: 渠道通讯录支持标签搜索

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ContactMapper.java:66-71`（`listAddressBookByOwner` 的搜索条件）与 `:76-80`（其签名）
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/ChannelAddressBookItem.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contact/ChannelAddressBookService.java:39-54`（`page`）、`:28-37`（字段与构造器）、`:99-101`、`:272`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/ChannelAddressBookController.java:31-38`
- Modify: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/contact/ChannelAddressBookServiceTest.java:31,34,38,276`
- Test: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/mapper/ContactMapperTagSearchIntegrationTest.java`（本任务新建，Task 4 往里加第二个用例）

**Interfaces:**
- Consumes: Task 1 的 `SearchMode`、`ContactTagMatchResolver.matchNamesByContact`
- Produces:
  - `ContactMapper.listAddressBookByOwner(UUID ownerId, String channelType, String query, boolean tagSearch, int limit, int offset)`
  - `ChannelAddressBookItem` 多一个末位组件 `List<String> matchedTags`，以及 `ChannelAddressBookItem withMatchedTags(List<String> matchedTags)`
  - `ChannelAddressBookService.page(UUID ownerId, String channelType, String query, SearchMode searchMode, int page, int size)`

> 本任务**只动 `listAddressBookByOwner`**，不动 `listForUser`。`listForUser` 的签名变更会让 `ContactService` 编译不过，而 `ContactService` 要到 Task 4 才改——两个 mapper 变更必须分在两个任务里，否则本任务永远到不了绿灯。`ContactMapperTagSearchIntegrationTest` 在 Task 4 会加第二个用例来覆盖 `listForUser`。

- [ ] **Step 1: 写失败的真库测试**

创建 `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/mapper/ContactMapperTagSearchIntegrationTest.java`：

```java
package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.crmforlogistics.messagecenter.entity.ContactEntity;
import com.crmforlogistics.messagecentertest.mapper.ContactMapperChatAppFilterTestConfiguration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = ContactMapperChatAppFilterTestConfiguration.class)
@Testcontainers
class ContactMapperTagSearchIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.5")
            .withDatabaseName("message_center")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("mybatis-plus.type-handlers-package",
                () -> "com.crmforlogistics.messagecenter.typehandler");
    }

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();
    }

    @Autowired ContactMapper contactMapper;
    @Autowired JdbcTemplate jdbc;

    private UUID userId;

    @BeforeEach
    void setUp() {
        jdbc.update("truncate table contact_taggings, contact_tags, contact_identities, contacts, "
                + "users cascade");
        userId = UUID.randomUUID();
        jdbc.update("insert into users (id, username, username_normalized, password_hash, display_name) "
                + "values (?, ?, ?, 'hash', 'agent')", userId, "agent" + userId, "agent" + userId);
    }

    @Test
    void addressBookTagModeMatchesOnlyTaggedContacts() {
        UUID tagged = insertContact("张经理", userId);
        attachTag(tagged, insertTag(userId, "VIP客户", "active"));
        UUID namedLikeTag = insertContact("VIP客户", userId);

        List<UUID> tagMode = contactMapper.listAddressBookByOwner(
                        userId, "email", "客户", true, 20, 0).stream()
                .map(ContactMapper.ChannelAddressBookRow::contactId).toList();
        List<UUID> contactMode = contactMapper.listAddressBookByOwner(
                        userId, "email", "客户", false, 20, 0).stream()
                .map(ContactMapper.ChannelAddressBookRow::contactId).toList();

        assertThat(tagMode).containsExactly(tagged);
        assertThat(contactMode).containsExactly(namedLikeTag);
    }

    private UUID insertContact(String name, UUID createdBy) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into contacts (id, display_name, created_by) values (?, ?, ?)",
                id, name, createdBy);
        jdbc.update("insert into contact_identities (id, contact_id, channel_type, identity_scope, "
                + "identity_value, normalized_value) values (?, ?, 'email', 'global', ?, ?)",
                UUID.randomUUID(), id, name + id + "@example.com", name + id + "@example.com");
        return id;
    }

    private UUID insertTag(UUID ownerId, String name, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into contact_tags (id, owner_user_id, name, color, status) "
                + "values (?, ?, ?, 'blue', ?)", id, ownerId, name, status);
        return id;
    }

    private void attachTag(UUID contactId, UUID tagId) {
        jdbc.update("insert into contact_taggings (contact_id, tag_id) values (?, ?)", contactId, tagId);
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd demo/message-center-spring/backend && mvn -q -Dtest=ContactMapperTagSearchIntegrationTest test`
Expected: 编译失败，`listAddressBookByOwner` 参数个数不匹配（实际 5 个，期望 6 个）

- [ ] **Step 3: 给 listAddressBookByOwner 加 tagSearch 分支**

`listAddressBookByOwner`（第 66-71 行）的搜索条件当前为：

```java
            <if test="query != null and query != ''">
              and (coalesce(c.remark, '') ilike '%' || #{query} || '%'
                   or c.display_name ilike '%' || #{query} || '%'
                   or coalesce(ci.display_name, '') ilike '%' || #{query} || '%'
                   or ci.identity_value ilike '%' || #{query} || '%')
            </if>
```

替换为：

```java
            <if test="query != null and query != ''">
              and ( <choose>
                <when test="tagSearch">
                  exists (select 1 from contact_taggings ct
                          join contact_tags t on t.id = ct.tag_id
                          where ct.contact_id = c.id
                            and t.status = 'active'
                            and t.owner_user_id = #{ownerId}::uuid
                            and t.name ilike '%' || #{query} || '%')
                </when>
                <otherwise>
                  coalesce(c.remark, '') ilike '%' || #{query} || '%'
                   or c.display_name ilike '%' || #{query} || '%'
                   or coalesce(ci.display_name, '') ilike '%' || #{query} || '%'
                   or ci.identity_value ilike '%' || #{query} || '%'
                </otherwise>
              </choose> )
            </if>
```

该方法签名整体替换为：

```java
    List<ChannelAddressBookRow> listAddressBookByOwner(@Param("ownerId") UUID ownerId,
                                                        @Param("channelType") String channelType,
                                                        @Param("query") String query,
                                                        @Param("tagSearch") boolean tagSearch,
                                                        @Param("limit") int limit,
                                                        @Param("offset") int offset);
```

**本任务只改 `listAddressBookByOwner`。** `ContactMapper.listForUser` 的同类改造放到 Task 4——因为改它会让 `ContactService.listForUser` 的调用点参数个数不符，而 `ContactService` 要等 Task 4 才更新，那样 Task 3 永远编译不过、拿不到绿。

- [ ] **Step 4: 运行确认 SQL 层面通过**

Run: `cd demo/message-center-spring/backend && mvn -q -Dtest=ContactMapperTagSearchIntegrationTest test`
Expected: 编译失败，`ChannelAddressBookService.page` 调用 `listAddressBookByOwner` 参数个数不匹配（实际 5 个，期望 6 个）。这一步只确认 mapper 层本身没有语法错误；Step 5、Step 6 完成后本测试必须通过

- [ ] **Step 5: 给 ChannelAddressBookItem 加 matchedTags**

把 `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/ChannelAddressBookItem.java` 整体替换为：

```java
package com.crmforlogistics.messagecenter.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ChannelAddressBookItem(
        UUID contactId,
        UUID identityId,
        String displayName,
        String remark,
        String channelType,
        String address,
        String channelDisplayName,
        List<String> additionalChannelTypes,
        String source,
        Instant lastContactAt,
        boolean hasActivity,
        boolean canDelete,
        List<String> matchedTags) {

    public ChannelAddressBookItem withMatchedTags(List<String> tags) {
        return new ChannelAddressBookItem(contactId, identityId, displayName, remark, channelType,
                address, channelDisplayName, additionalChannelTypes, source, lastContactAt,
                hasActivity, canDelete, tags == null ? List.of() : tags);
    }
}
```

- [ ] **Step 6: 更新 ChannelAddressBookService 的构造点并接上回填**

`ChannelAddressBookService.java` 的 `page` 方法替换为：

```java
    public ChannelAddressBookPageResponse page(UUID ownerId, String rawChannelType, String rawQuery,
                                                SearchMode searchMode,
                                                int requestedPage, int requestedSize) {
        String channelType = normalizeChannelType(rawChannelType);
        if (requestedPage > MAX_PAGE) {
            throw new ChannelAddressBookException("CONTACT_PAGE_OUT_OF_RANGE", HttpStatus.BAD_REQUEST);
        }
        int page = Math.max(1, requestedPage);
        int size = requestedSize <= 0 ? 20 : Math.min(requestedSize, MAX_PAGE_SIZE);
        String query = normalizeQuery(rawQuery);
        int offset = (page - 1) * size;
        List<ContactMapper.ChannelAddressBookRow> rows = contacts.listAddressBookByOwner(
                ownerId, channelType, query, searchMode.isTag(), size + 1, offset);
        boolean hasMore = rows.size() > size;
        List<ChannelAddressBookItem> items = rows.stream().limit(size).map(this::toItem).toList();
        items = withMatchedTags(ownerId, query, searchMode, items);
        return new ChannelAddressBookPageResponse(items, page, size, hasMore);
    }

    private List<ChannelAddressBookItem> withMatchedTags(UUID ownerId, String query,
                                                         SearchMode searchMode,
                                                         List<ChannelAddressBookItem> items) {
        List<UUID> contactIds = items.stream().map(ChannelAddressBookItem::contactId).distinct().toList();
        Map<UUID, List<String>> matched =
                tagMatchResolver.matchNamesByContact(ownerId, contactIds, query, searchMode);
        if (matched.isEmpty()) {
            return items;
        }
        return items.stream()
                .map(item -> item.withMatchedTags(matched.getOrDefault(item.contactId(), List.of())))
                .toList();
    }
```

在构造函数中追加 `ContactTagMatchResolver tagMatchResolver` 形参与赋值：

```java
    private final ContactMapper contacts;
    private final ContactIdentityMapper identities;
    private final ChannelAccountMapper accounts;
    private final ContactTagMatchResolver tagMatchResolver;

    public ChannelAddressBookService(ContactMapper contacts, ContactIdentityMapper identities,
                                     ChannelAccountMapper accounts,
                                     ContactTagMatchResolver tagMatchResolver) {
        this.contacts = contacts;
        this.identities = identities;
        this.accounts = accounts;
        this.tagMatchResolver = tagMatchResolver;
    }
```

新增 import：`import com.crmforlogistics.messagecenter.dto.request.SearchMode;`、`import java.util.Map;`（`java.util.List`、`java.util.UUID` 已在）。

把该类中两处 `new ChannelAddressBookItem(...)` 构造调用（`create` 内的约 99 行、`toItem` 内的约 272 行）末尾各补一个实参 `List.of()`。

- [ ] **Step 7: 修改 ChannelAddressBookController**

把 `page` 方法替换为：

```java
    @GetMapping
    public ChannelAddressBookPageResponse page(
            @PathVariable String channelType,
            @RequestParam(required = false) String query,
            @RequestParam(value = "searchMode", required = false) String rawSearchMode,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.page(SecurityUtil.currentUserId(), channelType, query,
                SearchMode.parse(rawSearchMode), page, size);
    }
```

新增 import：`import com.crmforlogistics.messagecenter.dto.request.SearchMode;`

- [ ] **Step 8: 更新 ChannelAddressBookServiceTest 的 stub**

该文件只有一个构造入口——末尾的私有辅助方法（第 273-277 行），改它即可，不必动 10 个用例：

```java
    private static ChannelAddressBookService service(ContactMapper contacts,
                                                       ContactIdentityMapper identities,
                                                       ChannelAccountMapper accounts) {
        return new ChannelAddressBookService(contacts, identities, accounts,
                mock(ContactTagMatchResolver.class));
    }
```

`pageIsOwnerScopedAndClampsRequestedSize` 三处：第 31 行 stub、第 34 行调用、第 38 行 verify 改为

```java
        when(contacts.listAddressBookByOwner(owner, "email", "buyer", false, 101, 0)).thenReturn(List.of());

        var result = service(contacts, mock(ContactIdentityMapper.class), mock(ChannelAccountMapper.class))
                .page(owner, "email", " buyer ", SearchMode.CONTACT, 1, 500);

        verify(contacts).listAddressBookByOwner(owner, "email", "buyer", false, 101, 0);
```

并新增 import：`import com.crmforlogistics.messagecenter.dto.request.SearchMode;`

- [ ] **Step 9: 运行确认通过**

Run: `cd demo/message-center-spring/backend && mvn -q -Dtest='ContactMapperTagSearchIntegrationTest,ChannelAddressBookServiceTest' test`
Expected: `addressBookTagModeMatchesOnlyTaggedContacts` 通过；`ChannelAddressBookServiceTest` 全部通过。此时 `ContactMapper.listForUser` 还未改，Task 4 才加 `contactListTagModeMatchesOnlyTaggedContacts`

- [ ] **Step 10: 提交**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ContactMapper.java \
        demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/ChannelAddressBookItem.java \
        demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contact/ChannelAddressBookService.java \
        demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/ChannelAddressBookController.java \
        demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/mapper/ContactMapperTagSearchIntegrationTest.java \
        demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/contact/ChannelAddressBookServiceTest.java
git commit -m "feat: support tag search in channel address book"
```

---

## Task 4: 联系人列表支持标签搜索

**Files:**
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ContactMapper.java:145-149`（`listForUser` 的搜索条件）与 `:165-172`（其签名）
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/ContactResponse.java`
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contact/ContactService.java:57-91`（构造器）、`:108-142`（listForUser）、`:235-248`（toResponse 的构造点）
- Modify: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/ContactController.java:55-67`
- Modify: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/mapper/ContactMapperTagSearchIntegrationTest.java`（Task 3 已建，本任务追加第二个用例）
- Modify: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/contact/ContactServiceAuthorizationTest.java:71,96,174,237,246`
- Modify: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/mapper/ContactMapperChatAppFilterIntegrationTest.java:92,95`

**Interfaces:**
- Consumes: Task 1 的 `SearchMode`、`ContactTagMatchResolver`；Task 3 已改好的 `ContactMapper.listAddressBookByOwner(UUID, String, String, boolean, int, int)`
- Produces:
  - `ContactMapper.listForUser(IPage<ContactEntity> page, UUID userId, String search, boolean tagSearch, Instant beforeLastMessageAt, UUID beforeId, boolean isAdmin, String channelType, UUID channelAccountId)`
  - `ContactResponse` 多一个末位组件 `List<String> matchedTags`，以及 `ContactResponse withMatchedTags(List<String> matchedTags)`
  - `ContactService.listForUser(UUID userId, String search, SearchMode searchMode, Instant beforeLastMessageAt, UUID beforeId, int page, int size, String channelType, UUID channelAccountId)`（9 参，新增）
  - 保留六参重载 `listForUser(UUID, String, Instant, UUID, int, int)`
  - 保留八参重载 `listForUser(UUID, String, Instant, UUID, int, int, String, UUID)`
  - 两个旧重载内部都按 `SearchMode.CONTACT` 委托到 9 参版本

  > 八参重载必须保留：`ContactServiceAuthorizationTest` 第 217 行用反射取的就是这个签名（`UUID, String, Instant, UUID, int, int, String, UUID`），第 243、264 行也直接调它。

- [ ] **Step 1a: 给真库测试补 listForUser 用例**

向 `ContactMapperTagSearchIntegrationTest`（Task 3 已建）追加第二个 `@Test` 和 `idsOf` 辅助方法（放在 `addressBookTagModeMatchesOnlyTaggedContacts` 之后、`insertContact` 之前）：

```java
    @Test
    void contactListTagModeMatchesOnlyTaggedContacts() {
        UUID tagged = insertContact("张经理", userId);
        attachTag(tagged, insertTag(userId, "VIP客户", "active"));
        UUID namedLikeTag = insertContact("VIP客户", userId);

        List<UUID> tagMode = idsOf(contactMapper.listForUser(new Page<>(1, 20), userId,
                "客户", true, null, null, false, null, null));
        List<UUID> contactMode = idsOf(contactMapper.listForUser(new Page<>(1, 20), userId,
                "客户", false, null, null, false, null, null));

        assertThat(tagMode).containsExactly(tagged);
        assertThat(contactMode).containsExactly(namedLikeTag);
    }

    private static List<UUID> idsOf(com.baomidou.mybatisplus.core.metadata.IPage<ContactEntity> page) {
        return page.getRecords().stream().map(ContactEntity::getId).toList();
    }
```

- [ ] **Step 1b: 写服务层的失败测试**

追加到 `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/service/contact/ContactServiceAuthorizationTest.java`：

```java
    @Test
    void tagModeSearchReturnsMatchedTagNamesOnlyForTaggedContacts() {
        UUID userId = UUID.randomUUID();
        UUID taggedId = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();
        ContactMapper contactMapper = mock(ContactMapper.class);
        ContactTagMatchResolver resolver = mock(ContactTagMatchResolver.class);
        ContactEntity tagged = new ContactEntity();
        tagged.setId(taggedId);
        tagged.setDisplayName("张经理");
        ContactEntity other = new ContactEntity();
        other.setId(otherId);
        other.setDisplayName("李四");
        Page<ContactEntity> page = new Page<>(1, 20);
        page.setRecords(List.of(tagged, other));
        when(contactMapper.listForUser(any(), any(), any(), anyBoolean(), any(), any(),
                anyBoolean(), any(), any())).thenReturn(page);
        when(resolver.matchNamesByContact(eq(userId), any(), eq("客户"), eq(SearchMode.TAG)))
                .thenReturn(Map.of(taggedId, List.of("VIP客户")));
        ContactService service = new ContactService(contactMapper,
                mock(ContactIdentityMapper.class), mock(ConversationMapper.class),
                mock(MessageMapper.class), mock(ChatAppAccountResolver.class),
                mock(ContactTagMapper.class), mock(ContactMemoryQueryService.class), resolver);

        List<ContactResponse> records = service
                .listForUser(userId, "客户", SearchMode.TAG, null, null, 1, 20, null, null)
                .getRecords();

        assertThat(records).extracting(ContactResponse::id, ContactResponse::matchedTags)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(taggedId, List.of("VIP客户")),
                        org.assertj.core.groups.Tuple.tuple(otherId, List.of()));
    }
```

新增 import：`import com.crmforlogistics.messagecenter.service.contactmemory.ContactMemoryQueryService;`、`import java.util.Map;`、`import static org.mockito.ArgumentMatchers.anyBoolean;`

> 构造器用的是 Step 5 改造后的 8 参公开构造器，形参顺序为 `contactMapper, contactIdentityMapper, conversationMapper, messageMapper, chatAppAccountResolver, contactTagMapper, contactMemoryQueryService, contactTagMatchResolver`。注意第 6 个形参是 `ContactTagMapper`、第 8 个才是 `ContactTagMatchResolver`，别把 resolver 传到第 6 位。`anyBoolean()` 的第二个位置对应新增的 `tagSearch`，第 7 个 `anyBoolean()` 对应原有的 `isAdmin`。

- [ ] **Step 2: 运行确认失败**

Run: `cd demo/message-center-spring/backend && mvn -q -Dtest='ContactMapperTagSearchIntegrationTest,ContactServiceAuthorizationTest' test`
Expected: 编译失败——`ContactMapper.listForUser` 参数个数不匹配（实际 8 个，期望 9 个）、`ContactService.listForUser` 参数个数不匹配、`ContactResponse::matchedTags` 不存在

- [ ] **Step 3: 给 listForUser 加 tagSearch 分支**

`ContactMapper.listForUser`（第 145-149 行）的搜索条件当前为：

```java
        "  <if test=\"search != null and search != ''\">" +
        "    and (c.display_name ilike '%' || #{search} || '%' or coalesce(c.remark,'') ilike '%' || #{search} || '%') " +
        "  </if>" +
```

替换为：

```java
        "  <if test=\"search != null and search != ''\">" +
        "    and ( <choose>" +
        "      <when test=\"tagSearch\">" +
        "        exists (select 1 from contact_taggings ct join contact_tags t on t.id = ct.tag_id " +
        "          where ct.contact_id = c.id and t.status = 'active' " +
        "          and t.owner_user_id = #{userId}::uuid " +
        "          and t.name ilike '%' || #{search} || '%') " +
        "      </when>" +
        "      <otherwise>" +
        "        c.display_name ilike '%' || #{search} || '%' or coalesce(c.remark,'') ilike '%' || #{search} || '%' " +
        "      </otherwise>" +
        "    </choose> ) " +
        "  </if>" +
```

该方法签名（第 165-172 行）整体替换为：

```java
    IPage<ContactEntity> listForUser(IPage<ContactEntity> page,
                                     @Param("userId") UUID userId,
                                     @Param("search") String search,
                                     @Param("tagSearch") boolean tagSearch,
                                     @Param("beforeLastMessageAt") Instant beforeLastMessageAt,
                                     @Param("beforeId") UUID beforeId,
                                     @Param("isAdmin") boolean isAdmin,
                                     @Param("channelType") String channelType,
                                     @Param("channelAccountId") UUID channelAccountId);
```

- [ ] **Step 4: 给 ContactResponse 加 matchedTags**

把 `ContactResponse.java` 的 record 头与两个构造器替换为（javadoc 保留不动）：

```java
public record ContactResponse(
        UUID id,
        String displayName,
        String remark,
        List<String> channelTypes,
        Instant lastMessageAt,
        String lastText,
        int messageCount,
        int unreadCount,
        List<ContactTagResponse> tags,
        List<ContactIdentityResponse> identities,
        ContactMemoryResponse memory,
        List<String> matchedTags
) {
    public ContactResponse(UUID id,
                           String displayName,
                           String remark,
                           List<String> channelTypes,
                           Instant lastMessageAt,
                           String lastText,
                           int messageCount,
                           int unreadCount,
                           List<ContactTagResponse> tags,
                           List<ContactIdentityResponse> identities) {
        this(id, displayName, remark, channelTypes, lastMessageAt, lastText,
                messageCount, unreadCount, tags, identities, null, List.of());
    }

    public ContactResponse withMatchedTags(List<String> tags) {
        return new ContactResponse(id, displayName, remark, channelTypes, lastMessageAt, lastText,
                messageCount, unreadCount, this.tags, identities, memory,
                tags == null ? List.of() : tags);
    }
}
```

在 `ContactService.java` 的 `getById` 路径（`toResponse(entity, userId)` 内约 235 行的 `new ContactResponse(...)`）末尾补上 `, List.of()`。

- [ ] **Step 5: 给 ContactService 接上 searchMode 与回填**

`ContactService` 当前有三个构造器：5 参（委托）、7 参 `@Autowired`（含 `contactMemoryQueryService`）、6 参（含 `contactTagMapper`）。把第 57-91 行整个构造器区块替换为下面四个：

```java
    public ContactService(ContactMapper contactMapper,
                          ContactIdentityMapper contactIdentityMapper,
                          ConversationMapper conversationMapper,
                          MessageMapper messageMapper,
                          ChatAppAccountResolver chatAppAccountResolver) {
        this(contactMapper, contactIdentityMapper, conversationMapper, messageMapper,
                chatAppAccountResolver, null, null, null);
    }

    @Autowired
    public ContactService(ContactMapper contactMapper,
                          ContactIdentityMapper contactIdentityMapper,
                          ConversationMapper conversationMapper,
                          MessageMapper messageMapper,
                          ChatAppAccountResolver chatAppAccountResolver,
                          ContactTagMapper contactTagMapper,
                          ContactMemoryQueryService contactMemoryQueryService,
                          ContactTagMatchResolver contactTagMatchResolver) {
        this.contactMapper = contactMapper;
        this.contactIdentityMapper = contactIdentityMapper;
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.chatAppAccountResolver = chatAppAccountResolver;
        this.contactTagMapper = contactTagMapper;
        this.contactMemoryQueryService = contactMemoryQueryService;
        this.contactTagMatchResolver = contactTagMatchResolver;
    }

    public ContactService(ContactMapper contactMapper,
                          ContactIdentityMapper contactIdentityMapper,
                          ConversationMapper conversationMapper,
                          MessageMapper messageMapper,
                          ChatAppAccountResolver chatAppAccountResolver,
                          ContactTagMapper contactTagMapper) {
        this(contactMapper, contactIdentityMapper, conversationMapper, messageMapper,
                chatAppAccountResolver, contactTagMapper, null, null);
    }
```

同时新增字段 `private final ContactTagMatchResolver contactTagMatchResolver;`。

> `ContactTagMatchResolver` 与本类同包（`service.contact`），无需 import。`@Autowired` 只标注 8 参那个，Spring 才不会有歧义。

把 `listForUser` 的两个重载（当前 108-142 行）替换为三个：

```java
    public IPage<ContactResponse> listForUser(UUID userId, String search,
                                               Instant beforeLastMessageAt, UUID beforeId,
                                               int page, int size) {
        return listForUser(userId, search, SearchMode.CONTACT, beforeLastMessageAt, beforeId,
                page, size, null, null);
    }

    public IPage<ContactResponse> listForUser(UUID userId, String search,
                                               Instant beforeLastMessageAt, UUID beforeId,
                                               int page, int size, String channelType,
                                               UUID channelAccountId) {
        return listForUser(userId, search, SearchMode.CONTACT, beforeLastMessageAt, beforeId,
                page, size, channelType, channelAccountId);
    }

    public IPage<ContactResponse> listForUser(UUID userId, String search, SearchMode searchMode,
                                               Instant beforeLastMessageAt, UUID beforeId,
                                               int page, int size, String channelType,
                                               UUID channelAccountId) {
        boolean chatAppFilter = "chatapp".equalsIgnoreCase(channelType);
        if ((chatAppFilter && channelAccountId == null)
                || (channelAccountId != null && !chatAppFilter)) {
            throw new IllegalArgumentException("CHATAPP_CONTACT_ACCOUNT_INACCESSIBLE");
        }
        if (chatAppFilter) {
            chatAppAccountResolver.requireOwnedAccount(userId, channelAccountId);
        }
        int safeSize = clampSize(size);
        Page<ContactEntity> pageParam = new Page<>(page, safeSize);
        boolean isAdmin = isCurrentUserAdmin();
        IPage<ContactEntity> pageResult = contactMapper.listForUser(
                pageParam, userId, search, searchMode.isTag(), beforeLastMessageAt, beforeId, isAdmin,
                chatAppFilter ? "chatapp" : null, chatAppFilter ? channelAccountId : null);

        List<UUID> contactIds = pageResult.getRecords().stream()
                .map(ContactEntity::getId)
                .filter(java.util.Objects::nonNull)
                .toList();
        Map<UUID, List<String>> matched =
                tagMatchResolver.matchNamesByContact(userId, contactIds, search, searchMode);
        List<ContactResponse> records = pageResult.getRecords().stream()
                .map(contact -> toResponse(contact, userId)
                        .withMatchedTags(matched.getOrDefault(contact.getId(), List.of())))
                .toList();

        Page<ContactResponse> resultPage = new Page<>(page, safeSize);
        resultPage.setRecords(records);
        resultPage.setTotal(pageResult.getTotal());
        return resultPage;
    }
```

新增 import：`import com.crmforlogistics.messagecenter.dto.request.SearchMode;`、`import java.util.Map;`。

- [ ] **Step 6: 修改 ContactController**

把 `list` 方法替换为：

```java
    @GetMapping
    public IPage<ContactResponse> list(
            @RequestParam(value = "search", required = false) String search,
            @RequestParam(value = "searchMode", required = false) String rawSearchMode,
            @RequestParam(value = "beforeLastMessageAt", required = false) Instant beforeLastMessageAt,
            @RequestParam(value = "beforeId", required = false) UUID beforeId,
            @RequestParam(value = "channelType", required = false) String channelType,
            @RequestParam(value = "channelAccountId", required = false) UUID channelAccountId,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        UUID userId = SecurityUtil.currentUserId();
        return contactService.listForUser(userId, search, SearchMode.parse(rawSearchMode),
                beforeLastMessageAt, beforeId, page, size, channelType, channelAccountId);
    }
```

新增 import：`import com.crmforlogistics.messagecenter.dto.request.SearchMode;`

- [ ] **Step 7: 更新受影响的既有测试**

`ContactServiceAuthorizationTest.java`：

- 第 71-72 行、第 237-238 行的 stub 改为（新 `tagSearch` 用 `anyBoolean()`，与既有 `any(Boolean.class)` 区分开，避免原始类型匹配踩坑）：

```java
        when(contactMapper.listForUser(any(), any(), any(), anyBoolean(), any(),
                any(), any(Boolean.class), any(), any())).thenReturn(contacts);
```

- 第 96-97 行的 verify 改为：

```java
        verify(contactMapper).listForUser(any(), eq(userId), isNull(), eq(false), isNull(),
                isNull(), eq(false), isNull(), isNull());
```

- 第 246-247 行的 verify 改为：

```java
        verify(contactMapper).listForUser(any(), eq(userId), isNull(), eq(false), isNull(),
                isNull(), eq(false), eq("chatapp"), eq(accountId));
```

- 第 174-176 行的反射签名（`ContactMapper`）在 `String.class` 之后插入 `boolean.class`：

```java
        String contactSql = sql(ContactMapper.class.getMethod(
                "listForUser", com.baomidou.mybatisplus.core.metadata.IPage.class,
                UUID.class, String.class, boolean.class, Instant.class, UUID.class, boolean.class,
                String.class, UUID.class));
```

- 第 217-221 行**保持不动**：它反射的是 `ContactService` 的八参重载（`UUID, String, Instant, UUID, int, int, String, UUID`），而 Step 5 明确保留了这个重载，所以签名与 `method.invoke(service, userId, null, null, null, 1, 20, "chatapp", null)` 都无需改。第 243、264 行的直接调用同理。

- 新增 import：`import static org.mockito.ArgumentMatchers.anyBoolean;`

`ContactMapperChatAppFilterIntegrationTest.java:92,95`：两次 `listForUser` 调用在第二个实参（`search`，该处为 `null`）之后插入 `false`：

```java
        List<UUID> generalIds = contactMapper.listForUser(new Page<>(1, 20), userId,
                        null, false, null, null, false, null, null)
                .getRecords().stream().map(ContactEntity::getId).toList();
        List<UUID> chatAppIds = contactMapper.listForUser(new Page<>(1, 20), userId,
                        null, false, null, null, false, "chatapp", accountId)
                .getRecords().stream().map(ContactEntity::getId).toList();
```

- [ ] **Step 8: 运行确认通过**

Run: `cd demo/message-center-spring/backend && mvn -q -Dtest='ContactServiceAuthorizationTest,ContactMapperTagSearchIntegrationTest,ContactMapperChatAppFilterIntegrationTest' test`
Expected: BUILD SUCCESS，全部通过

- [ ] **Step 9: 全量后端测试**

Run: `cd demo/message-center-spring/backend && mvn -q test`
Expected: BUILD SUCCESS

- [ ] **Step 10: 提交**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ContactMapper.java \
        demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/ContactResponse.java \
        demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contact/ContactService.java \
        demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/ContactController.java \
        demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/
git commit -m "feat: support tag search in contact list"
```

---

## Task 5: 前端 SearchModeSwitch 与 ContactsPage 接入

**Files:**
- Create: `demo/message-center-spring/frontend/src/components/SearchModeSwitch.tsx`
- Create: `demo/message-center-spring/frontend/src/components/SearchModeSwitch.test.tsx`
- Create: `demo/message-center-spring/frontend/src/components/ContactCard.matched-tags.test.tsx`
- Create: `demo/message-center-spring/frontend/src/pages/ContactsPage.search-mode.test.tsx`
- Modify: `demo/message-center-spring/frontend/src/api/types.ts`
- Modify: `demo/message-center-spring/frontend/src/api/endpoints.ts:492`
- Modify: `demo/message-center-spring/frontend/src/hooks/useContacts.ts`
- Modify: `demo/message-center-spring/frontend/src/components/ContactCard.tsx`
- Modify: `demo/message-center-spring/frontend/src/pages/ContactsPage.tsx`

**Interfaces:**
- Consumes: Task 2 的 `matchedTags` 响应字段
- Produces:
  - `type SearchMode = 'contact' | 'tag'`（`api/types.ts` 导出）
  - `SearchModeSwitch({ value, onChange }: { value: SearchMode; onChange: (mode: SearchMode) => void })`
  - `useUnifiedConversations(search?: string, searchMode?: SearchMode, options?: ContactsQueryOptions)`
  - `useContacts(search?, page?, size?, filters?, searchMode?, options?)`（`searchMode` 插在 `filters` 与 `options` 之间；`SendPage` 现有调用的 `options` 是第 5 个实参，Task 7 同步调整）
  - `useChannelAddressBook(channel, query?, page?, searchMode?)`

- [ ] **Step 1: 写失败的组件测试**

创建 `demo/message-center-spring/frontend/src/components/SearchModeSwitch.test.tsx`：

```tsx
import '@testing-library/jest-dom/vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import SearchModeSwitch from './SearchModeSwitch';

describe('SearchModeSwitch', () => {
  it('shows the current mode and reports the picked one', async () => {
    const onChange = vi.fn();
    render(<SearchModeSwitch value="contact" onChange={onChange} />);

    expect(screen.getByRole('button', { name: '搜索模式' })).toHaveTextContent('联系人');

    await userEvent.click(screen.getByRole('button', { name: '搜索模式' }));
    await userEvent.click(await screen.findByRole('menuitem', { name: '标签' }));

    expect(onChange).toHaveBeenCalledWith('tag');
  });
});
```

创建 `demo/message-center-spring/frontend/src/components/ContactCard.matched-tags.test.tsx`：

```tsx
import '@testing-library/jest-dom/vitest';
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import ContactCard from './ContactCard';

const base = {
  id: 'contact-1',
  displayName: '张经理',
  channelTypes: ['email'],
  lastMessageAt: null,
  lastText: '',
  messageCount: 0,
  unreadCount: 0,
};

describe('ContactCard matched tags', () => {
  it('renders matched tags when present', () => {
    render(<ContactCard contact={{ ...base, matchedTags: ['VIP客户', '老客户'] }}
      isActive={false} onClick={() => {}} />);

    expect(screen.getByText('VIP客户')).toBeInTheDocument();
    expect(screen.getByText('老客户')).toBeInTheDocument();
  });

  it('renders no tag chips without matched tags', () => {
    render(<ContactCard contact={{ ...base, matchedTags: [] }} isActive={false} onClick={() => {}} />);

    expect(screen.queryByText('VIP客户')).not.toBeInTheDocument();
  });
});
```

创建 `demo/message-center-spring/frontend/src/pages/ContactsPage.search-mode.test.tsx`（mock 形态照抄同目录的 `ContactsPage.test.tsx`）：

```tsx
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import ContactsPage from './ContactsPage';

const hooks = vi.hoisted(() => ({
  useContacts: vi.fn(),
  useUnifiedConversations: vi.fn(),
  useMergeContacts: vi.fn(),
  useConversationPreference: vi.fn(),
  useSse: vi.fn(),
}));

vi.mock('../hooks/useContacts', () => ({
  useContacts: hooks.useContacts,
  useUnifiedConversations: hooks.useUnifiedConversations,
  useMergeContacts: hooks.useMergeContacts,
  useConversationPreference: hooks.useConversationPreference,
}));
vi.mock('../hooks/useSse', () => ({ useSse: hooks.useSse }));
vi.mock('../components/ContactCard', () => ({ default: () => <div>联系人</div> }));
vi.mock('../components/ContactDetailPanel', () => ({ default: () => <div>详情</div> }));

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
        <ContactsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('ContactsPage search mode', () => {
  beforeEach(() => {
    hooks.useUnifiedConversations.mockReturnValue({ data: { records: [], total: 0, current: 1, pages: 1 }, isLoading: false });
    hooks.useContacts.mockReturnValue({ data: { records: [], total: 0, current: 1, pages: 1 }, isLoading: false });
    hooks.useMergeContacts.mockReturnValue({ mutate: vi.fn() });
    hooks.useConversationPreference.mockReturnValue({ mutateAsync: vi.fn(), isPending: false });
  });

  it('switches to tag mode, clears the keyword and asks the server for tags', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.type(screen.getByPlaceholderText('搜索联系人、邮箱、号码'), '张三');
    await waitFor(() => expect(hooks.useUnifiedConversations).toHaveBeenLastCalledWith('张三', 'contact'));

    await user.click(screen.getByRole('button', { name: '搜索模式' }));
    await user.click(await screen.findByRole('menuitem', { name: '标签' }));

    const box = await screen.findByPlaceholderText('搜索标签名');
    expect(box).toHaveValue('');
    await waitFor(() => expect(hooks.useUnifiedConversations).toHaveBeenLastCalledWith(undefined, 'tag'));
  });

  it('explains an empty tag search differently from an empty contact list', async () => {
    const user = userEvent.setup();
    renderPage();

    expect(screen.getByText('暂无联系人')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: '搜索模式' }));
    await user.click(await screen.findByRole('menuitem', { name: '标签' }));
    await user.type(screen.getByPlaceholderText('搜索标签名'), '客户');

    expect(screen.getByText('没有联系人被打上含「客户」的标签')).toBeInTheDocument();
    expect(screen.queryByText('暂无联系人')).not.toBeInTheDocument();
  });
});
```

- [ ] **Step 2: 运行确认失败**

Run: `cd demo/message-center-spring/frontend && npx vitest run src/components/SearchModeSwitch.test.tsx src/components/ContactCard.matched-tags.test.tsx src/pages/ContactsPage.search-mode.test.tsx`
Expected: 三个文件都因模块不存在 / 属性不存在 / 找不到「搜索模式」按钮而失败

- [ ] **Step 3: 新建 SearchModeSwitch**

创建 `demo/message-center-spring/frontend/src/components/SearchModeSwitch.tsx`：

```tsx
import { DownOutlined } from '@ant-design/icons';
import { Button, Dropdown, type MenuProps } from 'antd';
import type { SearchMode } from '../api/types';

const labels: Record<SearchMode, string> = { contact: '联系人', tag: '标签' };

interface Props {
  value: SearchMode;
  onChange: (mode: SearchMode) => void;
}

export default function SearchModeSwitch({ value, onChange }: Props) {
  const items: MenuProps['items'] = [
    { key: 'contact', label: '联系人' },
    { key: 'tag', label: '标签' },
  ];
  return (
    <Dropdown
      trigger={['click']}
      menu={{
        items,
        selectable: true,
        selectedKeys: [value],
        onClick: ({ key }) => onChange(key as SearchMode),
      }}
    >
      <Button aria-label="搜索模式" size="middle">
        {labels[value]}
        <DownOutlined style={{ fontSize: 10 }} />
      </Button>
    </Dropdown>
  );
}
```

- [ ] **Step 4: 加类型与请求参数**

`demo/message-center-spring/frontend/src/api/types.ts` 顶部追加：

```ts
export type SearchMode = 'contact' | 'tag';
```

`ContactConversationItem` 加一个可选字段（放在 `pinned?: boolean;` 之后）：

```ts
  matchedTags?: string[];
```

`ChannelAddressBookItem` 与 `ContactResponse` 各加一个可选字段（均放在接口末尾）：

```ts
  matchedTags?: string[];
```

`demo/message-center-spring/frontend/src/api/endpoints.ts` 的 `listConversations` 参数对象加 `searchMode`：

```ts
export async function listConversations(params?: {
  search?: string;
  searchMode?: SearchMode;
  cursor?: string;
  limit?: number;
}): Promise<ConversationPage> {
  const res = await client.get<ConversationPage>('/conversations', { params });
  return res.data;
}
```

同文件为 `SearchMode` 加 import，并按同样方式给 `fetchContacts` 的参数对象加 `searchMode?: SearchMode;`、给 `fetchChannelAddressBook` 的 `params` 加 `searchMode?: SearchMode;`。

- [ ] **Step 5: 更新 useContacts 的 hook**

`demo/message-center-spring/frontend/src/hooks/useContacts.ts`：

```ts
export function useUnifiedConversations(search?: string, searchMode: SearchMode = 'contact',
                                        options?: ContactsQueryOptions) {
  return useQuery({
    queryKey: ['conversations', search, searchMode],
    queryFn: () => listConversations({ search: search || undefined, searchMode, limit: 50 }),
    enabled: options?.enabled ?? true,
    placeholderData: (prev) => prev,
  });
}
```

`useChannelAddressBook` 加第四参 `searchMode: SearchMode = 'contact'`：

```ts
export function useChannelAddressBook(channel: ChannelAddressBookChannel, query?: string,
                                      page = 1, searchMode: SearchMode = 'contact') {
  return useQuery({
    queryKey: ['channel-address-book', channel, query, page, searchMode],
    queryFn: () => fetchChannelAddressBook(channel, { query: query || undefined, searchMode, page, size: 20 }),
  });
}
```

`useContacts` 在 `filters` 之后、`options` 之前加 `searchMode: SearchMode = 'contact'` 形参：

```ts
export function useContacts(
  search?: string,
  page = 1,
  size = 20,
  filters?: ContactFilters,
  searchMode: SearchMode = 'contact',
  options?: ContactsQueryOptions,
) {
  const { channelType, channelAccountId } = filters ?? {};
  return useQuery({
    queryKey: ['contacts', search, page, size, channelType, channelAccountId, searchMode],
    queryFn: () => fetchContacts({ search, searchMode, page, size, channelType, channelAccountId }),
    enabled: options?.enabled ?? true,
    placeholderData: channelAccountId ? undefined : (prev) => prev,
  });
}
```

**注意**：`SendPage` 目前按 `useContacts(search, 1, 20, filters, options)` 调用，`options` 是第 5 个实参；新增形参插在它前面后，这个调用点会让 `tsc -b` 报错。本步必须一并把它改成 `useContacts(search, 1, 20, filters, 'contact', options)` ——这只是让编译通过的机械修补，Task 7 会把它换成动态的 `searchMode` 状态。同时把 `useContacts.ts` 第 15 行的类型 import 改为 `import type { ChannelAddressBookChannel, ConversationOrderRequest, ConversationTargetType, SearchMode } from '../api/types';`。

> `SendPage.test.tsx` 走真实 hook 并断言 `api.fetchContacts` 的实参，本步传入常量 `'contact'` 后，既有用例的断言不受影响。

- [ ] **Step 6: ContactCard 渲染命中标签**

`demo/message-center-spring/frontend/src/components/ContactCard.tsx`：把 props 类型里的 `Pick<...>` 加上 `'matchedTags'`：

```tsx
interface ContactCardProps {
  contact: Pick<ContactResponse, 'id' | 'displayName' | 'channelTypes' | 'lastMessageAt' | 'lastText' | 'messageCount' | 'unreadCount' | 'matchedTags'> & { remark?: string | null; pinned?: boolean };
  isActive: boolean;
  onClick: () => void;
}
```

在组件内、渠道标签那段 `<div>` 之前插入：

```tsx
      {contact.matchedTags && contact.matchedTags.length > 0 && (
        <div style={{ paddingLeft: 24, marginTop: 2 }}>
          {contact.matchedTags.map((name) => (
            <Tag key={name} color="blue" style={{ fontSize: 10, lineHeight: '16px' }}>{name}</Tag>
          ))}
        </div>
      )}
```

- [ ] **Step 7: ContactsPage 接入**

`demo/message-center-spring/frontend/src/pages/ContactsPage.tsx`：

1. 新增 import：`import SearchModeSwitch from '../components/SearchModeSwitch';` 与 `import type { SearchMode } from '../api/types';`
2. 在 `const [search, setSearch] = useState('');` 之后加 `const [searchMode, setSearchMode] = useState<SearchMode>('contact');`
3. 把 `useUnifiedConversations(search || undefined)` 改为 `useUnifiedConversations(search || undefined, searchMode)`
4. 在组件内加：

```tsx
  const changeSearchMode = (mode: SearchMode) => {
    setSearchMode(mode);
    setSearch('');
  };
```

5. 搜索框（第 142-148 行）替换为：

```tsx
        <Input
          prefix={<SearchOutlined />}
          addonBefore={<SearchModeSwitch value={searchMode} onChange={changeSearchMode} />}
          placeholder={searchMode === 'tag' ? '搜索标签名' : '搜索联系人、邮箱、号码'}
          allowClear
          value={search}
          onChange={(e) => setSearch(e.target.value)}
        />
```

6. 空态文案（第 156 行）改为：

```tsx
          <Empty
            description={searchMode === 'tag'
              ? `没有联系人被打上含「${search}」的标签`
              : '暂无联系人'}
            style={{ padding: 24 }}
          />
```

> `ContactsPage` 没有分页与游标状态（统一会话列表固定 `limit: 50`，`useUnifiedConversations` 不传 cursor），所以规范里的「重置分页与游标」在这一页只体现为清空关键词；渠道通讯录的 `page` 重置在 Task 6。

- [ ] **Step 8: 更新既有测试并运行**

`ContactsPage.test.tsx` 第 44 行：`expect(hooks.useUnifiedConversations).toHaveBeenCalledWith(undefined);` 改为

```tsx
    expect(hooks.useUnifiedConversations).toHaveBeenCalledWith(undefined, 'contact');
```

`ContactsPage.interaction.test.tsx` 第 137 行的 `mockImplementation((search?: string) => ...)` 是位置参数，新增第二参不影响它，无需改；文件里没有对 `useUnifiedConversations` 的 `toHaveBeenCalledWith` 断言。

Run: `cd demo/message-center-spring/frontend && npx vitest run src/components/SearchModeSwitch.test.tsx src/components/ContactCard.matched-tags.test.tsx src/pages/ContactsPage.search-mode.test.tsx src/pages/ContactsPage.test.tsx src/pages/ContactsPage.interaction.test.tsx src/pages/ContactsPage.unified.test.tsx`
Expected: 全部通过（`ContactsPage.test.tsx` 的 placeholder 断言 `'搜索联系人、邮箱、号码'` 在默认联系人模式下不变，仍成立）

- [ ] **Step 9: 类型检查**

Run: `cd demo/message-center-spring/frontend && npx tsc -b`
Expected: 无错误

- [ ] **Step 10: 提交**

```bash
git add demo/message-center-spring/frontend/src/
git commit -m "feat: add search mode switch and tag chips to contacts page"
```

---

## Task 6: ChannelAddressBookPage 接入

**Files:**
- Modify: `demo/message-center-spring/frontend/src/pages/ChannelAddressBookPage.tsx`
- Modify: `demo/message-center-spring/frontend/src/components/ChannelAddressBookList.tsx`
- Test: `demo/message-center-spring/frontend/src/components/ChannelAddressBookList.matched-tags.test.tsx`（新建）

**Interfaces:**
- Consumes: Task 5 的 `SearchModeSwitch`、`SearchMode`、`useChannelAddressBook(channel, query, page, searchMode)`
- Produces: 无新接口

- [ ] **Step 1: 写失败的展示测试**

创建 `demo/message-center-spring/frontend/src/components/ChannelAddressBookList.matched-tags.test.tsx`：

```tsx
import '@testing-library/jest-dom/vitest';
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import ChannelAddressBookList from './ChannelAddressBookList';

const item = {
  contactId: 'contact-1', identityId: 'identity-1', displayName: '张经理', remark: null,
  channelType: 'email' as const, address: 'a@example.com', channelDisplayName: '邮件',
  additionalChannelTypes: [], source: 'manual', lastContactAt: null, hasActivity: true,
  canDelete: false,
};

describe('ChannelAddressBookList matched tags', () => {
  it('renders matched tags next to the contact name', () => {
    render(<ChannelAddressBookList channel="email" items={[{ ...item, matchedTags: ['VIP客户'] }]}
      onOpen={() => {}} onDelete={() => {}} />);

    expect(screen.getByText('VIP客户')).toBeInTheDocument();
  });

  it('renders nothing extra without matched tags', () => {
    render(<ChannelAddressBookList channel="email" items={[item]}
      onOpen={() => {}} onDelete={() => {}} />);

    expect(screen.queryByText('VIP客户')).not.toBeInTheDocument();
  });
});
```

- [ ] **Step 2: 运行确认失败**

Run: `cd demo/message-center-spring/frontend && npx vitest run src/components/ChannelAddressBookList.matched-tags.test.tsx`
Expected: 第一个用例失败（找不到文本 `VIP客户`）

- [ ] **Step 3: 列表渲染命中标签**

`demo/message-center-spring/frontend/src/components/ChannelAddressBookList.tsx` 的「联系人」列替换为：

```tsx
      { title: '联系人', key: 'contact', render: (_, item) => <Space>{iconByChannel[channel]}<span>{item.displayName}</span>
        {item.matchedTags?.map((name) => <Tag key={name} color="blue" style={{ fontSize: 10, lineHeight: '16px' }}>{name}</Tag>)}</Space> },
```

- [ ] **Step 4: 页面接入模式切换**

`demo/message-center-spring/frontend/src/pages/ChannelAddressBookPage.tsx`：

1. 新增 import：`import SearchModeSwitch from '../components/SearchModeSwitch';`、把 `import type { ChannelAddressBookChannel } from '../api/types';` 改为 `import type { ChannelAddressBookChannel, SearchMode } from '../api/types';`
2. 在 `const [page, setPage] = useState(1);` 之后加 `const [searchMode, setSearchMode] = useState<SearchMode>('contact');`
3. `const result = useChannelAddressBook(channel, search, page);` 改为 `const result = useChannelAddressBook(channel, search, page, searchMode);`
4. 加：

```tsx
  const changeSearchMode = (mode: SearchMode) => {
    setSearchMode(mode);
    setQuery('');
    setSearch('');
    setPage(1);
  };
```

5. 搜索框替换为：

```tsx
      <Input.Search allowClear value={query} prefix={<SearchOutlined />}
        addonBefore={<SearchModeSwitch value={searchMode} onChange={changeSearchMode} />}
        placeholder={searchMode === 'tag' ? '搜索标签名' : '搜索名称、备注、号码或邮箱'}
        onChange={(event) => setQuery(event.target.value)}
        onSearch={(value) => { setSearch(value); setPage(1); }} />
```

6. 空态文案（第 34-35 行整段替换）：

```tsx
      {result.data?.items.length === 0 && !result.isLoading
        ? <Empty description={searchMode === 'tag'
            ? `没有联系人被打上含「${search}」的标签`
            : '暂无联系人'} />
        : <ChannelAddressBookList channel={channel} items={result.data?.items ?? []} loading={result.isLoading} deletingId={remove.variables}
          onOpen={open} onDelete={(item) => remove.mutate(item.contactId, { onSuccess: () => message.success('已删除'), onError: () => message.error('无法删除已有记录的联系人') })} />}
```

- [ ] **Step 5: 更新既有测试并运行**

`ChannelAddressBookPage.test.tsx` 两处 `toHaveBeenLastCalledWith` 要补上第四参：

- 第 68 行：`expect(hooks.useChannelAddressBook).toHaveBeenLastCalledWith('chatapp', '张', 1);` → `expect(hooks.useChannelAddressBook).toHaveBeenLastCalledWith('chatapp', '张', 1, 'contact');`
- 第 79 行：`expect(hooks.useChannelAddressBook).toHaveBeenLastCalledWith('email', '', 2);` → `expect(hooks.useChannelAddressBook).toHaveBeenLastCalledWith('email', '', 2, 'contact');`

Run: `cd demo/message-center-spring/frontend && npx vitest run src/components/ChannelAddressBookList.matched-tags.test.tsx src/pages/ChannelAddressBookPage.test.tsx`
Expected: 全部通过

- [ ] **Step 6: 类型检查**

Run: `cd demo/message-center-spring/frontend && npx tsc -b`
Expected: 无错误

- [ ] **Step 7: 提交**

```bash
git add demo/message-center-spring/frontend/src/
git commit -m "feat: support tag search mode in channel address book"
```

---

## Task 7: 发送页选择器接入

**Files:**
- Modify: `demo/message-center-spring/frontend/src/pages/SendPage.tsx`
- Modify: `demo/message-center-spring/frontend/src/pages/SendPage.test.tsx`

**Interfaces:**
- Consumes: Task 5 的 `SearchModeSwitch`、`SearchMode`、`useContacts(search, page, size, filters, searchMode, options)`
- Produces: 无新接口

- [ ] **Step 1: 写失败的测试**

`SendPage.test.tsx` 没有 mock `useContacts`，它 mock 的是 `../api/endpoints` 并跑真实 hook（`api.fetchContacts`）。所以断言要打在 `api.fetchContacts` 的实参上，不能像其它页面那样读 mock 的调用参数。在 `describe('SendPage ChatApp contacts')` 里追加：

```tsx
  it('switches the recipient search to tag mode and clears the keyword', async () => {
    const user = userEvent.setup();
    api.fetchChannelCapabilities.mockResolvedValue([{
      channelType: 'chatapp', channelAccountId: 'account-a', displayName: 'CAMS 一号账号', authStatus: 'active',
    }]);
    api.fetchContacts.mockResolvedValue(page([{
      id: 'contact-1', displayName: '张三', remark: '', channelTypes: ['chatapp'],
      lastMessageAt: null, lastText: '', messageCount: 0, unreadCount: 0, identities: [],
    }]));

    renderPage();

    const recipientSearch = await screen.findByPlaceholderText('搜索并选择联系人');
    await user.type(recipientSearch, '张三');

    await user.click(screen.getByRole('button', { name: '搜索模式' }));
    await user.click(await screen.findByRole('menuitem', { name: '标签' }));

    expect(await screen.findByPlaceholderText('搜索标签名')).toBeInTheDocument();
    await waitFor(() => expect(api.fetchContacts).toHaveBeenLastCalledWith(expect.objectContaining({
      search: undefined, searchMode: 'tag', channelAccountId: 'account-a',
    })));
  });
```

> 只有能力接口返回单个 active 账号时 `SendPage` 才会自动选中它并请求联系人，这是本文件既有用例的通行写法。切换模式后关键词被清空，因此最后一次请求的 `search` 必须是 `undefined`。

- [ ] **Step 2: 运行确认失败**

Run: `cd demo/message-center-spring/frontend && npx vitest run src/pages/SendPage.test.tsx`
Expected: 新用例失败（找不到「搜索模式」按钮）

- [ ] **Step 3: 页面接入**

`demo/message-center-spring/frontend/src/pages/SendPage.tsx`：

1. 新增 import：`import SearchModeSwitch from '../components/SearchModeSwitch';`、把 SearchMode 类型并入已有的 `../api/types` import
2. 在 `const [search, setSearch] = useState('');` 之后加 `const [searchMode, setSearchMode] = useState<SearchMode>('contact');`
3. `useContacts` 调用改为：

```tsx
  } = useContacts(
    search || undefined,
    1,
    20,
    { channelType: 'chatapp', channelAccountId: currentChannelAccountId },
    searchMode,
    { enabled: Boolean(currentChannelAccountId) },
  );
```

4. 加：

```tsx
  const changeSearchMode = (mode: SearchMode) => {
    setSearchMode(mode);
    setSearch('');
  };
```

5. 「收件人」表单项替换为：

```tsx
        <Form.Item label="收件人" style={{ marginBottom: 0 }}>
          <div style={{ display: 'flex', gap: 8 }}>
            <SearchModeSwitch value={searchMode} onChange={changeSearchMode} />
            <Select
              showSearch
              placeholder={searchMode === 'tag' ? '搜索标签名' : '搜索并选择联系人'}
              filterOption={false}
              onSearch={setSearch}
              onChange={(id) => setSelectedId(id)}
              value={selectedId}
              style={{ flex: 1 }}
              options={contacts.map((c) => ({
                label: searchMode === 'tag' && c.matchedTags?.length
                  ? `${contactDisplayName(c)} (${c.matchedTags.join('、')})`
                  : `${contactDisplayName(c)} (${c.channelTypes?.join(', ')})`,
                value: c.id,
              }))}
              notFoundContent={contactsPending ? <Spin size="small" /> : <Empty
                description={searchMode === 'tag'
                  ? `没有联系人被打上含「${search}」的标签`
                  : '暂无 CAMS 消息历史联系人'} />}
            />
          </div>
        </Form.Item>
```

- [ ] **Step 4: 运行测试**

Run: `cd demo/message-center-spring/frontend && npx vitest run src/pages/SendPage.test.tsx`
Expected: 全部通过

- [ ] **Step 5: 全量前端检查**

Run: `cd demo/message-center-spring/frontend && npm test && npx tsc -b`
Expected: `node --test test/*.test.mjs` 与 `vitest run` 全部通过，`tsc -b` 无错误

- [ ] **Step 6: 提交**

```bash
git add demo/message-center-spring/frontend/src/
git commit -m "feat: support tag search mode in send page recipient picker"
```

---

## 手工验收

后端与前端都起来后（`mvn spring-boot:run` + `npm run dev`），用真实数据逐条过规范里的验收项：

1. 给某个联系人打上「VIP客户」标签，在统一会话列表切到「标签」模式搜「客户」，该联系人出现在结果中且带「VIP客户」chip。
2. 另建一个昵称就叫「VIP」、但没打 VIP 标签的联系人，标签模式搜「VIP」它不出现；切回「联系人」模式搜「VIP」它出现且不带 chip。
3. 切模式时输入框被清空。
4. 渠道通讯录和发送页选择器重复第 1、3 步。
5. 用一个非当前用户打的标签名去搜（需要另一账号造数据），搜不到。
6. 标签模式下搜一个不存在的标签名，空态显示「没有联系人被打上含「xxx」的标签」。
7. 标签模式下确认列表里没有企微群。
