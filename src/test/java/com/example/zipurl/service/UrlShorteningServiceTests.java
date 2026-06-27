package com.example.zipurl.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.example.zipurl.dto.CreateShortUrlRequest;
import com.example.zipurl.exception.AliasAlreadyExistsException;
import com.example.zipurl.model.ShortUrl;
import com.example.zipurl.repository.ShortUrlRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@SpringBootTest
class UrlShorteningServiceTests {

    @Autowired
    private UrlShorteningService urlShorteningService;

    @Autowired
    private ShortUrlRepository shortUrlRepository;

    @Autowired
    private TestAliasGenerator aliasGenerator;

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
                new CreateShortUrlRequest("https://example.com/new", null)
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
                                    new CreateShortUrlRequest("https://example.com/" + index, "shared01")
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
