package com.example.zipurl.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import com.example.zipurl.config.ZipurlProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.RedisConnectionFailureException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class ValkeyUrlCacheServiceTests {

    private final ZipurlProperties zipurlProperties = new ZipurlProperties();

    @Test
    void getOriginalUrlFallsBackToLoaderWhenValkeyReadFails() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("zipurl:url:abc123"))
                .thenThrow(new RedisConnectionFailureException("Valkey unavailable"));

        ValkeyUrlCacheService cacheService = new ValkeyUrlCacheService(redisTemplate, zipurlProperties, new ObjectMapper(), new SimpleMeterRegistry());
        AtomicInteger loaderCalls = new AtomicInteger();

        CachedRedirectTarget originalUrl = cacheService.getResolvedUrl(
                "abc123",
                alias -> {
                    loaderCalls.incrementAndGet();
                    return new CachedRedirectTarget("https://example.com/fallback", null);
                }
        );

        assertThat(originalUrl.originalUrl()).isEqualTo("https://example.com/fallback");
        assertThat(loaderCalls.get()).isEqualTo(1);
    }

    @Test
    void redisHitPopulatesLocalCacheAndSkipsLoader() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("zipurl:url:abc123"))
                .thenReturn("{\"originalUrl\":\"https://example.com/cached\",\"expiresAt\":null}");

        ValkeyUrlCacheService cacheService = new ValkeyUrlCacheService(redisTemplate, zipurlProperties, new ObjectMapper(), new SimpleMeterRegistry());
        AtomicInteger loaderCalls = new AtomicInteger();

        CachedRedirectTarget originalUrl = cacheService.getResolvedUrl(
                "abc123",
                alias -> {
                    loaderCalls.incrementAndGet();
                    return new CachedRedirectTarget("https://example.com/fallback", null);
                }
        );

        assertThat(originalUrl.originalUrl()).isEqualTo("https://example.com/cached");
        assertThat(loaderCalls.get()).isZero();

        clearInvocations(redisTemplate, valueOperations);

        CachedRedirectTarget originalUrlFromLocalCache = cacheService.getResolvedUrl(
                "abc123",
                alias -> {
                    loaderCalls.incrementAndGet();
                    return new CachedRedirectTarget("https://example.com/fallback", null);
                }
        );

        assertThat(originalUrlFromLocalCache.originalUrl()).isEqualTo("https://example.com/cached");
        assertThat(loaderCalls.get()).isZero();
        org.mockito.Mockito.verifyNoInteractions(redisTemplate, valueOperations);
    }

    @Test
    void putResolvedUrlDoesNotFailWhenValkeyWriteFails() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        org.mockito.Mockito.doThrow(new RedisConnectionFailureException("Valkey unavailable"))
                .when(valueOperations)
                .set(eq("zipurl:url:abc123"), any(String.class), any(Duration.class));

        ValkeyUrlCacheService cacheService = new ValkeyUrlCacheService(redisTemplate, zipurlProperties, new ObjectMapper(), new SimpleMeterRegistry());

        assertThatCode(() -> cacheService.putResolvedUrl(
                "abc123",
                new CachedRedirectTarget("https://example.com", null)
        ))
                .doesNotThrowAnyException();
    }

    @Test
    void invalidateDoesNotFailWhenValkeyDeleteFails() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.delete("zipurl:url:abc123"))
                .thenThrow(new RedisConnectionFailureException("Valkey unavailable"));

        ValkeyUrlCacheService cacheService = new ValkeyUrlCacheService(redisTemplate, zipurlProperties, new ObjectMapper(), new SimpleMeterRegistry());

        assertThatCode(() -> cacheService.invalidate("abc123"))
                .doesNotThrowAnyException();
    }

    @Test
    void localCaffeineHitDoesNotCallValkeyAgain() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("zipurl:url:abc123"))
                .thenReturn("{\"originalUrl\":\"https://example.com/cached\",\"expiresAt\":null}");

        ValkeyUrlCacheService cacheService = new ValkeyUrlCacheService(redisTemplate, zipurlProperties, new ObjectMapper(), new SimpleMeterRegistry());
        AtomicInteger loaderCalls = new AtomicInteger();

        assertThat(cacheService.getResolvedUrl(
                "abc123",
                alias -> {
                    loaderCalls.incrementAndGet();
                    return new CachedRedirectTarget("https://example.com/fallback", null);
                }
        ).originalUrl()).isEqualTo("https://example.com/cached");

        clearInvocations(redisTemplate, valueOperations);

        assertThat(cacheService.getResolvedUrl(
                "abc123",
                alias -> {
                    loaderCalls.incrementAndGet();
                    return new CachedRedirectTarget("https://example.com/fallback", null);
                }
        ).originalUrl()).isEqualTo("https://example.com/cached");

        assertThat(loaderCalls).hasValue(0);
        org.mockito.Mockito.verifyNoInteractions(redisTemplate, valueOperations);
    }
}
