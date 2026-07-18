package com.crmforlogistics.messagecenter;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;

import java.sql.Connection;
import java.util.Objects;

public final class Database implements AutoCloseable {
    @FunctionalInterface
    public interface SqlFunction<T> {
        T apply(Connection connection) throws Exception;
    }

    public static final class DatabaseException extends Exception {
        DatabaseException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final HikariDataSource dataSource;
    private final Flyway flyway;

    private Database(HikariDataSource dataSource) {
        this.dataSource = dataSource;
        this.flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load();
    }

    public static Database open(Config config) throws Exception {
        Objects.requireNonNull(config, "config");
        HikariConfig poolConfig = new HikariConfig();
        poolConfig.setPoolName("message-center-database");
        poolConfig.setJdbcUrl(config.databaseUrl());
        poolConfig.setUsername(config.databaseUser());
        poolConfig.setPassword(config.readSecret(config.databasePasswordFile()));
        poolConfig.setMaximumPoolSize(10);
        poolConfig.setMinimumIdle(0);
        poolConfig.setConnectionTimeout(10_000L);
        poolConfig.setValidationTimeout(5_000L);
        poolConfig.setInitializationFailTimeout(10_000L);
        try {
            return new Database(new HikariDataSource(poolConfig));
        } catch (RuntimeException exception) {
            throw new DatabaseException("Unable to open database connection pool", exception);
        }
    }

    public void migrate() throws DatabaseException {
        try {
            flyway.migrate();
        } catch (RuntimeException exception) {
            throw new DatabaseException("Database migration failed", exception);
        }
    }

    public <T> T read(SqlFunction<T> work) throws Exception {
        Objects.requireNonNull(work, "work");
        try (Connection connection = dataSource.getConnection()) {
            return work.apply(connection);
        } catch (DatabaseException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new DatabaseException("Database read failed", exception);
        }
    }

    public <T> T transaction(SqlFunction<T> work) throws Exception {
        Objects.requireNonNull(work, "work");
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                T result = work.apply(connection);
                connection.commit();
                return result;
            } catch (Exception exception) {
                try {
                    connection.rollback();
                } catch (Exception rollbackFailure) {
                    exception.addSuppressed(rollbackFailure);
                }
                throw new DatabaseException("Database transaction failed", exception);
            }
        } catch (DatabaseException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new DatabaseException("Unable to execute database transaction", exception);
        }
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
