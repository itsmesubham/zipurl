package com.example.zipurl.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import com.example.zipurl.config.ZipurlProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "zipurl.cache.warmup", name = "enabled", havingValue = "true")
public class RedirectCacheWarmupService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RedirectCacheWarmupService.class);

    private static final String WARMUP_SQL = """
            SELECT alias, original_url, expires_at
            FROM short_urls
            WHERE expires_at IS NULL OR expires_at > now()
            ORDER BY created_at DESC
            LIMIT ?
            """;

    private final JdbcTemplate jdbcTemplate;
    private final UrlCacheService urlCacheService;
    private final ZipurlProperties zipurlProperties;

    public RedirectCacheWarmupService(
            JdbcTemplate jdbcTemplate,
            UrlCacheService urlCacheService,
            ZipurlProperties zipurlProperties
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.urlCacheService = urlCacheService;
        this.zipurlProperties = zipurlProperties;
    }

    @Override
    public void run(ApplicationArguments args) {
        int limit = Math.toIntExact(zipurlProperties.getCacheWarmupLimit());
        if (limit <= 0) {
            return;
        }

        AtomicInteger warmedCount = new AtomicInteger();
        jdbcTemplate.query(
                WARMUP_SQL,
                preparedStatement -> preparedStatement.setInt(1, limit),
                resultSet -> {
                    warmupRow(resultSet);
                    warmedCount.incrementAndGet();
                }
        );
        log.info("Warmed {} redirect cache entries during startup with limit {}", warmedCount.get(), limit);
    }

    private void warmupRow(ResultSet resultSet) throws SQLException {
        urlCacheService.putResolvedUrl(
                resultSet.getString("alias"),
                new CachedRedirectTarget(
                        resultSet.getString("original_url"),
                        resultSet.getTimestamp("expires_at") == null ? null : resultSet.getTimestamp("expires_at").toInstant()
                )
        );
    }
}
