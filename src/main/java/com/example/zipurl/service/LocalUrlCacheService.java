package com.example.zipurl.service;

import java.util.function.Function;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.example.zipurl.config.ZipurlProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "zipurl.url-cache", name = "mode", havingValue = "local", matchIfMissing = true)
public class LocalUrlCacheService implements UrlCacheService {

    private final Cache<String, CachedRedirectTarget> cache;
    private final Counter cacheHitCounter;
    private final Counter cacheMissCounter;

    public LocalUrlCacheService(ZipurlProperties zipurlProperties, MeterRegistry meterRegistry) {
        this.cache = Caffeine.newBuilder()
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
        CachedRedirectTarget cached = cache.getIfPresent(alias);
        if (cached != null) {
            cacheHitCounter.increment();
            return cached;
        }

        cacheMissCounter.increment();
        CachedRedirectTarget resolvedUrl = loader.apply(alias);
        cache.put(alias, resolvedUrl);
        return resolvedUrl;
    }

    @Override
    public void putResolvedUrl(String alias, CachedRedirectTarget resolvedUrl) {
        cache.put(alias, resolvedUrl);
    }

    @Override
    public void invalidate(String alias) {
        cache.invalidate(alias);
    }
}
