package com.example.zipurl.service;

import com.example.zipurl.exception.ShortUrlNotFoundException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "zipurl.access-count", name = "mode", havingValue = "sync")
public class DatabaseAccessCountService implements AccessCountService {

    private static final String UPDATE_ACCESS_COUNT_SQL = """
            UPDATE short_urls
            SET access_count = access_count + :delta
            WHERE alias = :alias
              AND (expires_at IS NULL OR expires_at > now())
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public DatabaseAccessCountService(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public void recordAccess(String alias) {
        int updatedRows = jdbcTemplate.update(
                UPDATE_ACCESS_COUNT_SQL,
                new MapSqlParameterSource()
                        .addValue("alias", alias)
                        .addValue("delta", 1L)
        );
        if (updatedRows == 0) {
            throw new ShortUrlNotFoundException(alias);
        }
    }
}
