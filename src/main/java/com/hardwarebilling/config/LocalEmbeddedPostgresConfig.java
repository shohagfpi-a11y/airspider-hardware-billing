package com.hardwarebilling.config;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Path;

/**
 * "local" profile only: starts a real PostgreSQL server from bundled binaries so the app can be
 * tried on a PC with nothing installed. Data persists in ./data/pg between runs.
 * Production uses the PostgreSQL container from docker-compose instead.
 */
@Configuration
@Profile("local")
@ConditionalOnClass(EmbeddedPostgres.class)
public class LocalEmbeddedPostgresConfig {

    @Bean(destroyMethod = "close")
    EmbeddedPostgres embeddedPostgres(AppProperties props) throws IOException {
        var local = props.local();
        int port = local != null && local.pgPort() != null ? local.pgPort() : 54329;
        String dir = local != null && local.pgDataDir() != null ? local.pgDataDir() : "./data/pg";
        return EmbeddedPostgres.builder()
                .setPort(port)
                .setDataDirectory(Path.of(dir).toAbsolutePath())
                .setCleanDataDirectory(false)
                .start();
    }

    @Bean
    @Primary
    DataSource dataSource(EmbeddedPostgres pg) {
        return DataSourceBuilder.create()
                .url(pg.getJdbcUrl("postgres", "postgres"))
                .username("postgres")
                .password("postgres")
                .build();
    }
}
