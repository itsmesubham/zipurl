package com.example.zipurl.service;

import java.util.function.Function;

public interface UrlCacheService {

    CachedResolvedUrl getResolvedUrl(String alias, Function<String, CachedResolvedUrl> loader);

    void putResolvedUrl(String alias, CachedResolvedUrl resolvedUrl);

    void invalidate(String alias);
}
