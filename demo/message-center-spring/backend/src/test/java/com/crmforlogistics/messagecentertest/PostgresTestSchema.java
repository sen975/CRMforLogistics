package com.crmforlogistics.messagecentertest;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;

public final class PostgresTestSchema {

    private PostgresTestSchema() {
    }

    public static DataSource dataSource(PostgreSQLContainer<?> postgres, String schema) {
        return new DriverManagerDataSource(
                jdbcUrl(postgres.getJdbcUrl(), schema),
                postgres.getUsername(),
                postgres.getPassword());
    }

    private static String jdbcUrl(String baseUrl, String schema) {
        return baseUrl + (baseUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema;
    }

    /**
     * V1 installs pg_trgm via CREATE EXTENSION IF NOT EXISTS, and PostgreSQL keeps one copy of an
     * extension per database rather than per schema. Whichever schema migrates first therefore owns
     * the extension, and every later schema fails V3 with `operator class "gin_trgm_ops" does not
     * exist`. Dropping it lets the next schema install its own copy.
     */
    public static void resetTrigramExtension(DataSource dataSource) {
        new JdbcTemplate(dataSource).execute("drop extension if exists pg_trgm cascade");
    }
}
