package com.example.zipurl.config;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.profiles.active=postgres",
                "spring.datasource.url=jdbc:h2:mem:zipurl-postgres-profile;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.jpa.hibernate.ddl-auto=none",
                "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
                "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration",
                "ZIPURL_DB_MAX_POOL=4",
                "ZIPURL_DB_MIN_IDLE=1",
                "ZIPURL_DB_CONNECTION_TIMEOUT=2345",
                "ZIPURL_DB_IDLE_TIMEOUT=30000",
                "ZIPURL_DB_MAX_LIFETIME=600000",
                "ZIPURL_DB_KEEPALIVE=120000",
                "ZIPURL_DB_INIT_FAIL_TIMEOUT=555",
                "ZIPURL_URL_CACHE_MODE=local"
        }
)
class PostgresProfileHikariPropertiesTests {

    @Autowired
    private DataSource dataSource;

    @Test
    void postgresProfileBindsConservativeHikariSettings() {
        assertThat(dataSource).isInstanceOf(HikariDataSource.class);

        HikariDataSource hikariDataSource = (HikariDataSource) dataSource;
        assertThat(hikariDataSource.getMaximumPoolSize()).isEqualTo(4);
        assertThat(hikariDataSource.getMinimumIdle()).isEqualTo(1);
        assertThat(hikariDataSource.getConnectionTimeout()).isEqualTo(2345L);
        assertThat(hikariDataSource.getIdleTimeout()).isEqualTo(30000L);
        assertThat(hikariDataSource.getMaxLifetime()).isEqualTo(600000L);
        assertThat(hikariDataSource.getKeepaliveTime()).isEqualTo(120000L);
        assertThat(hikariDataSource.getInitializationFailTimeout()).isEqualTo(555L);
    }
}
