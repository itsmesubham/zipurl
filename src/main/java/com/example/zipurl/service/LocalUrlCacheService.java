package com.example.zipurl.service;

import java.time.Duration;
import java.util.function.Function;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "zipurl.url-cache", name = "mode", havingValue = "local", matchIfMissing = true)
public class LocalUrlCacheService implements UrlCacheService {

    private final Cache<String, String> cache = Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfterAccess(Duration.ofHours(1))
            .build();

    @Override
    public String getOriginalUrl(String alias, Function<String, String> loader) {
        return cache.get(alias, loader);
    }

    @Override
    public void putOriginalUrl(String alias, String originalUrl) {
        cache.put(alias, originalUrl);
    }

    @Override
    public void invalidate(String alias) {
        cache.invalidate(alias);
    }
}
