package com.example.zipurl.service;

import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import com.example.zipurl.config.ZipurlProperties;
import com.example.zipurl.dto.CreateShortUrlRequest;
import com.example.zipurl.exception.AliasAlreadyExistsException;
import com.example.zipurl.exception.AliasGenerationException;
import com.example.zipurl.exception.ShortUrlNotFoundException;
import com.example.zipurl.model.ShortUrl;
import com.example.zipurl.repository.ShortUrlRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

@Service
public class UrlShorteningService {

    private static final Logger log = LoggerFactory.getLogger(UrlShorteningService.class);

    private final AliasGenerator aliasGenerator;
    private final AccessCountService accessCountService;
    private final ShortUrlRepository shortUrlRepository;
    private final TransactionTemplate transactionTemplate;
    private final UrlCacheService urlCacheService;
    private final int generatedAliasLength;
    private final int maxGeneratedAliasAttempts;
    private final Set<String> reservedAliases;

    public UrlShorteningService(
            AliasGenerator aliasGenerator,
            AccessCountService accessCountService,
            ShortUrlRepository shortUrlRepository,
            PlatformTransactionManager transactionManager,
            UrlCacheService urlCacheService,
            ZipurlProperties zipurlProperties
    ) {
        this.aliasGenerator = aliasGenerator;
        this.accessCountService = accessCountService;
        this.shortUrlRepository = shortUrlRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.urlCacheService = urlCacheService;
        this.generatedAliasLength = zipurlProperties.getGeneratedAliasLength();
        this.maxGeneratedAliasAttempts = zipurlProperties.getMaxGeneratedAliasAttempts();
        this.reservedAliases = zipurlProperties.getReservedAliases().stream()
                .map(alias -> alias.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    public ShortUrl createShortUrl(CreateShortUrlRequest request) {
        String originalUrl = request.longUrl().trim();
        String customAlias = normalizeCustomAlias(request.customAlias());
        Instant expiresAt = resolveExpiresAt(request.ttlSeconds());

        if (StringUtils.hasText(customAlias)) {
            return createWithCustomAlias(customAlias, originalUrl, expiresAt);
        }

        return createWithGeneratedAlias(originalUrl, expiresAt);
    }

    public String resolveOriginalUrl(String alias) {
        CachedResolvedUrl resolvedUrl = urlCacheService.getResolvedUrl(alias, this::loadResolvedUrl);
        if (resolvedUrl.isExpired(Instant.now())) {
            urlCacheService.invalidate(alias);
            throw new ShortUrlNotFoundException(alias);
        }
        try {
            accessCountService.recordAccess(alias);
        } catch (ShortUrlNotFoundException exception) {
            // Row is gone or expired while still cached: drop the stale entry and surface a 404.
            urlCacheService.invalidate(alias);
            throw exception;
        } catch (DataAccessException exception) {
            // The link is valid and cached; a transient counter-write failure must not break redirects.
            log.warn("Failed to record access for alias '{}': {}", alias, exception.getMessage());
        }

        return resolvedUrl.originalUrl();
    }

    public ShortUrl getShortUrl(String alias) {
        ShortUrl shortUrl = shortUrlRepository.findByAlias(alias)
                .orElseThrow(() -> new ShortUrlNotFoundException(alias));
        if (shortUrl.isExpired(Instant.now())) {
            throw new ShortUrlNotFoundException(alias);
        }
        return shortUrl;
    }

    private ShortUrl createWithCustomAlias(String alias, String originalUrl, Instant expiresAt) {
        if (isReservedAlias(alias)) {
            throw new AliasAlreadyExistsException(alias);
        }

        try {
            ShortUrl shortUrl = transactionTemplate.execute(status -> {
                if (shortUrlRepository.existsByAlias(alias)) {
                    throw new AliasAlreadyExistsException(alias);
                }

                return shortUrlRepository.saveAndFlush(new ShortUrl(alias, originalUrl, expiresAt));
            });
            urlCacheService.putResolvedUrl(shortUrl.getAlias(), new CachedResolvedUrl(shortUrl.getOriginalUrl(), shortUrl.getExpiresAt()));
            return shortUrl;
        } catch (DataIntegrityViolationException exception) {
            throw new AliasAlreadyExistsException(alias, exception);
        }
    }

    private ShortUrl createWithGeneratedAlias(String originalUrl, Instant expiresAt) {
        for (int attempt = 0; attempt < maxGeneratedAliasAttempts; attempt++) {
            String alias = aliasGenerator.generate(generatedAliasLength);

            if (isReservedAlias(alias)) {
                continue;
            }

            try {
                ShortUrl shortUrl = transactionTemplate.execute(status ->
                        shortUrlRepository.saveAndFlush(new ShortUrl(alias, originalUrl, expiresAt))
                );
                urlCacheService.putResolvedUrl(shortUrl.getAlias(), new CachedResolvedUrl(shortUrl.getOriginalUrl(), shortUrl.getExpiresAt()));
                return shortUrl;
            } catch (DataIntegrityViolationException exception) {
                // A concurrent request may have claimed the alias first; try a fresh transaction.
            }
        }

        throw new AliasGenerationException();
    }

    private Instant resolveExpiresAt(Long ttlSeconds) {
        if (ttlSeconds == null) {
            return null;
        }

        return Instant.now().plusSeconds(ttlSeconds);
    }

    private String normalizeCustomAlias(String customAlias) {
        if (!StringUtils.hasText(customAlias)) {
            return null;
        }

        return customAlias.trim();
    }

    private boolean isReservedAlias(String alias) {
        return reservedAliases.contains(alias.toLowerCase(Locale.ROOT));
    }

    private CachedResolvedUrl loadResolvedUrl(String alias) {
        ShortUrl shortUrl = shortUrlRepository.findByAlias(alias)
                .orElseThrow(() -> new ShortUrlNotFoundException(alias));
        if (shortUrl.isExpired(Instant.now())) {
            throw new ShortUrlNotFoundException(alias);
        }
        return new CachedResolvedUrl(shortUrl.getOriginalUrl(), shortUrl.getExpiresAt());
    }

}
