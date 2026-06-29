package com.example.zipurl.service;

import java.util.function.Function;

public interface UrlCacheService {

    CachedRedirectTarget getResolvedUrl(String alias, Function<String, CachedRedirectTarget> loader);

    void putResolvedUrl(String alias, CachedRedirectTarget resolvedUrl);

    void invalidate(String alias);
}
