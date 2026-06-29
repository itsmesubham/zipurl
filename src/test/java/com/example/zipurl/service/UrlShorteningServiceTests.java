package com.example.zipurl.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.example.zipurl.config.ZipurlProperties;
import com.example.zipurl.dto.CreateShortUrlRequest;
import com.example.zipurl.exception.AliasAlreadyExistsException;
import com.example.zipurl.exception.ShortUrlNotFoundException;
import com.example.zipurl.model.ShortUrl;
import com.example.zipurl.repository.ShortUrlRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@SpringBootTest(properties = {
        "zipurl.access-count.mode=sync"
})
class UrlShorteningServiceTests {

    @Autowired
    private UrlShorteningService urlShorteningService;

    @Autowired
    private ShortUrlRepository shortUrlRepository;

    @Autowired
    private UrlCacheService urlCacheService;

    @Autowired
    private TestAliasGenerator aliasGenerator;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        shortUrlRepository.deleteAll();
        aliasGenerator.clear();
    }

    @Test
    void retriesWhenGeneratedAliasCollidesWithExistingRow() {
        shortUrlRepository.saveAndFlush(new ShortUrl("taken001", "https://example.com/existing"));
        aliasGenerator.useAliases("taken001", "fresh001");

        ShortUrl shortUrl = urlShorteningService.createShortUrl(
                new CreateShortUrlRequest("https://example.com/new", null, null)
        );

        assertThat(shortUrl.getAlias()).isEqualTo("fresh001");
        assertThat(shortUrlRepository.count()).isEqualTo(2);
    }

    @Test
    void onlyOneConcurrentRequestCanClaimACustomAlias() throws Exception {
        int requestCount = 6;
        CountDownLatch startLatch = new CountDownLatch(1);
        ExecutorService executorService = Executors.newFixedThreadPool(requestCount);

        try {
            List<Future<Object>> futures = java.util.stream.IntStream.range(0, requestCount)
                    .mapToObj(index -> (Callable<Object>) () -> {
                        startLatch.await();
                        try {
                            return urlShorteningService.createShortUrl(
                                    new CreateShortUrlRequest("https://example.com/" + index, "shared01", null)
                            );
                        } catch (AliasAlreadyExistsException exception) {
                            return exception;
                        }
                    })
                    .map(executorService::submit)
                    .toList();

            startLatch.countDown();

            List<Object> results = futures.stream()
                    .map(this::getResult)
                    .toList();

            assertThat(results).filteredOn(ShortUrl.class::isInstance).hasSize(1);
            assertThat(results).filteredOn(AliasAlreadyExistsException.class::isInstance).hasSize(requestCount - 1);
            assertThat(shortUrlRepository.count()).isEqualTo(1);
        } finally {
            executorService.shutdownNow();
        }
    }

    @Test
    void concurrentResolvesReturnOriginalUrlAndTrackEveryAccess() {
        int requestCount = 8;
        shortUrlRepository.saveAndFlush(new ShortUrl("hot001", "https://example.com/hot"));
        CountDownLatch startLatch = new CountDownLatch(1);
        ExecutorService executorService = Executors.newFixedThreadPool(requestCount);

        try {
            List<Future<Object>> futures = java.util.stream.IntStream.range(0, requestCount)
                    .mapToObj(index -> (Callable<Object>) () -> {
                        startLatch.await();
                        return urlShorteningService.resolveOriginalUrl("hot001");
                    })
                    .map(executorService::submit)
                    .toList();

            startLatch.countDown();

            List<Object> results = futures.stream()
                    .map(this::getResult)
                    .toList();

            assertThat(results).containsOnly("https://example.com/hot");
            assertThat(shortUrlRepository.findByAlias("hot001"))
                    .hasValueSatisfying(shortUrl -> assertThat(shortUrl.getAccessCount()).isEqualTo(requestCount));
        } finally {
            executorService.shutdownNow();
        }
    }

    @Test
    void createWithTtlStoresExpiry() {
        Instant before = Instant.now();

        ShortUrl shortUrl = urlShorteningService.createShortUrl(
                new CreateShortUrlRequest("https://example.com/ttl", "ttl-link", 3600L)
        );

        assertThat(shortUrl.getExpiresAt())
                .isNotNull()
                .isAfterOrEqualTo(before.plusSeconds(3600).truncatedTo(ChronoUnit.MILLIS).minusSeconds(2));
    }

    @Test
    void resolveTreatsExpiredLinkAsNotFound() {
        shortUrlRepository.saveAndFlush(
                new ShortUrl("expired1", "https://example.com/old", Instant.now().minusSeconds(60))
        );

        assertThatThrownBy(() -> urlShorteningService.resolveOriginalUrl("expired1"))
                .isInstanceOf(ShortUrlNotFoundException.class);
    }

    @Test
    void metadataTreatsExpiredLinkAsNotFound() {
        shortUrlRepository.saveAndFlush(
                new ShortUrl("expired2", "https://example.com/old", Instant.now().minusSeconds(60))
        );

        assertThatThrownBy(() -> urlShorteningService.getShortUrl("expired2"))
                .isInstanceOf(ShortUrlNotFoundException.class);
    }

    @Test
    void resolveReturnsUrlForUnexpiredLink() {
        shortUrlRepository.saveAndFlush(
                new ShortUrl("active1", "https://example.com/active", Instant.now().plusSeconds(3600))
        );

        assertThat(urlShorteningService.resolveOriginalUrl("active1"))
                .isEqualTo("https://example.com/active");
    }

    @Test
    void resolveTreatsCachedButExpiredLinkAsNotFound() {
        shortUrlRepository.saveAndFlush(
                new ShortUrl("cached1", "https://example.com/cached", Instant.now().minusSeconds(60))
        );
        // Simulate an entry that was cached while still valid, then expired.
        urlCacheService.putResolvedUrl("cached1", new com.example.zipurl.service.CachedRedirectTarget(
                "https://example.com/cached",
                Instant.now().plusSeconds(60)
        ));

        assertThatThrownBy(() -> urlShorteningService.resolveOriginalUrl("cached1"))
                .isInstanceOf(ShortUrlNotFoundException.class);
    }

    @Test
    void createConcurrencyLimiterStillAllowsSuccessfulCreate() {
        ZipurlProperties zipurlProperties = new ZipurlProperties();
        zipurlProperties.setCreateMaxConcurrent(1);
        UrlShorteningService limitedService = new UrlShorteningService(
                aliasGenerator,
                org.mockito.Mockito.mock(com.example.zipurl.service.AccessCountService.class),
                jdbcTemplate,
                shortUrlRepository,
                transactionManager,
                urlCacheService,
                zipurlProperties,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry()
        );

        aliasGenerator.useAliases("limit01");

        ShortUrl shortUrl = limitedService.createShortUrl(
                new CreateShortUrlRequest("https://example.com/limit", null, null)
        );

        assertThat(shortUrl.getAlias()).isEqualTo("limit01");
    }

    private Object getResult(Future<Object> future) {
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new AssertionError("Concurrent create task failed", exception);
        }
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        @Primary
        TestAliasGenerator testAliasGenerator() {
            return new TestAliasGenerator();
        }
    }

    static class TestAliasGenerator extends AliasGenerator {

        private final Queue<String> aliases = new ConcurrentLinkedQueue<>();

        void useAliases(String... aliases) {
            this.aliases.addAll(List.of(aliases));
        }

        void clear() {
            aliases.clear();
        }

        @Override
        public String generate(int length) {
            String alias = aliases.poll();
            if (alias == null) {
                throw new AssertionError("No test alias configured");
            }

            return alias;
        }
    }
}
