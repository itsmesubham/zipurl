package com.example.zipurl.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;

import com.example.zipurl.config.ZipurlProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.RedisConnectionFailureException;

class ValkeyUrlCacheServiceTests {

    private final ZipurlProperties zipurlProperties = new ZipurlProperties();

    @Test
    void getOriginalUrlFallsBackToLoaderWhenValkeyReadFails() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("zipurl:url:abc123"))
                .thenThrow(new RedisConnectionFailureException("Valkey unavailable"));

        ValkeyUrlCacheService cacheService = new ValkeyUrlCacheService(redisTemplate, zipurlProperties, new ObjectMapper());

        CachedResolvedUrl originalUrl = cacheService.getResolvedUrl(
                "abc123",
                alias -> new CachedResolvedUrl("https://example.com/fallback", null)
        );

        assertThat(originalUrl.originalUrl()).isEqualTo("https://example.com/fallback");
    }

    @Test
    void putResolvedUrlDoesNotFailWhenValkeyWriteFails() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        org.mockito.Mockito.doThrow(new RedisConnectionFailureException("Valkey unavailable"))
                .when(valueOperations)
                .set(eq("zipurl:url:abc123"), any(String.class), any(Duration.class));

        ValkeyUrlCacheService cacheService = new ValkeyUrlCacheService(redisTemplate, zipurlProperties, new ObjectMapper());

        assertThatCode(() -> cacheService.putResolvedUrl(
                "abc123",
                new CachedResolvedUrl("https://example.com", null)
        ))
                .doesNotThrowAnyException();
    }

    @Test
    void invalidateDoesNotFailWhenValkeyDeleteFails() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.delete("zipurl:url:abc123"))
                .thenThrow(new RedisConnectionFailureException("Valkey unavailable"));

        ValkeyUrlCacheService cacheService = new ValkeyUrlCacheService(redisTemplate, zipurlProperties, new ObjectMapper());

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

        ValkeyUrlCacheService cacheService = new ValkeyUrlCacheService(redisTemplate, zipurlProperties, new ObjectMapper());

        assertThat(cacheService.getResolvedUrl(
                "abc123",
                alias -> new CachedResolvedUrl("https://example.com/fallback", null)
        ).originalUrl()).isEqualTo("https://example.com/cached");

        clearInvocations(redisTemplate, valueOperations);

        assertThat(cacheService.getResolvedUrl(
                "abc123",
                alias -> new CachedResolvedUrl("https://example.com/fallback", null)
        ).originalUrl()).isEqualTo("https://example.com/cached");

        org.mockito.Mockito.verifyNoInteractions(redisTemplate, valueOperations);
    }
}
