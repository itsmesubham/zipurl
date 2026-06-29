package com.example.zipurl.service;

import java.util.function.Function;

import com.example.zipurl.config.ZipurlProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "zipurl.url-cache", name = "mode", havingValue = "valkey")
public class ValkeyUrlCacheService implements UrlCacheService {

    private static final String URL_CACHE_KEY_PREFIX = "zipurl:url:";

    private final Cache<String, CachedResolvedUrl> localCache;
    private final StringRedisTemplate redisTemplate;
    private final ZipurlProperties zipurlProperties;
    private final ObjectMapper objectMapper;

    public ValkeyUrlCacheService(
            StringRedisTemplate redisTemplate,
            ZipurlProperties zipurlProperties,
            ObjectMapper objectMapper
    ) {
        this.redisTemplate = redisTemplate;
        this.zipurlProperties = zipurlProperties;
        this.objectMapper = objectMapper;
        this.localCache = Caffeine.newBuilder()
                .maximumSize(zipurlProperties.getCacheMaxSize())
                .expireAfterWrite(java.time.Duration.ofSeconds(zipurlProperties.getCacheExpireAfterWriteSeconds()))
                .build();
    }

    @Override
    public CachedResolvedUrl getResolvedUrl(String alias, Function<String, CachedResolvedUrl> loader) {
        return localCache.get(alias, cacheKey -> loadFromValkey(alias, loader));
    }

    @Override
    public void putResolvedUrl(String alias, CachedResolvedUrl resolvedUrl) {
        localCache.put(alias, resolvedUrl);
        try {
            redisTemplate.opsForValue().set(
                    cacheKey(alias),
                    objectMapper.writeValueAsString(resolvedUrl),
                    zipurlProperties.getSharedCacheTtl()
            );
        } catch (DataAccessException exception) {
            // Cache warming is best-effort; Postgres remains the source of truth.
        } catch (JsonProcessingException exception) {
            // Serialization failures should not break writes.
        }
    }

    @Override
    public void invalidate(String alias) {
        localCache.invalidate(alias);
        try {
            redisTemplate.delete(cacheKey(alias));
        } catch (DataAccessException exception) {
            // Local invalidation is enough for this instance; stale shared cache expires by TTL.
        }
    }

    private CachedResolvedUrl loadFromValkey(String alias, Function<String, CachedResolvedUrl> loader) {
        try {
            String cachedValue = redisTemplate.opsForValue().get(cacheKey(alias));
            if (cachedValue != null) {
                return objectMapper.readValue(cachedValue, CachedResolvedUrl.class);
            }
        } catch (DataAccessException exception) {
            return loader.apply(alias);
        } catch (JsonProcessingException exception) {
            // Fall through to the authoritative Postgres lookup.
        }

        CachedResolvedUrl resolvedUrl = loader.apply(alias);
        try {
            redisTemplate.opsForValue().set(
                    cacheKey(alias),
                    objectMapper.writeValueAsString(resolvedUrl),
                    zipurlProperties.getSharedCacheTtl()
            );
        } catch (DataAccessException exception) {
            // Cache fill is best-effort; return the DB-loaded value.
        } catch (JsonProcessingException exception) {
            // Serialization failures are best-effort only.
        }

        return resolvedUrl;
    }

    private String cacheKey(String alias) {
        return URL_CACHE_KEY_PREFIX + alias;
    }
}
