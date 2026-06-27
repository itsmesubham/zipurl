package com.example.zipurl.service;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.example.zipurl.dto.CreateShortUrlRequest;
import com.example.zipurl.exception.AliasAlreadyExistsException;
import com.example.zipurl.exception.AliasGenerationException;
import com.example.zipurl.exception.ShortUrlNotFoundException;
import com.example.zipurl.model.ShortUrl;
import com.example.zipurl.repository.ShortUrlRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

@Service
public class UrlShorteningService {

    private static final int GENERATED_ALIAS_LENGTH = 8;
    private static final int MAX_GENERATED_ALIAS_ATTEMPTS = 10;
    private static final Set<String> RESERVED_ALIASES = Set.of("api", "health", "h2-console");

    private final AliasGenerator aliasGenerator;
    private final ShortUrlRepository shortUrlRepository;
    private final TransactionTemplate transactionTemplate;
    // ponytail: local bounded cache; use Redis or another shared cache when running multiple app instances.
    private final Cache<String, String> originalUrlCache = Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfterAccess(Duration.ofHours(1))
            .build();

    public UrlShorteningService(
            AliasGenerator aliasGenerator,
            ShortUrlRepository shortUrlRepository,
            PlatformTransactionManager transactionManager
    ) {
        this.aliasGenerator = aliasGenerator;
        this.shortUrlRepository = shortUrlRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public ShortUrl createShortUrl(CreateShortUrlRequest request) {
        String originalUrl = request.longUrl().trim();
        String customAlias = normalizeCustomAlias(request.customAlias());

        if (StringUtils.hasText(customAlias)) {
            return createWithCustomAlias(customAlias, originalUrl);
        }

        return createWithGeneratedAlias(originalUrl);
    }

    public String resolveOriginalUrl(String alias) {
        String cachedOriginalUrl = originalUrlCache.getIfPresent(alias);
        if (cachedOriginalUrl != null) {
            incrementAccessCount(alias);
            return cachedOriginalUrl;
        }

        return transactionTemplate.execute(status -> {
            ShortUrl shortUrl = shortUrlRepository.findByAlias(alias)
                    .orElseThrow(() -> new ShortUrlNotFoundException(alias));

            shortUrlRepository.incrementAccessCountByAlias(alias);
            originalUrlCache.put(alias, shortUrl.getOriginalUrl());

            return shortUrl.getOriginalUrl();
        });
    }

    public ShortUrl getShortUrl(String alias) {
        return shortUrlRepository.findByAlias(alias)
                .orElseThrow(() -> new ShortUrlNotFoundException(alias));
    }

    private ShortUrl createWithCustomAlias(String alias, String originalUrl) {
        if (isReservedAlias(alias)) {
            throw new AliasAlreadyExistsException(alias);
        }

        try {
            ShortUrl shortUrl = transactionTemplate.execute(status -> {
                if (shortUrlRepository.existsByAlias(alias)) {
                    throw new AliasAlreadyExistsException(alias);
                }

                return shortUrlRepository.saveAndFlush(new ShortUrl(alias, originalUrl));
            });
            originalUrlCache.put(shortUrl.getAlias(), shortUrl.getOriginalUrl());
            return shortUrl;
        } catch (DataIntegrityViolationException exception) {
            throw new AliasAlreadyExistsException(alias);
        }
    }

    private ShortUrl createWithGeneratedAlias(String originalUrl) {
        for (int attempt = 0; attempt < MAX_GENERATED_ALIAS_ATTEMPTS; attempt++) {
            String alias = aliasGenerator.generate(GENERATED_ALIAS_LENGTH);

            if (isReservedAlias(alias)) {
                continue;
            }

            try {
                ShortUrl shortUrl = transactionTemplate.execute(status ->
                        shortUrlRepository.saveAndFlush(new ShortUrl(alias, originalUrl))
                );
                originalUrlCache.put(shortUrl.getAlias(), shortUrl.getOriginalUrl());
                return shortUrl;
            } catch (DataIntegrityViolationException exception) {
                // A concurrent request may have claimed the alias first; try a fresh transaction.
            }
        }

        throw new AliasGenerationException();
    }

    private String normalizeCustomAlias(String customAlias) {
        if (!StringUtils.hasText(customAlias)) {
            return null;
        }

        return customAlias.trim();
    }

    private boolean isReservedAlias(String alias) {
        return RESERVED_ALIASES.contains(alias.toLowerCase(Locale.ROOT));
    }

    private void incrementAccessCount(String alias) {
        transactionTemplate.executeWithoutResult(status -> {
            int updatedRows = shortUrlRepository.incrementAccessCountByAlias(alias);
            if (updatedRows == 0) {
                originalUrlCache.invalidate(alias);
                throw new ShortUrlNotFoundException(alias);
            }
        });
    }
}
