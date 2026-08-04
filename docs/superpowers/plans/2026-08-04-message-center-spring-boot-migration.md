# message-center Spring Boot MVC Migration — Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Scaffold `demo/message-center-spring/` as a Spring Boot 3.4.x MVC project with MyBatis-Plus, Spring Security, MinIO, and core contacts/messages/threads APIs, referencing `demo/message-center-demo/` for business logic.

**Architecture:** Spring Boot 3.4.x + MVC (Tomcat). Jackson for JSON, MyBatis-Plus with XML Mapper for data access, Spring Security for authentication, MinIO for file storage. Frontend is a React + TypeScript + Vite + Ant Design SPA, functionally identical to the current embedded page in the old `App.java`.

**Tech Stack:** Java 17, Spring Boot 3.4.x, Spring MVC, Spring Security, MyBatis-Plus, Flyway, PostgreSQL, MinIO, Jackson, React 18, TypeScript 5, Vite 5, Ant Design 5, TanStack Query, React Router 6, Axios.

## Global Constraints

- Java 17 minimum
- Spring Boot 3.x
- Spring Security
- MyBatis-Plus + XML Mapper
- PostgreSQL, first phase no pgvector
- MinIO for file storage
- Flyway for database migration
- Docker Compose single-machine private deployment
- Frontend UI/UX must match the old embedded page exactly
- New code in `demo/message-center-spring/`, old code in `demo/message-center-demo/` is read-only reference
- Jackson replaces Gson

---

## File Structure Map

```
demo/message-center-spring/
├── backend/
│   ├── pom.xml
│   ├── compose.yaml
│   ├── secrets/                           # copied from old project
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
│       │   ├── entity/                    # MyBatis-Plus entities
│       │   │   ├── UserEntity.java
│       │   │   ├── SessionEntity.java
│       │   │   ├── ContactEntity.java
│       │   │   ├── ContactIdentityEntity.java
│       │   │   ├── ConversationEntity.java
│       │   │   ├── MessageEntity.java
│       │   │   ├── ChannelAccountEntity.java
│       │   │   └── CompanyEntity.java
│       │   ├── mapper/                    # MyBatis-Plus mapper interfaces
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
│           ├── db/migration/              # copied from old project
│           │   ├── V1__identity_and_contact.sql
│           │   ├── V2__channel_conversation_message.sql
│           │   ├── V3__events_storage_import.sql
│           │   ├── V4__tag_search_indexes.sql
│           │   └── V5__wecom_daily_summary.sql
│           └── mapper/                    # MyBatis XML mappers
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

### Task 1: Create project directory and pom.xml

**Files:**
- Create: `demo/message-center-spring/backend/pom.xml`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/App.java`

**Interfaces:**
- Produces: Spring Boot 3.4.x parent POM with all Phase 1 dependencies

- [ ] **Step 1: Create directory structure**

```bash
mkdir -p demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter
mkdir -p demo/message-center-spring/backend/src/main/resources
mkdir -p demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter
```

- [ ] **Step 2: Write pom.xml**

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

        <!-- BouncyCastle (for WeCom crypto, deferred phase) -->
        <dependency>
            <groupId>org.bouncycastle</groupId>
            <artifactId>bcprov-jdk18on</artifactId>
            <version>1.78.1</version>
        </dependency>

        <!-- Mail (for email send, deferred phase) -->
        <dependency>
            <groupId>com.sun.mail</groupId>
            <artifactId>jakarta.mail</artifactId>
            <version>2.0.1</version>
        </dependency>

        <!-- Aliyun CAMS SDK (for chatapp, deferred phase) -->
        <dependency>
            <groupId>com.aliyun</groupId>
            <artifactId>alibabacloud-cams20200606</artifactId>
            <version>5.0.5</version>
        </dependency>

        <!-- Argon2 password hasher -->
        <dependency>
            <groupId>de.mkammerer</groupId>
            <artifactId>argon2-jvm</artifactId>
            <version>2.11</version>
        </dependency>

        <!-- Test -->
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

- [ ] **Step 3: Write minimal App.java to verify scaffold**

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

- [ ] **Step 4: Write empty application.yml**

```yaml
spring:
  application:
    name: message-center
  profiles:
    active: dev
```

- [ ] **Step 5: Verify build compiles**

```bash
cd demo/message-center-spring/backend && mvn compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add demo/message-center-spring/backend/pom.xml demo/message-center-spring/backend/src/
git commit -m "chore: scaffold Spring Boot 3.4.5 project with phase 1 dependencies"
```

---

### Task 2: Copy compose.yaml, secrets, and Flyway migrations

**Files:**
- Copy: `demo/message-center-demo/compose.yaml` → `demo/message-center-spring/backend/compose.yaml`
- Copy: `demo/message-center-demo/secrets/` → `demo/message-center-spring/backend/secrets/`
- Copy: `demo/message-center-demo/src/main/resources/db/migration/` → `demo/message-center-spring/backend/src/main/resources/db/migration/`

**Interfaces:**
- Produces: Docker Compose services (postgres:17.5, minio, minio-init) and V1-V5 Flyway scripts

- [ ] **Step 1: Copy infrastructure files**

```bash
cp demo/message-center-demo/compose.yaml demo/message-center-spring/backend/compose.yaml
cp -r demo/message-center-demo/secrets demo/message-center-spring/backend/secrets
mkdir -p demo/message-center-spring/backend/src/main/resources/db/migration
cp demo/message-center-demo/src/main/resources/db/migration/V*.sql demo/message-center-spring/backend/src/main/resources/db/migration/
```

- [ ] **Step 2: Write application-dev.yml with datasource and flyway config**

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

- [ ] **Step 3: Commit**

```bash
git add demo/message-center-spring/backend/compose.yaml \
        demo/message-center-spring/backend/secrets/ \
        demo/message-center-spring/backend/src/main/resources/db/ \
        demo/message-center-spring/backend/src/main/resources/application-dev.yml
git commit -m "chore: copy compose.yaml, secrets, and flyway migrations"
```

---

### Task 3: Config layer — AppConfig, CorsConfig, JacksonConfig, MyBatisPlusConfig

**Files:**
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/config/AppConfig.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/config/CorsConfig.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/config/JacksonConfig.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/config/MyBatisPlusConfig.java`
- Modify: `demo/message-center-spring/backend/src/main/resources/application.yml`

**Interfaces:**
- Produces: `AppConfig` properties record, `CorsConfig` WebMvcConfigurer bean, `JacksonConfig` ObjectMapper bean, `MyBatisPlusConfig` pagination interceptor bean

- [ ] **Step 1: Write AppConfig.java**

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

- [ ] **Step 2: Write CorsConfig.java**

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

- [ ] **Step 3: Write JacksonConfig.java**

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

- [ ] **Step 4: Write MyBatisPlusConfig.java**

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

- [ ] **Step 5: Update application.yml** with MyBatis-Plus settings

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

- [ ] **Step 6: Verify compile**

```bash
cd demo/message-center-spring/backend && mvn compile -q
```

- [ ] **Step 7: Commit**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/config/ \
        demo/message-center-spring/backend/src/main/resources/application.yml
git commit -m "feat: add config layer with AppConfig, Cors, Jackson, and MyBatis-Plus"
```

---

### Task 4: Entities — MyBatis-Plus entity classes

**Files:**
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/UserEntity.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/ContactEntity.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/ContactIdentityEntity.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/ConversationEntity.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/MessageEntity.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/ChannelAccountEntity.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/CompanyEntity.java`

**Interfaces:**
- Produces: Entity classes mapping to Flyway-created PostgreSQL tables, using `@TableName`, `@TableId`, and `@TableField` annotations

Implementation guide: Each entity maps 1:1 to a DB table defined in V1-V2 Flyway scripts. Use `@TableName` for table name, `@TableId(type = IdType.ASSIGN_UUID)` for UUID PKs, and `@TableField` for columns where name differs from camelCase. Example patterns below; all entities follow the same structure.

- [ ] **Step 1: Write ContactEntity.java**

Reference: V1__identity_and_contact.sql `contacts` table. Map `contacts` table columns: id (UUID PK, auto gen), display_name, role_title, remark, status, merged_to_id, created_by, created_at, updated_at, deleted_at, version.

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
    // getters and setters
}
```

- [ ] **Step 2: Write remaining entities following the same pattern**

- [ ] **Step 3: Commit**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/entity/
git commit -m "feat: add MyBatis-Plus entity classes for core tables"
```

---

### Task 5: Mappers — MyBatis-Plus mapper interfaces + XML

**Files:**
- Create: all mapper interfaces under `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/`
- Create: XML mapper files under `demo/message-center-spring/backend/src/main/resources/mapper/`

**Interfaces:**
- Produces: `extends BaseMapper<Entity>` interfaces with custom query methods for complex SQL (contact search with user permission scoping, message pagination with cursor, conversation thread queries)

- [ ] **Step 1: Write ContactMapper.java**

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

- [ ] **Step 2: Write ContactMapper.xml**

Translate the complex SQL from `JdbcContactRepository.listForUser()` (lines 27-107) into MyBatis XML, preserving the permission-scoped subquery (user-owned contacts OR team-assigned conversations OR access grants) and the search/pagination logic. Reference the old code at `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/JdbcContactRepository.java:27-107`.

- [ ] **Step 3: Write MessageMapper with ConversationMapper**

Similarly port `JdbcMessageRepository` methods: `getOrCreateConversation`, `insert` (atomic ingest sequence + idempotency), `listMessages` (cursor pagination), `findMessage`. Complex transactional logic lives in the Service layer, the Mapper provides the SQL primitives.

- [ ] **Step 4: Commit**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/mapper/ \
        demo/message-center-spring/backend/src/main/resources/mapper/
git commit -m "feat: add MyBatis-Plus mapper interfaces and XML for contacts and messages"
```

---

### Task 6: Security — Spring Security with DB-backed auth

**Files:**
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/config/SecurityConfig.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/auth/UserDetailsServiceImpl.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/infrastructure/SecurityUtil.java`

**Interfaces:**
- Consumes: `UserMapper`, `SessionMapper`, Argon2 password hasher
- Produces: `SecurityConfig` filter chain bean, `UserDetailsServiceImpl` (loads users from DB, validates Argon2 hash)

- [ ] **Step 1: Write SecurityConfig.java**

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

- [ ] **Step 2: Write UserDetailsServiceImpl.java**

Port the auth logic from `JdbcAuthRepository` and `PasswordHasher`. Load user by username from `users` table (via UserMapper), return Spring Security `UserDetails` with granted authorities from `user_roles` join.

- [ ] **Step 3: Write SecurityUtil.java**

```java
package com.crmforlogistics.messagecenter.infrastructure;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.UUID;

public final class SecurityUtil {
    private SecurityUtil() {}

    public static UUID currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) throw new SecurityException("Not authenticated");
        return UUID.fromString(auth.getName());
    }
}
```

- [ ] **Step 4: Write AuthController.java — login/token endpoint**

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
        // insert session into user_sessions table via SessionMapper
        return new LoginResponse(token, auth.getName());
    }

    @PostMapping("/logout")
    public void logout() {
        SecurityContextHolder.clearContext();
    }
}
```

- [ ] **Step 5: Verify compile**

```bash
cd demo/message-center-spring/backend && mvn compile -q
```

- [ ] **Step 6: Commit**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/config/SecurityConfig.java \
        demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/auth/ \
        demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/infrastructure/SecurityUtil.java \
        demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/AuthController.java
git commit -m "feat: add Spring Security with Argon2 password encoder and login endpoint"
```

---

### Task 7: Bootstrap admin service

**Files:**
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/auth/BootstrapService.java`

**Interfaces:**
- Consumes: `UserMapper`, `PasswordEncoder`
- Produces: `BootstrapService.bootstrapAdmin(username, password)` — idempotent admin user creation

- [ ] **Step 1: Write BootstrapService.java**

Port from `SessionService.bootstrapAdmin()` in the old codebase. Check if admin user exists; if not, insert into `users` table with Argon2-hashed password and assign `admin` role via `user_roles`. Return `BootstrapResult(created boolean, userId UUID, code String)`.

- [ ] **Step 2: Add bootstrap-admin CLI command to App.java**

```java
@SpringBootApplication
public class App {
    public static void main(String[] args) {
        if (args.length > 0 && "bootstrap-admin".equals(args[0])) {
            // bootstrap via CommandLineRunner or direct call
            var ctx = SpringApplication.run(App.class, args);
            var bootstrap = ctx.getBean(BootstrapService.class);
            // read env vars for admin user/password, bootstrap, print JSON result
            System.exit(SpringApplication.exit(ctx));
        }
        SpringApplication.run(App.class, args);
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/auth/BootstrapService.java \
        demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/App.java
git commit -m "feat: add bootstrap-admin service for initial admin user creation"
```

---

### Task 8: ContactService — contact listing and CRUD

**Files:**
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contact/ContactService.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/ContactResponse.java`

**Interfaces:**
- Consumes: `ContactMapper`, `ContactIdentityMapper`, `MessageMapper`
- Produces: `ContactService.listForUser(userId, query)` returning `List<ContactResponse>`

- [ ] **Step 1: Write ContactResponse.java**

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

- [ ] **Step 2: Write ContactService.java**

Port business logic from `UnifiedMessageStore.contacts()` and `JdbcContactRepository.listForUser()`:
- Query contacts via ContactMapper.listForUser with user permission scoping
- For each contact, resolve channel types from contact_identities
- Count unread messages via conversation_read_states join
- Enrich with lastMessageAt from the mapper's sort_at column

- [ ] **Step 3: Commit**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contact/ \
        demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/
git commit -m "feat: add ContactService with user-scoped listing and DTOs"
```

---

### Task 9: ContactController + ContactGroupController

**Files:**
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/ContactController.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contact/ContactGroupService.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/request/ContactGroupRequest.java`

**Interfaces:**
- Consumes: `ContactService`, `ContactGroupService`
- Produces: `GET /api/contacts`, `POST /api/contact-groups/{merge,split,remark,profile}`, `GET /api/contact-groups`

- [ ] **Step 1: Write ContactController.java**

Translate the old `App.route()` handlers for:
- `GET /api/contacts` → `contactService.listForUser(userId, query(params))`
- `GET /api/contact-groups` → `contactGroupService.list()`
- `POST /api/contact-groups/merge` → `contactGroupService.merge(request)`
- `POST /api/contact-groups/split` → `contactGroupService.split(request)`
- `POST /api/contact-groups/remark` → `contactGroupService.updateRemark(request)`
- `POST /api/contact-groups/profile` → `contactGroupService.updateProfile(request)`

Reference old routing: `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java`, route() method, contacts-related handler blocks.

- [ ] **Step 2: Write ContactGroupService.java**

Port `UnifiedMessageStore.mergeContacts()`, `splitContact()`, `updateContactRemark()`, `updateContactProfile()` — each method translates the old JSONL file-based mutation into DB CRUD operations on `contacts` and `contact_identities` tables, with version-based optimistic locking.

- [ ] **Step 3: Commit**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/ContactController.java \
        demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/contact/ContactGroupService.java \
        demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/request/ContactGroupRequest.java
git commit -m "feat: add ContactController and ContactGroupService for contact CRUD"
```

---

### Task 10: ThreadService + ThreadController

**Files:**
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/message/ThreadService.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/ThreadController.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/ThreadResponse.java`

**Interfaces:**
- Consumes: `ConversationMapper`, `MessageMapper`, `ContactMapper`, `ChannelAccountMapper`
- Produces: `GET /api/threads?contactId={id}` → `ThreadResponse` (cursor-paginated message list + thread metadata)

- [ ] **Step 1: Write ThreadResponse.java**

```java
public record ThreadResponse(
        List<MessageResponse> items,
        String nextCursor,
        int messageCount,
        String threadRevision
) {}
```

- [ ] **Step 2: Write ThreadService.java**

Port `UnifiedMessageStore.threadPage()` cursor-pagination logic:
- Accept userId, contactId, channelType, cursor parameters
- Resolve contact identity and channel account for the given channelType
- Find or create conversation via `conversations` table
- Query messages with cursor pagination (base64-encoded timestamp+id cursor)
- Return `ThreadResponse` with next cursor

- [ ] **Step 3: Write ThreadController.java**

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

- [ ] **Step 4: Commit**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/message/ThreadService.java \
        demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/ThreadController.java \
        demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/ThreadResponse.java
git commit -m "feat: add ThreadService and ThreadController with cursor pagination"
```

---

### Task 11: MessageController — send, single message, and channel capabilities

**Files:**
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/MessageController.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/request/SendMessageRequest.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/MessageResponse.java`

**Interfaces:**
- Consumes: `MessageService` (send logic), `ThreadService`
- Produces: `GET /api/messages/{id}`, `POST /api/send/{email,chatapp}`, `GET /api/channel-capabilities`

- [ ] **Step 1: Write SendMessageRequest.java**

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

- [ ] **Step 2: Write MessageResponse.java**

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

- [ ] **Step 3: Write MessageController.java**

Translate old routes:
- `GET /api/messages/{id}` → return single message by ID
- `POST /api/send/email` → email send (stub for Phase 1 — return unsupported if not configured)
- `POST /api/send/chatapp` → ChatApp send (stub for Phase 1)
- `GET /api/channel-capabilities` → return available channels for a contact

For Phase 1, `/api/send/*` endpoints return a placeholder response with status 501 or delegate to a stub service. Full email/ChatApp sending is deferred to a later phase.

- [ ] **Step 4: Commit**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/MessageController.java \
        demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/request/SendMessageRequest.java \
        demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/MessageResponse.java
git commit -m "feat: add MessageController with send and channel capabilities endpoints"
```

---

### Task 12: TemplateController

**Files:**
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/TemplateController.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/chatapp/ChatAppTemplateService.java`

**Interfaces:**
- Consumes: TemplateStore (port of old JSON-file TemplateStore as DB-backed in Phase 1)
- Produces: `GET /api/templates`

- [ ] **Step 1: Write ChatAppTemplateService.java**

Port `TemplateStore` from the old codebase. For Phase 1, use `message_templates` table (already defined in V2 migration). Query templates for a given channel account, return list of templates with templateCode, name, languageCode, body, placeholders.

- [ ] **Step 2: Write TemplateController.java**

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

- [ ] **Step 3: Commit**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/TemplateController.java \
        demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/chatapp/ChatAppTemplateService.java
git commit -m "feat: add TemplateController and ChatAppTemplateService"
```

---

### Task 13: SSE EventHub + SseController

**Files:**
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/event/EventHub.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/SseController.java`

**Interfaces:**
- Consumes: Spring MVC `SseEmitter`
- Produces: `GET /api/events` SSE stream, `EventHub.publish(event)` for other services to push events

- [ ] **Step 1: Write EventHub.java**

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
        SseEmitter emitter = new SseEmitter(0L); // no timeout
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

- [ ] **Step 2: Write SseController.java**

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

- [ ] **Step 3: Commit**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/service/event/EventHub.java \
        demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/SseController.java
git commit -m "feat: add SSE EventHub and SseController"
```

---

### Task 14: MinIO storage adapter + MediaController

**Files:**
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/config/MinioConfig.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/infrastructure/MinioStorage.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/MediaController.java`

**Interfaces:**
- Consumes: MinioClient bean
- Produces: `GET /api/media/{id}` (serves media from MinIO with presigned URL redirect or byte streaming), `MinioStorage.store(bytes, contentType)` → `(objectKey, mediaId)`

- [ ] **Step 1: Write MinioConfig.java**

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

- [ ] **Step 2: Write MinioStorage.java**

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
            throw new RuntimeException("MinIO bucket init failed", e);
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

- [ ] **Step 3: Write MediaController.java**

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

- [ ] **Step 4: Commit**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/config/MinioConfig.java \
        demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/infrastructure/MinioStorage.java \
        demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/MediaController.java
git commit -m "feat: add MinIO storage adapter and media serving endpoint"
```

---

### Task 15: GlobalExceptionHandler

**Files:**
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/GlobalExceptionHandler.java`
- Create: `demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/ApiError.java`

**Interfaces:**
- Produces: Unified JSON error format `{code, message, traceId, fieldErrors}` matching old `App.writeRouteError()` format

- [ ] **Step 1: Write ApiError.java**

```java
public record ApiError(String code, String message, String traceId, Map<String, String> fieldErrors) {}
```

- [ ] **Step 2: Write GlobalExceptionHandler.java**

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

Match the old error format: `{"code":"FORBIDDEN","message":"...", "traceId":"uuid", "fieldErrors":{}}`.

- [ ] **Step 3: Commit**

```bash
git add demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/web/GlobalExceptionHandler.java \
        demo/message-center-spring/backend/src/main/java/com/crmforlogistics/messagecenter/dto/response/ApiError.java
git commit -m "feat: add global exception handler with unified error format"
```

---

### Task 16: Integration test — verify core API flow

**Files:**
- Create: `demo/message-center-spring/backend/src/test/java/com/crmforlogistics/messagecenter/AppIntegrationTest.java`

**Interfaces:**
- Consumes: Testcontainers PostgreSQL, Spring Boot Test
- Produces: Integration test verifying bootstrap-admin → login → list contacts → list threads flow

- [ ] **Step 1: Write AppIntegrationTest.java with Testcontainers**

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

- [ ] **Step 2: Run test**

```bash
cd demo/message-center-spring/backend && mvn test -Dtest=AppIntegrationTest
```

Expected: Tests pass with Testcontainers PostgreSQL.

- [ ] **Step 3: Commit**

```bash
git add demo/message-center-spring/backend/src/test/
git commit -m "test: add integration test with Testcontainers PostgreSQL"
```

---

### Task 17: Frontend — analysis document

**Files:**
- Create: `docs/superpowers/specs/2026-08-04-message-center-frontend-analysis.md`

**Interfaces:**
- Produces: Full analysis of the current `App.java` embedded page — component tree, data flow, SSE events, API surface, interaction patterns

- [ ] **Step 1: Analyze the existing HTML page**

Read `demo/message-center-demo/src/main/java/com/crmforlogistics/messagecenter/App.java`, specifically:
- `pageHtml(...)` method — extract full HTML/CSS/JS structure
- All `fetch()` calls to `/api/*` endpoints — document request/response shapes
- SSE event handling — document event types and data schemas
- UI components: contact list, thread view, message bubbles, send form, template selector, contact group management

- [ ] **Step 2: Write analysis document**

Document with sections:
1. **Page layout** — wireframe description of the single-page app
2. **Component tree** — hierarchical breakdown of UI components
3. **Data flow** — API calls → state → render cycle
4. **SSE events** — event type table with payload schemas
5. **Routes** — all `/api/*` calls with method, params, response shape
6. **Interaction flows** — send message, merge contacts, view thread, etc.

- [ ] **Step 3: Commit**

```bash
git add docs/superpowers/specs/2026-08-04-message-center-frontend-analysis.md
git commit -m "docs: add frontend analysis for React SPA migration"
```

- [ ] **Step 4: Present to user for review**

Present the analysis doc to the user for approval before starting frontend implementation.

---

### Task 18: Frontend — React SPA scaffold

**Files:**
- Create: `demo/message-center-spring/frontend/package.json`
- Create: `demo/message-center-spring/frontend/vite.config.ts`
- Create: `demo/message-center-spring/frontend/tsconfig.json`
- Create: `demo/message-center-spring/frontend/index.html`
- Create: `demo/message-center-spring/frontend/src/main.tsx`
- Create: `demo/message-center-spring/frontend/src/App.tsx`
- Create: `demo/message-center-spring/frontend/src/router.tsx`
- Create: `demo/message-center-spring/frontend/src/api/client.ts`

**Interfaces:**
- Produces: Running Vite dev server with React, Ant Design, TanStack Query, React Router, Axios configured

- [ ] **Step 1: Scaffold with Vite**

```bash
cd demo/message-center-spring
npm create vite@latest frontend -- --template react-ts
cd frontend
npm install
```

- [ ] **Step 2: Add dependencies**

```bash
cd demo/message-center-spring/frontend
npm install antd @ant-design/icons @tanstack/react-query react-router-dom axios
```

- [ ] **Step 3: Configure vite.config.ts** — proxy `/api/*` to localhost:8099

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

- [ ] **Step 4: Write api/client.ts** — Axios instance with baseURL

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

- [ ] **Step 5: Write router.tsx** — route definitions

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

- [ ] **Step 6: Verify dev server starts**

```bash
cd demo/message-center-spring/frontend && npm run dev
```

Open http://localhost:5173 — should see the Ant Design shell.

- [ ] **Step 7: Commit**

```bash
git add demo/message-center-spring/frontend/
git commit -m "feat: scaffold React SPA with Vite, Ant Design, and TanStack Query"
```

---

### Task 19: Frontend — page implementations

**Files:**
- Create: `demo/message-center-spring/frontend/src/pages/LoginPage.tsx`
- Create: `demo/message-center-spring/frontend/src/pages/ContactsPage.tsx`
- Create: `demo/message-center-spring/frontend/src/pages/ThreadPage.tsx`
- Create: `demo/message-center-spring/frontend/src/pages/SendPage.tsx`
- Create: `demo/message-center-spring/frontend/src/pages/TemplatesPage.tsx`
- Create: `demo/message-center-spring/frontend/src/components/AppLayout.tsx`
- Create: `demo/message-center-spring/frontend/src/components/ContactCard.tsx`
- Create: `demo/message-center-spring/frontend/src/components/MessageBubble.tsx`
- Create: `demo/message-center-spring/frontend/src/components/SendForm.tsx`
- Create: `demo/message-center-spring/frontend/src/components/SseProvider.tsx`
- Create: `demo/message-center-spring/frontend/src/hooks/useContacts.ts`
- Create: `demo/message-center-spring/frontend/src/hooks/useMessages.ts`
- Create: `demo/message-center-spring/frontend/src/hooks/useSse.ts`

Implementation guide: Build each page/component to match the old embedded page UI. Use Ant Design components (Card, List, Input, Button, Select, Modal, message, notification). TanStack Query for data fetching with cache invalidation on SSE events. Each page implements the identical interaction pattern from the old page.

- [ ] **Step 1: Write SseProvider.tsx** — context wrapping EventSource for `/api/events`

- [ ] **Step 2: Write useContacts.ts** — TanStack Query hook wrapping `GET /api/contacts` with search/merge/split mutations

- [ ] **Step 3: Write ContactsPage.tsx** — contact list with search, channel filters, merge/split modals

- [ ] **Step 4: Write useMessages.ts** — cursor-based infinite query for thread messages

- [ ] **Step 5: Write ThreadPage.tsx** — message timeline with cursor-based infinite scroll, send form at bottom

- [ ] **Step 6: Write SendPage.tsx** — multi-channel send form (email/ChatApp) with template selector

- [ ] **Step 7: Write TemplatesPage.tsx** — template list with search

- [ ] **Step 8: Write LoginPage.tsx** — username/password form, redirects to / on success

- [ ] **Step 9: Commit**

```bash
git add demo/message-center-spring/frontend/src/
git commit -m "feat: implement React pages matching original embedded UI"
```

---

### Task 20: End-to-end verification

- [ ] **Step 1: Start infrastructure**

```bash
cd demo/message-center-spring/backend && docker compose up -d
```

- [ ] **Step 2: Bootstrap admin user**

```bash
cd demo/message-center-spring/backend && mvn spring-boot:run -Dspring-boot.run.arguments="bootstrap-admin"
```

- [ ] **Step 3: Start backend**

```bash
cd demo/message-center-spring/backend && mvn spring-boot:run
```

Expected: Tomcat starts on port 8099, Flyway migrates, health check returns 200.

- [ ] **Step 4: Start frontend**

```bash
cd demo/message-center-spring/frontend && npm run dev
```

- [ ] **Step 5: Manual test flow**

1. Open http://localhost:5173
2. Login with admin credentials
3. See contacts page (empty)
4. Verify SSE connection is established
5. Verify error states display correctly

- [ ] **Step 6: Commit any fixes**

```bash
git commit -m "fix: end-to-end verification fixes"
```
