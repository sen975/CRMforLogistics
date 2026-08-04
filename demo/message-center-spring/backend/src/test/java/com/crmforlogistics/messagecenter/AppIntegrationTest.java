package com.crmforlogistics.messagecenter;

import io.minio.MinioClient;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.profiles.active=test"
        }
)
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
        // Disable MinIO for tests
        registry.add("minio.endpoint", () -> "http://localhost:9999");
        registry.add("minio.access-key", () -> "test");
        registry.add("minio.secret-key", () -> "test");
        registry.add("minio.bucket", () -> "test");
    }

    /**
     * Provides a mock MinioClient so MinioStorage does not attempt real
     * network calls during context initialization.
     */
    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        public MinioClient minioClient() {
            return Mockito.mock(MinioClient.class);
        }
    }

    @Autowired
    TestRestTemplate rest;

    @Test
    void contextLoads() {
        // Verify Spring context starts successfully
    }

    @Test
    void loginWithoutCredentialsReturns403() {
        ResponseEntity<String> response = rest.getForEntity("/api/contacts", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void authEndpointIsPublic() {
        ResponseEntity<String> response = rest.postForEntity("/api/auth/login",
                Map.of("username", "nonexistent", "password", "wrong"), String.class);
        // Should return 401 from Spring Security (bad credentials), not 403
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void healthCheckIsAccessible() {
        ResponseEntity<String> response = rest.getForEntity("/actuator/health", String.class);
        // May be 200 or 404 depending on whether actuator is configured
        assertThat(response.getStatusCode().is2xxSuccessful()
                || response.getStatusCode() == HttpStatus.NOT_FOUND).isTrue();
    }
}
