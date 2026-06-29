package com.example.zipurl.service;

import java.util.function.Function;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.example.zipurl.config.ZipurlProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "zipurl.url-cache", name = "mode", havingValue = "local", matchIfMissing = true)
public class LocalUrlCacheService implements UrlCacheService {

    private final Cache<String, CachedResolvedUrl> cache;

    public LocalUrlCacheService(ZipurlProperties zipurlProperties) {
        this.cache = Caffeine.newBuilder()
                .maximumSize(zipurlProperties.getCacheMaxSize())
                .expireAfterWrite(java.time.Duration.ofSeconds(zipurlProperties.getCacheExpireAfterWriteSeconds()))
                .build();
    }

    @Override
    public CachedResolvedUrl getResolvedUrl(String alias, Function<String, CachedResolvedUrl> loader) {
        return cache.get(alias, loader);
    }

    @Override
    public void putResolvedUrl(String alias, CachedResolvedUrl resolvedUrl) {
        cache.put(alias, resolvedUrl);
    }

    @Override
    public void invalidate(String alias) {
        cache.invalidate(alias);
    }
}
