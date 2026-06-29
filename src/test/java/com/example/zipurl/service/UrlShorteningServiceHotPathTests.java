package com.example.zipurl.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import com.example.zipurl.config.ZipurlProperties;
import com.example.zipurl.repository.ShortUrlRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class UrlShorteningServiceHotPathTests {

    @Test
    void resolveOriginalUrlFromLocalCacheDoesNotTouchPersistenceOrTransactions() {
        AliasGenerator aliasGenerator = mock(AliasGenerator.class);
        AccessCountService accessCountService = mock(AccessCountService.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ShortUrlRepository shortUrlRepository = mock(ShortUrlRepository.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        ZipurlProperties zipurlProperties = new ZipurlProperties();
        LocalUrlCacheService urlCacheService = new LocalUrlCacheService(zipurlProperties, new SimpleMeterRegistry());
        UrlShorteningService urlShorteningService = new UrlShorteningService(
                aliasGenerator,
                accessCountService,
                jdbcTemplate,
                shortUrlRepository,
                transactionManager,
                urlCacheService,
                zipurlProperties,
                new SimpleMeterRegistry()
        );

        urlCacheService.putResolvedUrl(
                "hot001",
                new CachedRedirectTarget("https://example.com/hot", Instant.now().plusSeconds(60))
        );

        String originalUrl = urlShorteningService.resolveOriginalUrl("hot001");

        assertThat(originalUrl).isEqualTo("https://example.com/hot");
        verify(accessCountService).recordAccess("hot001");
        verifyNoInteractions(jdbcTemplate);
        verifyNoInteractions(shortUrlRepository, transactionManager);
        verifyNoInteractions(aliasGenerator);
    }

    @Test
    void repeatedMissingAliasHitsNegativeCacheInsteadOfDatabase() {
        AliasGenerator aliasGenerator = mock(AliasGenerator.class);
        AccessCountService accessCountService = mock(AccessCountService.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ShortUrlRepository shortUrlRepository = mock(ShortUrlRepository.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        ZipurlProperties zipurlProperties = new ZipurlProperties();
        LocalUrlCacheService urlCacheService = new LocalUrlCacheService(zipurlProperties, new SimpleMeterRegistry());
        UrlShorteningService urlShorteningService = new UrlShorteningService(
                aliasGenerator,
                accessCountService,
                jdbcTemplate,
                shortUrlRepository,
                transactionManager,
                urlCacheService,
                zipurlProperties,
                new SimpleMeterRegistry()
        );

        when(jdbcTemplate.queryForObject(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<CachedRedirectTarget>>any(),
                org.mockito.ArgumentMatchers.eq("missing1")
        )).thenReturn(null);

        assertThatThrownBy(() -> urlShorteningService.resolveOriginalUrl("missing1"))
                .isInstanceOf(com.example.zipurl.exception.ShortUrlNotFoundException.class);
        assertThatThrownBy(() -> urlShorteningService.resolveOriginalUrl("missing1"))
                .isInstanceOf(com.example.zipurl.exception.ShortUrlNotFoundException.class);

        verify(jdbcTemplate).queryForObject(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<CachedRedirectTarget>>any(),
                org.mockito.ArgumentMatchers.eq("missing1")
        );
        verifyNoInteractions(accessCountService, aliasGenerator, shortUrlRepository, transactionManager);
    }

    @Test
    void expiredCachedAliasReturns404AndInvalidatesCache() {
        AliasGenerator aliasGenerator = mock(AliasGenerator.class);
        AccessCountService accessCountService = mock(AccessCountService.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ShortUrlRepository shortUrlRepository = mock(ShortUrlRepository.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        ZipurlProperties zipurlProperties = new ZipurlProperties();
        UrlCacheService urlCacheService = mock(UrlCacheService.class);
        UrlShorteningService urlShorteningService = new UrlShorteningService(
                aliasGenerator,
                accessCountService,
                jdbcTemplate,
                shortUrlRepository,
                transactionManager,
                urlCacheService,
                zipurlProperties,
                new SimpleMeterRegistry()
        );

        when(urlCacheService.getResolvedUrl(
                org.mockito.ArgumentMatchers.eq("expired1"),
                org.mockito.ArgumentMatchers.<java.util.function.Function<String, CachedRedirectTarget>>any()
        )).thenReturn(new CachedRedirectTarget("https://example.com/expired", Instant.now().minusSeconds(1)));

        assertThatThrownBy(() -> urlShorteningService.resolveOriginalUrl("expired1"))
                .isInstanceOf(com.example.zipurl.exception.ShortUrlNotFoundException.class);

        verify(urlCacheService).invalidate("expired1");
        verifyNoInteractions(jdbcTemplate, shortUrlRepository, transactionManager, aliasGenerator, accessCountService);
    }
}
