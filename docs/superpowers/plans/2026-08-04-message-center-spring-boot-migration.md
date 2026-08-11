# message-center Spring Boot MVC 迁移 — 第一阶段实现计划

> **供 agentic worker 使用：** 必须子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 按任务逐个实现此计划。步骤使用 checkbox（`- [ ]`）语法跟踪。

**目标：** 搭建 `demo/message-center-spring/` 为 Spring Boot 3.4.x MVC 项目，包含 MyBatis-Plus、Spring Security、MinIO 以及核心联系人/消息/线程 API，参考 `demo/message-center-demo/` 中的业务逻辑。

**架构：** Spring Boot 3.4.x + MVC (Tomcat)。Jackson 处理 JSON，MyBatis-Plus 配合 XML Mapper 进行数据访问，Spring Security 处理认证，MinIO 处理文件存储。前端为 React + TypeScript + Vite + Ant Design SPA，功能与旧 `App.java` 中当前嵌入式页面完全一致。

**技术栈：** Java 17、Spring Boot 3.4.x、Spring MVC、Spring Security、MyBatis-Plus、Flyway、PostgreSQL、MinIO、Jackson、React 18、TypeScript 5、Vite 5、Ant Design 5、TanStack Query、React Router 6、Axios。

## 全局约束

- Java 17 最低版本
- Spring Boot 3.x
- Spring Security
- MyBatis-Plus + XML Mapper
- PostgreSQL，第一阶段不用 pgvector
- MinIO 用于文件存储
- Flyway 用于数据库迁移
- Docker Compose 单机私有化部署
- 前端 UI/UX 必须与旧嵌入式页面完全一致
- 新代码在 `demo/message-center-spring/`，旧代码在 `demo/message-center-demo/` 中仅作只读参考
- Jackson 替换 Gson

---

## 文件结构图

```
demo/message-center-spring/
├── backend/
│   ├── pom.xml
│   ├── compose.yaml
│   ├── secrets/                           # 从旧项目复制
│   │   ├── postgres_password
│   │   ├── minio_access_key
│   │   └── minio_secret_key
│   └── src/main/
│       ├── java/com/crmforlogistics/messagecenter/
│       │   ├── App.java                   # @SpringBootApplication
│       │   ├── config/
│       │   │   ├── AppConfig.java         # @ConfigurationProperties("app")
│       │   │   ├── CorsConfig.java
│       │   │   ├── JacksonConfig.java
│       │   │   ├── SecurityConfig.java
│       │   │   ├── MinioConfig.java
│       │   │   └── MyBatisPlusConfig.java
│       │   ├── entity/                    # MyBatis-Plus 实体
│       │   │   ├── UserEntity.java
│       │   │   ├── SessionEntity.java
│       │   │   ├── ContactEntity.java
│       │   │   ├── ContactIdentityEntity.java
│       │   │   ├── ConversationEntity.java
│       │   │   ├── MessageEntity.java
│       │   │   ├── ChannelAccountEntity.java
│       │   │   └── CompanyEntity.java
│       │   ├── mapper/                    # MyBatis-Plus mapper 接口
│       │   │   ├── UserMapper.java
│       │   │   ├── SessionMapper.java
│       │   │   ├── ContactMapper.java
│       │   │   ├── ContactIdentityMapper.java
│       │   │   ├── ConversationMapper.java
│       │   │   ├── MessageMapper.java
│       │   │   └── ChannelAccountMapper.java
│       │   ├── dto/
│       │   │   ├── request/
│       │   │   │   ├── LoginRequest.java
│       │   │   │   ├── SendMessageRequest.java
│       │   │   │   ├── ContactGroupRequest.java
│       │   │   │   └── PageRequest.java
│       │   │   └── response/
│       │   │       ├── ApiError.java
│       │   │       ├── ContactResponse.java
│       │   │       ├── ThreadResponse.java
│       │   │       ├── MessageResponse.java
│       │   │       └── LoginResponse.java
│       │   ├── service/
│       │   │   ├── auth/
│       │   │   │   ├── UserDetailsServiceImpl.java
│       │   │   │   └── BootstrapService.java
│       │   │   ├── contact/
│       │   │   │   ├── ContactService.java
│       │   │   │   └── ContactGroupService.java
│       │   │   ├── message/
│       │   │   │   ├── MessageService.java
│       │   │   │   ├── ThreadService.java
│       │   │   │   └── SyncService.java
│       │   │   ├── chatapp/
│       │   │   │   ├── ChatAppSender.java
│       │   │   │   └── ChatAppTemplateService.java
│       │   │   └── event/
│       │   │       └── EventHub.java
│       │   ├── web/
│       │   │   ├── AuthController.java
│       │   │   ├── ContactController.java
│       │   │   ├── ThreadController.java
│       │   │   ├── MessageController.java
│       │   │   ├── TemplateController.java
│       │   │   ├── SseController.java
│       │   │   ├── MediaController.java
│       │   │   └── GlobalExceptionHandler.java
│       │   └── infrastructure/
│       │       ├── MinioStorage.java
│       │       └── SecurityUtil.java
│       └── resources/
│           ├── application.yml
│           ├── application-dev.yml
│           ├── db/migration/              # 从旧项目复制
│           │   ├── V1__identity_and_contact.sql
│           │   ├── V2__channel_conversation_message.sql
│           │   ├── V3__events_storage_import.sql
│           │   ├── V4__tag_search_indexes.sql
│           │   └── V5__wecom_daily_summary.sql
│           └── mapper/                    # MyBatis XML mapper
│               ├── ContactMapper.xml
│               ├── MessageMapper.xml
│               └── ConversationMapper.xml
└── frontend/
    ├── package.json
    ├── vite.config.ts
    ├── tsconfig.json
    ├── index.html
    └── src/
        ├── main.tsx
        ├── App.tsx
        ├── router.tsx
        ├── api/client.ts
        ├── pages/
        │   ├── LoginPage.tsx
        │   ├── ContactsPage.tsx
        │   ├── ThreadPage.tsx
        │   ├── SendPage.tsx
        │   └── TemplatesPage.tsx
        ├── components/
        │   ├── AppLayout.tsx
        │   ├── ContactCard.tsx
        │   ├── MessageBubble.tsx
        │   ├── SendForm.tsx
        │   └── SseProvider.tsx
        └── hooks/
            ├── useContacts.ts
            ├── useMessages.ts
            └── useSse.ts
```

---

### 任务 1: 创建项目目录和 pom.xml

**文件：**
- 创建: `demo/message-center-spring/backend/pom.xml`
- 创建: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/App.java`

**接口：**
- 产出: Spring Boot 3.4.x 父 POM，包含所有第一阶段依赖

- [ ] **步骤 1: 创建目录结构**

```bash
mkdir -p demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter
mkdir -p demo/message-center-spring/backend/src/main/resources
mkdir -p demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter
```

- [ ] **步骤 2: 编写 pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.4.5</version>
        <relativePath/>
    </parent>

    <groupId>com.crmforlogistics</groupId>
    <artifactId>message-center</artifactId>
    <version>1.0.0-SNAPSHOT</version>

    <properties>
        <java.version>17</java.version>
        <mybatis-plus.version>3.5.10</mybatis-plus.version>
        <minio.version>8.5.14</minio.version>
    </properties>

    <dependencies>
        <!-- Spring Boot starters -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>

        <!-- MyBatis-Plus + PostgreSQL -->
        <dependency>
            <groupId>com.baomidou</groupId>
            <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
            <version>${mybatis-plus.version}</version>
        </dependency>
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
        </dependency>

        <!-- Flyway -->
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-core</artifactId>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-database-postgresql</artifactId>
        </dependency>

        <!-- MinIO -->
        <dependency>
            <groupId>io.minio</groupId>
            <artifactId>minio</artifactId>
            <version>${minio.version}</version>
        </dependency>

        <!-- BouncyCastle（用于企业微信加解密，后续阶段） -->
        <dependency>
            <groupId>org.bouncycastle</groupId>
            <artifactId>bcprov-jdk18on</artifactId>
            <version>1.78.1</version>
        </dependency>

        <!-- Mail（用于邮件发送，后续阶段） -->
        <dependency>
            <groupId>com.sun.mail</groupId>
            <artifactId>jakarta.mail</artifactId>
            <version>2.0.1</version>
        </dependency>

        <!-- 阿里云 CAMS SDK（用于 ChatApp，后续阶段） -->
        <dependency>
            <groupId>com.aliyun</groupId>
            <artifactId>alibabacloud-cams20200606</artifactId>
            <version>5.0.5</version>
        </dependency>

        <!-- Argon2 密码哈希 -->
        <dependency>
            <groupId>de.mkammerer</groupId>
            <artifactId>argon2-jvm</artifactId>
            <version>2.11</version>
        </dependency>

        <!-- 测试 -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.security</groupId>
            <artifactId>spring-security-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>testcontainers</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>postgresql</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **步骤 3: 编写最小 App.java 验证脚手架**

```java
package com.crmforlogistics.messagecenter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class App {
    public static void main(String[] args) {
        SpringApplication.run(App.class, args);
    }
}
```

- [ ] **步骤 4: 编写空的 application.yml**

```yaml
spring:
  application:
    name: message-center
  profiles:
    active: dev
```

- [ ] **步骤 5: 验证构建能编译**

```bash
cd demo/message-center-spring/backend && mvn compile -q
```

期望: BUILD SUCCESS

- [ ] **步骤 6: 提交**

```bash
git add demo/message-center-spring/backend/pom.xml demo/message-center-spring/backend/src/
git commit -m "chore: 搭建 Spring Boot 3.4.5 项目脚手架，包含第一阶段依赖"
```

---

### 任务 2: 复制 compose.yaml、secrets 和 Flyway 迁移脚本

**文件：**
- 复制: `demo/message-center-demo/compose.yaml` → `demo/message-center-spring/backend/compose.yaml`
- 复制: `demo/message-center-demo/secrets/` → `demo/message-center-spring/backend/secrets/`
- 复制: `demo/message-center-demo/src/main/resources/db/migration/` → `demo/message-center-spring/backend/src/main/resources/db/migration/`

**接口：**
- 产出: Docker Compose 服务（postgres:17.5、minio、minio-init）和 V1-V5 Flyway 脚本

- [ ] **步骤 1: 复制基础设施文件**

```bash
cp demo/message-center-demo/compose.yaml demo/message-center-spring/backend/compose.yaml
cp -r demo/message-center-demo/secrets demo/message-center-spring/backend/secrets
mkdir -p demo/message-center-spring/backend/src/main/resources/db/migration
cp demo/message-center-demo/src/main/resources/db/migration/V*.sql demo/message-center-spring/backend/src/main/resources/db/migration/
```

- [ ] **步骤 2: 编写 application-dev.yml，配置数据源和 Flyway**

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/message_center
    username: message_center
    password: ${postgres_password}
  flyway:
    enabled: true
    locations: classpath:db/migration

postgres_password: placeholder
```

- [ ] **步骤 3: 提交**

```bash
git add demo/message-center-spring/backend/compose.yaml \
        demo/message-center-spring/backend/secrets/ \
        demo/message-center-spring/backend/src/main/resources/db/ \
        demo/message-center-spring/backend/src/main/resources/application-dev.yml
git commit -m "chore: 复制 compose.yaml、secrets 和 Flyway 迁移脚本"
```

---

### 任务 3: 配置层 — AppConfig、CorsConfig、JacksonConfig、MyBatisPlusConfig

**文件：**
- 创建: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/config/AppConfig.java`
- 创建: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/config/CorsConfig.java`
- 创建: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/config/JacksonConfig.java`
- 创建: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/config/MyBatisPlusConfig.java`
- 修改: `demo/message-center-spring/backend/src/main/resources/application.yml`

**接口：**
- 产出: `AppConfig` 属性 record、`CorsConfig` WebMvcConfigurer bean、`JacksonConfig` ObjectMapper bean、`MyBatisPlusConfig` 分页拦截器 bean

- [ ] **步骤 1: 编写 AppConfig.java**

```java
package com.crmforlogistics.messagecenter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "app")
public record AppConfig(
        @DefaultValue("8099") int port,
        @DefaultValue("${user.dir}/data") String dataDir,
        String webBindAddress,
        String databaseUrl,
        String databaseUser,
        String databasePasswordFile,
        boolean localDevMode,
        String custSpaceId,
        String chatappFrom,
        String chatappTo,
        String chatappChannelType,
        String smtpHost,
        String smtpPort,
        String smtpUser,
        String smtpPasswordFile,
        String imapHost,
        String imapPort,
        String imapUser,
        String imapPasswordFile
) {}
```

- [ ] **步骤 2: 编写 CorsConfig.java**

```java
package com.crmforlogistics.messagecenter.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CorsConfig {
    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**")
                        .allowedOrigins("http://localhost:5173")
                        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                        .allowCredentials(true);
            }
        };
    }
}
```

- [ ] **步骤 3: 编写 JacksonConfig.java**

```java
package com.crmforlogistics.messagecenter.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JacksonConfig {
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
    }
}
```

- [ ] **步骤 4: 编写 MyBatisPlusConfig.java**

```java
package com.crmforlogistics.messagecenter.config;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan("com.crmforlogistics.messagecenter.mapper")
public class MyBatisPlusConfig {
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor());
        return interceptor;
    }
}
```

- [ ] **步骤 5: 更新 application.yml** — 添加 MyBatis-Plus 设置

```yaml
spring:
  application:
    name: message-center
  profiles:
    active: dev

mybatis-plus:
  mapper-locations: classpath*:mapper/**/*.xml
  type-aliases-package: com.crmforlogistics.messagecenter.entity
  global-config:
    db-config:
      id-type: auto
  configuration:
    map-underscore-to-camel-case: true
```

- [ ] **步骤 6: 验证编译**

```bash
cd demo/message-center-spring/backend && mvn compile -q
```

- [ ] **步骤 7: 提交**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/config/ \
        demo/message-center-spring/backend/src/main/resources/application.yml
git commit -m "feat: 添加配置层，包含 AppConfig、Cors、Jackson 和 MyBatis-Plus"
```

---

### 任务 4: 实体类 — MyBatis-Plus 实体

**文件：**
- 创建: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/UserEntity.java`
- 创建: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/ContactEntity.java`
- 创建: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/ContactIdentityEntity.java`
- 创建: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/ConversationEntity.java`
- 创建: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/MessageEntity.java`
- 创建: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/ChannelAccountEntity.java`
- 创建: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/CompanyEntity.java`

**接口：**
- 产出: 映射到 Flyway 创建的 PostgreSQL 表的实体类，使用 `@TableName`、`@TableId` 和 `@TableField` 注解

实现指南：每个实体与 V1-V2 Flyway 脚本中定义的数据表一一对应。使用 `@TableName` 指定表名，`@TableId(type = IdType.ASSIGN_UUID)` 用于 UUID 主键，`@TableField` 用于列名与驼峰命名不同的字段。以下为示例模式，所有实体遵循相同结构。

- [ ] **步骤 1: 编写 ContactEntity.java**

参考: V1__identity_and_contact.sql `contacts` 表。映射 `contacts` 表列：id (UUID 主键，自动生成)、display_name、role_title、remark、status、merged_to_id、created_by、created_at、updated_at、deleted_at、version。

```java
package com.crmforlogistics.messagecenter.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.time.Instant;
import java.util.UUID;

@TableName("contacts")
public class ContactEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private UUID id;
    private String displayName;
    private String roleTitle;
    private String remark;
    private String status;
    private UUID mergedToId;
    private UUID createdBy;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant deletedAt;
    @Version
    private Long version;
    // getters 和 setters
}
```

- [ ] **步骤 2: 按相同模式编写其余实体类**

- [ ] **步骤 3: 提交**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/
git commit -m "feat: 添加核心表的 MyBatis-Plus 实体类"
```

---

### 任务 5: Mapper — MyBatis-Plus mapper 接口 + XML

**文件：**
- 创建: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/` 下所有 mapper 接口
- 创建: `demo/message-center-spring/backend/src/main/resources/mapper/` 下 XML mapper 文件

**接口：**
- 产出: `extends BaseMapper<Entity>` 接口，包含复杂 SQL 的自定义查询方法（带用户权限范围的联系人搜索、带游标的消息分页、会话线程查询）

- [ ] **步骤 1: 编写 ContactMapper.java**

```java
package com.crmforlogistics.messagecenter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.crmforlogistics.messagecenter.entity.ContactEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.Instant;
import java.util.UUID;

@Mapper
public interface ContactMapper extends BaseMapper<ContactEntity> {
    IPage<ContactEntity> listForUser(IPage<ContactEntity> page,
                                     @Param("userId") UUID userId,
                                     @Param("search") String search,
                                     @Param("beforeLastMessageAt") Instant beforeLastMessageAt,
                                     @Param("beforeId") UUID beforeId);
}
```

- [ ] **步骤 2: 编写 ContactMapper.xml**

将 `JdbcContactRepository.listForUser()`（第 27-107 行）中的复杂 SQL 翻译为 MyBatis XML，保留权限范围子查询（用户拥有的联系人 OR 团队分配的会话 OR 访问授权）以及搜索/分页逻辑。参考旧代码：`demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/JdbcContactRepository.java:27-107`。

- [ ] **步骤 3: 编写 MessageMapper 和 ConversationMapper**

类似地移植 `JdbcMessageRepository` 方法：`getOrCreateConversation`、`insert`（原子摄入序列号 + 幂等性）、`listMessages`（游标分页）、`findMessage`。复杂的事务逻辑放在 Service 层，Mapper 提供 SQL 原语。

- [ ] **步骤 4: 提交**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ \
        demo/message-center-spring/backend/src/main/resources/mapper/
git commit -m "feat: 添加联系人和消息的 MyBatis-Plus mapper 接口及 XML"
```

---

### 任务 6: 安全 — Spring Security 配合数据库认证

**文件：**
- 创建: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/config/SecurityConfig.java`
- 创建: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/auth/UserDetailsServiceImpl.java`
- 创建: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/infrastructure/SecurityUtil.java`

**接口：**
- 消费: `UserMapper`、`SessionMapper`、Argon2 密码哈希器
- 产出: `SecurityConfig` 过滤器链 bean、`UserDetailsServiceImpl`（从数据库加载用户，验证 Argon2 哈希）

- [ ] **步骤 1: 编写 SecurityConfig.java**

```java
package com.crmforlogistics.messagecenter.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/**").permitAll()
                .requestMatchers("/api/**").authenticated()
                .anyRequest().permitAll()
            );
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }
}
```

- [ ] **步骤 2: 编写 UserDetailsServiceImpl.java**

将 `JdbcAuthRepository` 和 `PasswordHasher` 中的认证逻辑移植过来。通过 UserMapper 从 `users` 表按用户名加载用户，返回 Spring Security `UserDetails`，包含从 `user_roles` 表 join 获取的授权信息。

- [ ] **步骤 3: 编写 SecurityUtil.java**

```java
package com.crmforlogistics.messagecenter.infrastructure;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.UUID;

public final class SecurityUtil {
    private SecurityUtil() {}

    public static UUID currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) throw new SecurityException("未认证");
        return UUID.fromString(auth.getName());
    }
}
```

- [ ] **步骤 4: 编写 AuthController.java — 登录/令牌接口**

```java
package com.crmforlogistics.messagecenter.web;

import com.crmforlogistics.messagecenter.dto.request.LoginRequest;
import com.crmforlogistics.messagecenter.dto.response.LoginResponse;
import com.crmforlogistics.messagecenter.service.auth.UserDetailsServiceImpl;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Base64;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationConfiguration authConfig;
    private final SessionMapper sessionMapper;

    public AuthController(AuthenticationConfiguration authConfig, SessionMapper sessionMapper) {
        this.authConfig = authConfig;
        this.sessionMapper = sessionMapper;
    }

    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest request) throws Exception {
        Authentication auth = authConfig.getAuthenticationManager()
                .authenticate(new UsernamePasswordAuthenticationToken(request.username(), request.password()));
        SecurityContextHolder.getContext().setAuthentication(auth);
        String token = UUID.randomUUID().toString();
        // 通过 SessionMapper 插入会话到 user_sessions 表
        return new LoginResponse(token, auth.getName());
    }

    @PostMapping("/logout")
    public void logout() {
        SecurityContextHolder.clearContext();
    }
}
```

- [ ] **步骤 5: 验证编译**

```bash
cd demo/message-center-spring/backend && mvn compile -q
```

- [ ] **步骤 6: 提交**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/config/SecurityConfig.java \
        demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/auth/ \
        demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/infrastructure/SecurityUtil.java \
        demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/AuthController.java
git commit -m "feat: 添加 Spring Security 配合 Argon2 密码编码器和登录接口"
```

---

### 任务 7: Bootstrap 管理员服务

**文件：**
- 创建: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/auth/BootstrapService.java`

**接口：**
- 消费: `UserMapper`、`PasswordEncoder`
- 产出: `BootstrapService.bootstrapAdmin(username, password)` — 幂等的管理员用户创建

- [ ] **步骤 1: 编写 BootstrapService.java**

从旧代码库中的 `SessionService.bootstrapAdmin()` 移植。检查管理员用户是否存在；如果不存在，插入到 `users` 表（使用 Argon2 哈希密码）并通过 `user_roles` 分配 `admin` 角色。返回 `BootstrapResult(created boolean, userId UUID, code String)`。

- [ ] **步骤 2: 向 App.java 添加 bootstrap-admin CLI 命令**

```java
@SpringBootApplication
public class App {
    public static void main(String[] args) {
        if (args.length > 0 && "bootstrap-admin".equals(args[0])) {
            // 通过 CommandLineRunner 或直接调用执行 bootstrap
            var ctx = SpringApplication.run(App.class, args);
            var bootstrap = ctx.getBean(BootstrapService.class);
            // 读取环境变量中的管理员用户名/密码，执行 bootstrap，打印 JSON 结果
            System.exit(SpringApplication.exit(ctx));
        }
        SpringApplication.run(App.class, args);
    }
}
```

- [ ] **步骤 3: 提交**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/auth/BootstrapService.java \
        demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/App.java
git commit -m "feat: 添加 bootstrap-admin 服务用于初始管理员用户创建"
```

---

### 任务 8: ContactService — 联系人列表和 CRUD

**文件：**
- 创建: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contact/ContactService.java`
- 创建: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/ContactResponse.java`

**接口：**
- 消费: `ContactMapper`、`ContactIdentityMapper`、`MessageMapper`
- 产出: `ContactService.listForUser(userId, query)` 返回 `List<ContactResponse>`

- [ ] **步骤 1: 编写 ContactResponse.java**

```java
package com.crmforlogistics.messagecenter.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ContactResponse(
        UUID id,
        String displayName,
        String remark,
        List<String> channelTypes,
        Instant lastMessageAt,
        int unreadCount
) {}
```

- [ ] **步骤 2: 编写 ContactService.java**

移植 `UnifiedMessageStore.contacts()` 和 `JdbcContactRepository.listForUser()` 中的业务逻辑：
- 通过 ContactMapper.listForUser 查询联系人，带用户权限范围
- 对每个联系人，通过 contact_identities 解析渠道类型
- 通过 conversation_read_states join 统计未读消息数
- 通过 mapper 的 sort_at 列填充 lastMessageAt

- [ ] **步骤 3: 提交**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contact/ \
        demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/
git commit -m "feat: 添加 ContactService 含用户范围列表查询和 DTO"
```

---

### 任务 9: ContactController + ContactGroupController

**文件：**
- 创建: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/ContactController.java`
- 创建: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contact/ContactGroupService.java`
- 创建: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/request/ContactGroupRequest.java`

**接口：**
- 消费: `ContactService`、`ContactGroupService`
- 产出: `GET /api/contacts`、`POST /api/contact-groups/{merge,split,remark,profile}`、`GET /api/contact-groups`

- [ ] **步骤 1: 编写 ContactController.java**

翻译旧 `App.route()` 中以下路由的处理器：
- `GET /api/contacts` → `contactService.listForUser(userId, query(params))`
- `GET /api/contact-groups` → `contactGroupService.list()`
- `POST /api/contact-groups/merge` → `contactGroupService.merge(request)`
- `POST /api/contact-groups/split` → `contactGroupService.split(request)`
- `POST /api/contact-groups/remark` → `contactGroupService.updateRemark(request)`
- `POST /api/contact-groups/profile` → `contactGroupService.updateProfile(request)`

参考旧路由：`demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java`，route() 方法中联系人相关的处理器代码块。

- [ ] **步骤 2: 编写 ContactGroupService.java**

移植 `UnifiedMessageStore.mergeContacts()`、`splitContact()`、`updateContactRemark()`、`updateContactProfile()` — 每个方法将旧的基于 JSONL 文件的变更翻译为对 `contacts` 和 `contact_identities` 表的数据库 CRUD 操作，使用基于版本的乐观锁。

- [ ] **步骤 3: 提交**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/ContactController.java \
        demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contact/ContactGroupService.java \
        demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/request/ContactGroupRequest.java
git commit -m "feat: 添加 ContactController 和 ContactGroupService 用于联系人 CRUD"
```

---

### 任务 10: ThreadService + ThreadController

**文件：**
- 创建: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/message/ThreadService.java`
- 创建: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/ThreadController.java`
- 创建: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/ThreadResponse.java`

**接口：**
- 消费: `ConversationMapper`、`MessageMapper`、`ContactMapper`、`ChannelAccountMapper`
- 产出: `GET /api/threads?contactId={id}` → `ThreadResponse`（游标分页消息列表 + 线程元数据）

- [ ] **步骤 1: 编写 ThreadResponse.java**

```java
public record ThreadResponse(
        List<MessageResponse> items,
        String nextCursor,
        int messageCount,
        String threadRevision
) {}
```

- [ ] **步骤 2: 编写 ThreadService.java**

移植 `UnifiedMessageStore.threadPage()` 的游标分页逻辑：
- 接受 userId、contactId、channelType、cursor 参数
- 解析给定 channelType 的联系人身份和渠道账号
- 通过 `conversations` 表查找或创建会话
- 使用游标分页查询消息（base64 编码的 timestamp+id 游标）
- 返回 `ThreadResponse` 含下一游标

- [ ] **步骤 3: 编写 ThreadController.java**

```java
@RestController
@RequestMapping("/api")
public class ThreadController {
    private final ThreadService threadService;

    @GetMapping("/threads")
    public ThreadResponse listThreads(
            @RequestParam UUID contactId,
            @RequestParam(required = false) String channelType,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "10") int limit) {
        return threadService.threadPage(SecurityUtil.currentUserId(), contactId, channelType, cursor, limit);
    }
}
```

- [ ] **步骤 4: 提交**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/message/ThreadService.java \
        demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/ThreadController.java \
        demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/ThreadResponse.java
git commit -m "feat: 添加 ThreadService 和 ThreadController 含游标分页"
```

---

### 任务 11: MessageController — 发送、单条消息和渠道能力

**文件：**
- 创建: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/MessageController.java`
- 创建: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/request/SendMessageRequest.java`
- 创建: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/MessageResponse.java`

**接口：**
- 消费: `MessageService`（发送逻辑）、`ThreadService`
- 产出: `GET /api/messages/{id}`、`POST /api/send/{email,chatapp}`、`GET /api/channel-capabilities`

- [ ] **步骤 1: 编写 SendMessageRequest.java**

```java
public record SendMessageRequest(
        @NotNull UUID contactId,
        @NotNull @Size(min = 1) String body,
        String channelType,
        String templateCode,
        String language,
        Map<String, String> templateParams
) {}
```

- [ ] **步骤 2: 编写 MessageResponse.java**

```java
public record MessageResponse(
        UUID id,
        String direction,
        String kind,
        String subject,
        String bodyText,
        String bodyHtml,
        String channelType,
        String from,
        String to,
        Instant occurredAt,
        String status,
        int ingestSequence
) {}
```

- [ ] **步骤 3: 编写 MessageController.java**

翻译旧路由：
- `GET /api/messages/{id}` → 按 ID 返回单条消息
- `POST /api/send/email` → 邮件发送（第一阶段为桩实现 — 未配置时返回不支持）
- `POST /api/send/chatapp` → ChatApp 发送（第一阶段为桩实现）
- `GET /api/channel-capabilities` → 返回联系人的可用渠道

第一阶段 `/api/send/*` 接口返回占位响应（状态码 501）或委托给桩服务。完整的邮件/ChatApp 发送留到后续阶段。

- [ ] **步骤 4: 提交**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/MessageController.java \
        demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/request/SendMessageRequest.java \
        demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/MessageResponse.java
git commit -m "feat: 添加 MessageController 含发送和渠道能力接口"
```

---

### 任务 12: TemplateController

**文件：**
- 创建: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/TemplateController.java`
- 创建: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/chatapp/ChatAppTemplateService.java`

**接口：**
- 消费: TemplateStore（将旧的 JSON 文件 TemplateStore 移植为第一阶段数据库存储）
- 产出: `GET /api/templates`

- [ ] **步骤 1: 编写 ChatAppTemplateService.java**

从旧代码库移植 `TemplateStore`。第一阶段使用 `message_templates` 表（已在 V2 迁移脚本中定义）。查询给定渠道账号的模板，返回模板列表（含 templateCode、name、languageCode、body、placeholders）。

- [ ] **步骤 2: 编写 TemplateController.java**

```java
@RestController
@RequestMapping("/api")
public class TemplateController {
    private final ChatAppTemplateService templateService;

    @GetMapping("/templates")
    public List<TemplateResponse> listTemplates() {
        return templateService.listAll();
    }
}
```

- [ ] **步骤 3: 提交**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/TemplateController.java \
        demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/chatapp/ChatAppTemplateService.java
git commit -m "feat: 添加 TemplateController 和 ChatAppTemplateService"
```

---

### 任务 13: SSE EventHub + SseController

**文件：**
- 创建: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/event/EventHub.java`
- 创建: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/SseController.java`

**接口：**
- 消费: Spring MVC `SseEmitter`
- 产出: `GET /api/events` SSE 流、`EventHub.publish(event)` 供其他服务推送事件

- [ ] **步骤 1: 编写 EventHub.java**

```java
package com.crmforlogistics.messagecenter.service.event;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class EventHub {
    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public SseEmitter connect() {
        SseEmitter emitter = new SseEmitter(0L); // 无超时
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        return emitter;
    }

    public void publish(String eventType, Object data) {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(eventType).data(data));
            } catch (IOException e) {
                emitters.remove(emitter);
            }
        }
    }

    public void publishTemplatesChanged() {
        publish("templates-changed", "{}");
    }
}
```

- [ ] **步骤 2: 编写 SseController.java**

```java
@RestController
@RequestMapping("/api")
public class SseController {
    private final EventHub eventHub;

    @GetMapping("/events")
    public SseEmitter streamEvents() {
        return eventHub.connect();
    }
}
```

- [ ] **步骤 3: 提交**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/event/EventHub.java \
        demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/SseController.java
git commit -m "feat: 添加 SSE EventHub 和 SseController"
```

---

### 任务 14: MinIO 存储适配器 + MediaController

**文件：**
- 创建: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/config/MinioConfig.java`
- 创建: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/infrastructure/MinioStorage.java`
- 创建: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/MediaController.java`

**接口：**
- 消费: MinioClient bean
- 产出: `GET /api/media/{id}`（通过预签名 URL 重定向或字节流方式从 MinIO 提供媒体文件）、`MinioStorage.store(bytes, contentType)` → `(objectKey, mediaId)`

- [ ] **步骤 1: 编写 MinioConfig.java**

```java
package com.crmforlogistics.messagecenter.config;

import io.minio.MinioClient;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MinioConfig {

    @Bean
    @ConfigurationProperties(prefix = "minio")
    public MinioProperties minioProperties() {
        return new MinioProperties();
    }

    @Bean
    public MinioClient minioClient(MinioProperties props) {
        return MinioClient.builder()
                .endpoint(props.endpoint())
                .credentials(props.accessKey(), props.secretKey())
                .build();
    }

    public record MinioProperties(String endpoint, String accessKey, String secretKey, String bucket) {}
}
```

- [ ] **步骤 2: 编写 MinioStorage.java**

```java
package com.crmforlogistics.messagecenter.infrastructure;

import io.minio.*;
import org.springframework.stereotype.Service;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.UUID;

@Service
public class MinioStorage {
    private final MinioClient client;
    private final String bucket;

    public MinioStorage(MinioClient client, MinioConfig.MinioProperties props) {
        this.client = client;
        this.bucket = props.bucket();
        ensureBucket();
    }

    private void ensureBucket() {
        try {
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            }
        } catch (Exception e) {
            throw new RuntimeException("MinIO bucket 初始化失败", e);
        }
    }

    public String store(byte[] data, String contentType) throws Exception {
        String objectKey = UUID.randomUUID().toString();
        client.putObject(PutObjectArgs.builder()
                .bucket(bucket).object(objectKey)
                .stream(new ByteArrayInputStream(data), data.length, -1)
                .contentType(contentType).build());
        return objectKey;
    }

    public InputStream get(String objectKey) throws Exception {
        return client.getObject(GetObjectArgs.builder().bucket(bucket).object(objectKey).build());
    }
}
```

- [ ] **步骤 3: 编写 MediaController.java**

```java
@RestController
@RequestMapping("/api")
public class MediaController {
    private final MinioStorage storage;

    @GetMapping("/media/{id}")
    public ResponseEntity<InputStreamResource> getMedia(@PathVariable String id) throws Exception {
        InputStream stream = storage.get(id);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new InputStreamResource(stream));
    }
}
```

- [ ] **步骤 4: 提交**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/config/MinioConfig.java \
        demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/infrastructure/MinioStorage.java \
        demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/MediaController.java
git commit -m "feat: 添加 MinIO 存储适配器和媒体服务接口"
```

---

### 任务 15: GlobalExceptionHandler

**文件：**
- 创建: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/GlobalExceptionHandler.java`
- 创建: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/ApiError.java`

**接口：**
- 产出: 统一 JSON 错误格式 `{code, message, traceId, fieldErrors}`，匹配旧 `App.writeRouteError()` 格式

- [ ] **步骤 1: 编写 ApiError.java**

```java
public record ApiError(String code, String message, String traceId, Map<String, String> fieldErrors) {}
```

- [ ] **步骤 2: 编写 GlobalExceptionHandler.java**

```java
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(SecurityException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ApiError handleSecurity(SecurityException e) { ... }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError handleBadRequest(IllegalArgumentException e) { ... }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiError handleGeneral(Exception e) { ... }
}
```

匹配旧错误格式：`{"code":"FORBIDDEN","message":"...", "traceId":"uuid", "fieldErrors":{}}`。

- [ ] **步骤 3: 提交**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/GlobalExceptionHandler.java \
        demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/ApiError.java
git commit -m "feat: 添加统一错误格式的全局异常处理器"
```

---

### 任务 16: 集成测试 — 验证核心 API 流程

**文件：**
- 创建: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/AppIntegrationTest.java`

**接口：**
- 消费: Testcontainers PostgreSQL、Spring Boot Test
- 产出: 集成测试验证 bootstrap-admin → login → list contacts → list threads 流程

- [ ] **步骤 1: 使用 Testcontainers 编写 AppIntegrationTest.java**

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
public class AppIntegrationTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.5")
            .withDatabaseName("message_center")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired TestRestTemplate rest;

    @Test
    void bootstrapAndLogin() { ... }

    @Test
    void listContactsReturnsEmptyForNewUser() { ... }
}
```

- [ ] **步骤 2: 运行测试**

```bash
cd demo/message-center-spring/backend && mvn test -Dtest=AppIntegrationTest
```

期望: 测试通过，使用 Testcontainers PostgreSQL。

- [ ] **步骤 3: 提交**

```bash
git add demo/message-center-spring/backend/src/test/
git commit -m "test: 添加 Testcontainers PostgreSQL 集成测试"
```

---

### 任务 17: 前端 — 分析文档

**文件：**
- 创建: `docs/superpowers/specs/2026-08-04-message-center-frontend-analysis.md`

**接口：**
- 产出: 对当前 `App.java` 嵌入式页面的完整分析 — 组件树、数据流、SSE 事件、API 全景、交互模式

- [ ] **步骤 1: 分析现有 HTML 页面**

阅读 `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java`，具体关注：
- `pageHtml(...)` 方法 — 提取完整 HTML/CSS/JS 结构
- 所有对 `/api/*` 接口的 `fetch()` 调用 — 记录请求/响应格式
- SSE 事件处理 — 记录事件类型和数据模式
- UI 组件：联系人列表、消息视图、消息气泡、发送表单、模板选择器、联系人组管理

- [ ] **步骤 2: 编写分析文档**

文档包含以下章节：
1. **页面布局** — 单页应用的线框描述
2. **组件树** — UI 组件的层次分解
3. **数据流** — API 调用 → state → 渲染 循环
4. **SSE 事件** — 事件类型表及负载模式
5. **路由** — 所有 `/api/*` 调用（含方法、参数、响应格式）
6. **交互流程** — 发送消息、合并联系人、查看消息面板等

- [ ] **步骤 3: 提交**

```bash
git add docs/superpowers/specs/2026-08-04-message-center-frontend-analysis.md
git commit -m "docs: 添加 React SPA 迁移前端分析文档"
```

- [ ] **步骤 4: 提交给用户评审**

在开始前端实现之前，将分析文档提交给用户审批。

---

### 任务 18: 前端 — React SPA 脚手架

**文件：**
- 创建: `demo/message-center-spring/frontend/package.json`
- 创建: `demo/message-center-spring/frontend/vite.config.ts`
- 创建: `demo/message-center-spring/frontend/tsconfig.json`
- 创建: `demo/message-center-spring/frontend/index.html`
- 创建: `demo/message-center-spring/frontend/src/main.tsx`
- 创建: `demo/message-center-spring/frontend/src/App.tsx`
- 创建: `demo/message-center-spring/frontend/src/router.tsx`
- 创建: `demo/message-center-spring/frontend/src/api/client.ts`

**接口：**
- 产出: 运行中的 Vite 开发服务器，配置好 React、Ant Design、TanStack Query、React Router、Axios

- [ ] **步骤 1: 使用 Vite 搭建脚手架**

```bash
cd demo/message-center-spring
npm create vite@latest frontend -- --template react-ts
cd frontend
npm install
```

- [ ] **步骤 2: 添加依赖**

```bash
cd demo/message-center-spring/frontend
npm install antd @ant-design/icons @tanstack/react-query react-router-dom axios
```

- [ ] **步骤 3: 配置 vite.config.ts** — 代理 `/api/*` 到 localhost:8099

```typescript
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8099'
    }
  }
})
```

- [ ] **步骤 4: 编写 api/client.ts** — Axios 实例（含 baseURL）

```typescript
import axios from 'axios';

const client = axios.create({
  baseURL: '/api',
  withCredentials: true,
});

client.interceptors.response.use(
  r => r,
  err => {
    if (err.response?.status === 401) window.location.href = '/login';
    return Promise.reject(err);
  }
);

export default client;
```

- [ ] **步骤 5: 编写 router.tsx** — 路由定义

```typescript
import { createBrowserRouter } from 'react-router-dom';

export const router = createBrowserRouter([
  { path: '/login', element: <LoginPage /> },
  { path: '/', element: <AppLayout />, children: [
    { index: true, element: <ContactsPage /> },
    { path: 'thread/:contactId', element: <ThreadPage /> },
    { path: 'send', element: <SendPage /> },
    { path: 'templates', element: <TemplatesPage /> },
  ]},
]);
```

- [ ] **步骤 6: 验证开发服务器启动**

```bash
cd demo/message-center-spring/frontend && npm run dev
```

打开 http://localhost:5173 — 应看到 Ant Design 外壳。

- [ ] **步骤 7: 提交**

```bash
git add demo/message-center-spring/frontend/
git commit -m "feat: 搭建 React SPA 脚手架，含 Vite、Ant Design 和 TanStack Query"
```

---

### 任务 19: 前端 — 页面实现

**文件：**
- 创建: `demo/message-center-spring/frontend/src/pages/LoginPage.tsx`
- 创建: `demo/message-center-spring/frontend/src/pages/ContactsPage.tsx`
- 创建: `demo/message-center-spring/frontend/src/pages/ThreadPage.tsx`
- 创建: `demo/message-center-spring/frontend/src/pages/SendPage.tsx`
- 创建: `demo/message-center-spring/frontend/src/pages/TemplatesPage.tsx`
- 创建: `demo/message-center-spring/frontend/src/components/AppLayout.tsx`
- 创建: `demo/message-center-spring/frontend/src/components/ContactCard.tsx`
- 创建: `demo/message-center-spring/frontend/src/components/MessageBubble.tsx`
- 创建: `demo/message-center-spring/frontend/src/components/SendForm.tsx`
- 创建: `demo/message-center-spring/frontend/src/components/SseProvider.tsx`
- 创建: `demo/message-center-spring/frontend/src/hooks/useContacts.ts`
- 创建: `demo/message-center-spring/frontend/src/hooks/useMessages.ts`
- 创建: `demo/message-center-spring/frontend/src/hooks/useSse.ts`

实现指南：构建每个页面/组件以匹配旧嵌入式页面 UI。使用 Ant Design 组件（Card、List、Input、Button、Select、Modal、message、notification）。使用 TanStack Query 进行数据获取，在 SSE 事件时进行缓存失效。每个页面实现与旧页面完全相同的交互模式。

- [ ] **步骤 1: 编写 SseProvider.tsx** — 包装 `/api/events` EventSource 的 context

- [ ] **步骤 2: 编写 useContacts.ts** — TanStack Query hook 包装 `GET /api/contacts`，含搜索/合并/拆分 mutations

- [ ] **步骤 3: 编写 ContactsPage.tsx** — 联系人列表，含搜索、渠道过滤、合并/拆分模态框

- [ ] **步骤 4: 编写 useMessages.ts** — 基于游标的无限查询，用于消息面板消息

- [ ] **步骤 5: 编写 ThreadPage.tsx** — 消息时间线，含基于游标的无限滚动，底部发送表单

- [ ] **步骤 6: 编写 SendPage.tsx** — 多渠道发送表单（邮件/ChatApp），含模板选择器

- [ ] **步骤 7: 编写 TemplatesPage.tsx** — 模板列表，含搜索

- [ ] **步骤 8: 编写 LoginPage.tsx** — 用户名/密码表单，成功后跳转到 /

- [ ] **步骤 9: 提交**

```bash
git add demo/message-center-spring/frontend/src/
git commit -m "feat: 实现 React 页面，匹配原嵌入式 UI"
```

---

### 任务 20: 端到端验证

- [ ] **步骤 1: 启动基础设施**

```bash
cd demo/message-center-spring/backend && docker compose up -d
```

- [ ] **步骤 2: 创建管理员用户**

```bash
cd demo/message-center-spring/backend && mvn spring-boot:run -Dspring-boot.run.arguments="bootstrap-admin"
```

- [ ] **步骤 3: 启动后端**

```bash
cd demo/message-center-spring/backend && mvn spring-boot:run
```

期望: Tomcat 在 8099 端口启动，Flyway 执行迁移，健康检查返回 200。

- [ ] **步骤 4: 启动前端**

```bash
cd demo/message-center-spring/frontend && npm run dev
```

- [ ] **步骤 5: 手动测试流程**

1. 打开 http://localhost:5173
2. 使用管理员凭据登录
3. 看到联系人页面（空）
4. 验证 SSE 连接已建立
5. 验证错误状态正确显示

- [ ] **步骤 6: 提交所有修复**

```bash
git commit -m "fix: 端到端验证修复"
```
