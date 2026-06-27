package com.example.zipurl.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.RedisConnectionFailureException;

class ValkeyUrlCacheServiceTests {

    @Test
    void getOriginalUrlFallsBackToLoaderWhenValkeyReadFails() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("zipurl:url:abc123"))
                .thenThrow(new RedisConnectionFailureException("Valkey unavailable"));

        ValkeyUrlCacheService cacheService = new ValkeyUrlCacheService(redisTemplate);

        String originalUrl = cacheService.getOriginalUrl("abc123", alias -> "https://example.com/fallback");

        assertThat(originalUrl).isEqualTo("https://example.com/fallback");
    }

    @Test
    void putOriginalUrlDoesNotFailWhenValkeyWriteFails() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        org.mockito.Mockito.doThrow(new RedisConnectionFailureException("Valkey unavailable"))
                .when(valueOperations)
                .set(eq("zipurl:url:abc123"), eq("https://example.com"), any(Duration.class));

        ValkeyUrlCacheService cacheService = new ValkeyUrlCacheService(redisTemplate);

        assertThatCode(() -> cacheService.putOriginalUrl("abc123", "https://example.com"))
                .doesNotThrowAnyException();
    }

    @Test
    void invalidateDoesNotFailWhenValkeyDeleteFails() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.delete("zipurl:url:abc123"))
                .thenThrow(new RedisConnectionFailureException("Valkey unavailable"));

        ValkeyUrlCacheService cacheService = new ValkeyUrlCacheService(redisTemplate);

        assertThatCode(() -> cacheService.invalidate("abc123"))
                .doesNotThrowAnyException();
    }
}
