package com.example.zipurl.service;

import java.util.function.Function;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.example.zipurl.config.ZipurlProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "zipurl.url-cache", name = "mode", havingValue = "valkey")
public class ValkeyUrlCacheService implements UrlCacheService {

    private static final String URL_CACHE_KEY_PREFIX = "zipurl:url:";

    private final Cache<String, String> localCache;
    private final StringRedisTemplate redisTemplate;
    private final ZipurlProperties zipurlProperties;

    public ValkeyUrlCacheService(
            StringRedisTemplate redisTemplate,
            ZipurlProperties zipurlProperties
    ) {
        this.redisTemplate = redisTemplate;
        this.zipurlProperties = zipurlProperties;
        this.localCache = Caffeine.newBuilder()
                .maximumSize(zipurlProperties.getLocalCacheMaxSize())
                .expireAfterAccess(zipurlProperties.getLocalCacheExpireAfterAccess())
                .build();
    }

    @Override
    public String getOriginalUrl(String alias, Function<String, String> loader) {
        return localCache.get(alias, cacheKey -> loadFromValkey(alias, loader));
    }

    @Override
    public void putOriginalUrl(String alias, String originalUrl) {
        localCache.put(alias, originalUrl);
        try {
            redisTemplate.opsForValue().set(cacheKey(alias), originalUrl, zipurlProperties.getSharedCacheTtl());
        } catch (DataAccessException exception) {
            // Cache warming is best-effort; Postgres remains the source of truth.
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

    private String loadFromValkey(String alias, Function<String, String> loader) {
        try {
            String cachedOriginalUrl = redisTemplate.opsForValue().get(cacheKey(alias));
            if (cachedOriginalUrl != null) {
                return cachedOriginalUrl;
            }
        } catch (DataAccessException exception) {
            return loader.apply(alias);
        }

        String originalUrl = loader.apply(alias);
        try {
            redisTemplate.opsForValue().set(cacheKey(alias), originalUrl, zipurlProperties.getSharedCacheTtl());
        } catch (DataAccessException exception) {
            // Cache fill is best-effort; return the DB-loaded value.
        }

        return originalUrl;
    }

    private String cacheKey(String alias) {
        return URL_CACHE_KEY_PREFIX + alias;
    }
}
