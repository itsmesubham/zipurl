package com.example.zipurl.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;

import com.example.zipurl.config.ZipurlProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.PreparedStatementSetter;

class RedirectCacheWarmupServiceTests {

    @Test
    void warmupLoadsLimitedActiveAliasesIntoCache() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        UrlCacheService urlCacheService = mock(UrlCacheService.class);
        ZipurlProperties zipurlProperties = new ZipurlProperties();
        zipurlProperties.setCacheWarmupEnabled(true);
        zipurlProperties.setCacheWarmupLimit(2);
        RedirectCacheWarmupService warmupService = new RedirectCacheWarmupService(jdbcTemplate, urlCacheService, zipurlProperties);

        doAnswer(invocation -> {
            RowCallbackHandler rowCallbackHandler = invocation.getArgument(2);
            ResultSet resultSet = mock(ResultSet.class);
            when(resultSet.getString("alias")).thenReturn("warm001");
            when(resultSet.getString("original_url")).thenReturn("https://example.com/warm");
            when(resultSet.getTimestamp("expires_at")).thenReturn(null);
            rowCallbackHandler.processRow(resultSet);
            return null;
        }).when(jdbcTemplate).query(anyString(), any(PreparedStatementSetter.class), any(RowCallbackHandler.class));

        assertThatCode(() -> warmupService.run(mock(ApplicationArguments.class))).doesNotThrowAnyException();

        verify(urlCacheService).putResolvedUrl(
                "warm001",
                new CachedRedirectTarget("https://example.com/warm", null)
        );
    }
}
