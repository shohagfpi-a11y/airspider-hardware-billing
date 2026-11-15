package com.hardwarebilling;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import javax.sql.DataSource;
import java.io.IOException;

/** Real, throw-away PostgreSQL for integration tests (no Docker, no install). Flyway runs all migrations on it. */
@TestConfiguration(proxyBeanMethods = false)
public class TestDatabaseConfig {

    @Bean(destroyMethod = "close")
    EmbeddedPostgres testPostgres() throws IOException {
        return EmbeddedPostgres.start();
    }

    @Bean
    @Primary
    DataSource testDataSource(EmbeddedPostgres pg) {
        return DataSourceBuilder.create().url(pg.getJdbcUrl("postgres", "postgres")).username("postgres").password("postgres").build();
    }
}
