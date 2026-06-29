package com.example.zipurl.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicInteger;

import com.example.zipurl.config.ZipurlProperties;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class AsyncDatabaseAccessCountServiceTests {

    @Test
    void batchesMultipleRedirectsForTheSameAliasIntoOneDelta() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.batchUpdate(anyString(), any(SqlParameterSource[].class))).thenReturn(new int[] {1});

        AsyncDatabaseAccessCountService service = new AsyncDatabaseAccessCountService(
                jdbcTemplate,
                new ZipurlProperties(),
                new SimpleMeterRegistry()
        );
        service.recordAccess("hot001");
        service.recordAccess("hot001");
        service.recordAccess("hot001");

        int flushed = service.flushPendingCountsForTests();

        assertThat(flushed).isEqualTo(3);
        var sqlCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        var paramsCaptor = org.mockito.ArgumentCaptor.forClass(SqlParameterSource[].class);
        verify(jdbcTemplate).batchUpdate(sqlCaptor.capture(), paramsCaptor.capture());
        assertThat(sqlCaptor.getValue()).contains("UPDATE short_urls");
        assertThat(paramsCaptor.getValue()).hasSize(1);
        assertThat(((org.springframework.jdbc.core.namedparam.MapSqlParameterSource) paramsCaptor.getValue()[0]).getValue("delta"))
                .isEqualTo(3L);
    }

    @Test
    void requeuesCountsWhenFlushFails() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        AtomicInteger calls = new AtomicInteger();
        org.mockito.Mockito.doAnswer(invocation -> {
                    if (calls.getAndIncrement() == 0) {
                        throw new DataAccessResourceFailureException("db unavailable");
                    }
                    return new int[] {1};
                })
                .when(jdbcTemplate)
                .batchUpdate(anyString(), any(SqlParameterSource[].class));

        AsyncDatabaseAccessCountService service = new AsyncDatabaseAccessCountService(
                jdbcTemplate,
                new ZipurlProperties(),
                new SimpleMeterRegistry()
        );
        service.recordAccess("hot002");

        assertThat(service.flushPendingCountsForTests()).isZero();
        assertThat(service.flushPendingCountsForTests()).isEqualTo(1);
    }

    @Test
    void recordAccessDoesNotTouchJdbcTemplateBeforeFlush() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        AsyncDatabaseAccessCountService service = new AsyncDatabaseAccessCountService(
                jdbcTemplate,
                new ZipurlProperties(),
                new SimpleMeterRegistry()
        );

        service.recordAccess("hot003");

        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void flushHonorsConfiguredBatchSize() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.batchUpdate(anyString(), any(SqlParameterSource[].class))).thenReturn(new int[] {1});

        ZipurlProperties zipurlProperties = new ZipurlProperties();
        zipurlProperties.setAccessCountBatchSize(1);
        AsyncDatabaseAccessCountService service = new AsyncDatabaseAccessCountService(
                jdbcTemplate,
                zipurlProperties,
                new SimpleMeterRegistry()
        );

        service.recordAccess("hot100");
        service.recordAccess("hot200");

        assertThat(service.flushPendingCountsForTests()).isEqualTo(2);
        org.mockito.Mockito.verify(jdbcTemplate, org.mockito.Mockito.times(2))
                .batchUpdate(anyString(), any(SqlParameterSource[].class));
    }
}
