package com.example.zipurl.service;

import java.util.function.Function;

public interface UrlCacheService {

    String getOriginalUrl(String alias, Function<String, String> loader);

    void putOriginalUrl(String alias, String originalUrl);

    void invalidate(String alias);
}
