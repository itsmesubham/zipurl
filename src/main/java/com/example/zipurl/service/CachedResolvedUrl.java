package com.example.zipurl.service;

import java.time.Instant;

public record CachedResolvedUrl(String originalUrl, Instant expiresAt) {

    public boolean isExpired(Instant now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }
}
