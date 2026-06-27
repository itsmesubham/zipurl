package com.example.zipurl.dto;

import java.time.Instant;

public record ShortUrlResponse(
        String alias,
        String shortUrl,
        String originalUrl,
        Instant createdAt,
        Instant expiresAt,
        long accessCount
) {
}
