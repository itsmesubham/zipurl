package com.example.zipurl.service;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Function;

import com.example.zipurl.config.ZipurlProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "zipurl.url-cache", name = "mode", havingValue = "valkey")
public class ValkeyUrlCacheService implements UrlCacheService {

    private static final String URL_CACHE_KEY_PREFIX = "zipurl:url:";

    private final Cache<String, CachedRedirectTarget> localCache;
    private final StringRedisTemplate redisTemplate;
    private final ZipurlProperties zipurlProperties;
    private final ObjectMapper objectMapper;
    private final Counter cacheHitCounter;
    private final Counter cacheMissCounter;

    public ValkeyUrlCacheService(
            StringRedisTemplate redisTemplate,
            ZipurlProperties zipurlProperties,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry
    ) {
        this.redisTemplate = redisTemplate;
        this.zipurlProperties = zipurlProperties;
        this.objectMapper = objectMapper;
        this.localCache = Caffeine.newBuilder()
                .maximumSize(zipurlProperties.getCacheMaxSize())
                .expireAfterWrite(java.time.Duration.ofSeconds(zipurlProperties.getCacheExpireAfterWriteSeconds()))
                .build();
        this.cacheHitCounter = Counter.builder("zipurl.cache.hit")
                .description("Cache hits for redirect lookups")
                .register(meterRegistry);
        this.cacheMissCounter = Counter.builder("zipurl.cache.miss")
                .description("Cache misses for redirect lookups")
                .register(meterRegistry);
    }

    @Override
    public CachedRedirectTarget getResolvedUrl(String alias, Function<String, CachedRedirectTarget> loader) {
        CachedRedirectTarget cached = localCache.getIfPresent(alias);
        if (cached != null) {
            if (cached.isExpired(Instant.now())) {
                invalidate(alias);
            } else {
                cacheHitCounter.increment();
                return cached;
            }
        }

        if (zipurlProperties.isValkeyEnabled()) {
            try {
                String cachedValue = redisTemplate.opsForValue().get(cacheKey(alias));
                if (cachedValue != null) {
                    CachedRedirectTarget resolvedUrl = objectMapper.readValue(cachedValue, CachedRedirectTarget.class);
                    if (resolvedUrl.isExpired(Instant.now())) {
                        invalidate(alias);
                    } else {
                        localCache.put(alias, resolvedUrl);
                        cacheHitCounter.increment();
                        return resolvedUrl;
                    }
                }
            } catch (DataAccessException exception) {
                // Shared cache is best-effort; fall through to the authoritative DB lookup.
            } catch (JsonProcessingException exception) {
                // Invalid cache payload falls through to the authoritative DB lookup.
            }
        }

        cacheMissCounter.increment();
        CachedRedirectTarget resolvedUrl = loader.apply(alias);
        localCache.put(alias, resolvedUrl);
        writeToValkey(alias, resolvedUrl);
        return resolvedUrl;
    }

    @Override
    public void putResolvedUrl(String alias, CachedRedirectTarget resolvedUrl) {
        localCache.put(alias, resolvedUrl);
        writeToValkey(alias, resolvedUrl);
    }

    @Override
    public void invalidate(String alias) {
        localCache.invalidate(alias);
        if (!zipurlProperties.isValkeyEnabled()) {
            return;
        }
        try {
            redisTemplate.delete(cacheKey(alias));
        } catch (DataAccessException exception) {
            // Local invalidation is enough for this instance; stale shared cache expires by TTL.
        }
    }

    private void writeToValkey(String alias, CachedRedirectTarget resolvedUrl) {
        if (!zipurlProperties.isValkeyEnabled()) {
            return;
        }

        Duration ttl = resolveValkeyTtl(resolvedUrl);
        if (ttl.isZero() || ttl.isNegative()) {
            invalidate(alias);
            return;
        }

        try {
            redisTemplate.opsForValue().set(
                    cacheKey(alias),
                    objectMapper.writeValueAsString(resolvedUrl),
                    ttl
            );
        } catch (DataAccessException exception) {
            // Cache fill is best-effort; Postgres remains the source of truth.
        } catch (JsonProcessingException exception) {
            // Serialization failures should not break reads or writes.
        }
    }

    private String cacheKey(String alias) {
        return URL_CACHE_KEY_PREFIX + alias;
    }

    private Duration resolveValkeyTtl(CachedRedirectTarget resolvedUrl) {
        Duration configuredTtl = Duration.ofSeconds(zipurlProperties.getValkeyTtlSeconds());
        Instant expiresAt = resolvedUrl.expiresAt();
        if (expiresAt == null) {
            return configuredTtl;
        }

        Duration expiresIn = Duration.between(Instant.now(), expiresAt);
        if (expiresIn.isZero() || expiresIn.isNegative()) {
            return Duration.ZERO;
        }

        return configuredTtl.compareTo(expiresIn) <= 0 ? configuredTtl : expiresIn;
    }
}
