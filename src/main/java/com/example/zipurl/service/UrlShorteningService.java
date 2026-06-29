package com.example.zipurl.service;

import java.time.Instant;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.Set;
import java.util.stream.Collectors;

import com.example.zipurl.config.ZipurlProperties;
import com.example.zipurl.dto.CreateShortUrlRequest;
import com.example.zipurl.exception.AliasAlreadyExistsException;
import com.example.zipurl.exception.AliasGenerationException;
import com.example.zipurl.exception.ShortUrlNotFoundException;
import com.example.zipurl.model.ShortUrl;
import com.example.zipurl.repository.ShortUrlRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

@Service
public class UrlShorteningService {

    private static final Logger log = LoggerFactory.getLogger(UrlShorteningService.class);

    private final AliasGenerator aliasGenerator;
    private final AccessCountService accessCountService;
    private final JdbcTemplate jdbcTemplate;
    private final ShortUrlRepository shortUrlRepository;
    private final TransactionTemplate transactionTemplate;
    private final UrlCacheService urlCacheService;
    private final MeterRegistry meterRegistry;
    private final Semaphore createSemaphore;
    private final Cache<String, Boolean> negativeCache;
    private final NegativeAliasBloomFilter negativeAliasBloomFilter;
    private final int generatedAliasLength;
    private final int maxGeneratedAliasAttempts;
    private final Set<String> reservedAliases;
    private final Counter redirectCounter;
    private final Timer redirectTimer;
    private final Counter createRejectedCounter;
    private final Counter negativeCacheHitCounter;
    private final Counter negativeCacheMissCounter;

    public UrlShorteningService(
            AliasGenerator aliasGenerator,
            AccessCountService accessCountService,
            JdbcTemplate jdbcTemplate,
            ShortUrlRepository shortUrlRepository,
            PlatformTransactionManager transactionManager,
            UrlCacheService urlCacheService,
            ZipurlProperties zipurlProperties,
            MeterRegistry meterRegistry
    ) {
        this.aliasGenerator = aliasGenerator;
        this.accessCountService = accessCountService;
        this.jdbcTemplate = jdbcTemplate;
        this.shortUrlRepository = shortUrlRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.urlCacheService = urlCacheService;
        this.meterRegistry = meterRegistry;
        this.createSemaphore = new Semaphore(zipurlProperties.getCreateMaxConcurrent());
        this.negativeCache = Caffeine.newBuilder()
                .maximumSize(zipurlProperties.getNegativeCacheMaxSize())
                .expireAfterWrite(java.time.Duration.ofSeconds(zipurlProperties.getNegativeCacheTtlSeconds()))
                .build();
        this.negativeAliasBloomFilter = new NegativeAliasBloomFilter(zipurlProperties.getNegativeCacheMaxSize());
        this.generatedAliasLength = zipurlProperties.getGeneratedAliasLength();
        this.maxGeneratedAliasAttempts = zipurlProperties.getMaxGeneratedAliasAttempts();
        this.reservedAliases = zipurlProperties.getReservedAliases().stream()
                .map(alias -> alias.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        this.redirectCounter = Counter.builder("zipurl.redirect.count")
                .description("Successful redirect responses")
                .register(meterRegistry);
        this.redirectTimer = Timer.builder("zipurl.redirect.latency")
                .description("Redirect resolution latency")
                .register(meterRegistry);
        this.createRejectedCounter = Counter.builder("zipurl.create.rejected")
                .description("Rejected create requests due to concurrency limit")
                .register(meterRegistry);
        this.negativeCacheHitCounter = Counter.builder("zipurl.cache.negative.hit")
                .description("Negative cache hits for missing aliases")
                .register(meterRegistry);
        this.negativeCacheMissCounter = Counter.builder("zipurl.cache.negative.miss")
                .description("Negative cache misses for missing aliases")
                .register(meterRegistry);
    }

    public ShortUrl createShortUrl(CreateShortUrlRequest request) {
        if (!createSemaphore.tryAcquire()) {
            createRejectedCounter.increment();
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Create capacity exhausted");
        }

        try {
            String originalUrl = request.longUrl().trim();
            String customAlias = normalizeCustomAlias(request.customAlias());
            Instant expiresAt = resolveExpiresAt(request.ttlSeconds());

            if (StringUtils.hasText(customAlias)) {
                return createWithCustomAlias(customAlias, originalUrl, expiresAt);
            }

            return createWithGeneratedAlias(originalUrl, expiresAt);
        } finally {
            createSemaphore.release();
        }
    }

    public String resolveOriginalUrl(String alias) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            if (isNegativeAlias(alias)) {
                negativeCacheHitCounter.increment();
                throw new ShortUrlNotFoundException(alias);
            }
            negativeCacheMissCounter.increment();

            CachedRedirectTarget resolvedUrl;
            try {
                resolvedUrl = urlCacheService.getResolvedUrl(alias, this::loadRedirectTarget);
            } catch (ShortUrlNotFoundException exception) {
                recordNegativeAlias(alias);
                throw exception;
            }
            Instant now = Instant.now();
            if (resolvedUrl.isExpired(now)) {
                urlCacheService.invalidate(alias);
                recordNegativeAlias(alias);
                throw new ShortUrlNotFoundException(alias);
            }
            try {
                accessCountService.recordAccess(alias);
            } catch (ShortUrlNotFoundException exception) {
                // Row is gone or expired while still cached: drop the stale entry and surface a 404.
                urlCacheService.invalidate(alias);
                recordNegativeAlias(alias);
                throw exception;
            } catch (DataAccessException exception) {
                // The link is valid and cached; a transient counter-write failure must not break redirects.
                log.warn("Failed to record access for alias '{}': {}", alias, exception.getMessage());
            }

            redirectCounter.increment();
            return resolvedUrl.originalUrl();
        } finally {
            sample.stop(redirectTimer);
        }
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
            ShortUrl shortUrl = Objects.requireNonNull(transactionTemplate.execute(status -> {
                if (shortUrlRepository.existsByAlias(alias)) {
                    throw new AliasAlreadyExistsException(alias);
                }

                return shortUrlRepository.saveAndFlush(new ShortUrl(alias, originalUrl, expiresAt));
            }), "shortUrl");
            publishCreatedTargetAfterCommit(shortUrl);
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
                ShortUrl shortUrl = Objects.requireNonNull(transactionTemplate.execute(status ->
                        shortUrlRepository.saveAndFlush(new ShortUrl(alias, originalUrl, expiresAt))
                ), "shortUrl");
                publishCreatedTargetAfterCommit(shortUrl);
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

    private CachedRedirectTarget loadRedirectTarget(String alias) {
        CachedRedirectTarget redirectTarget;
        try {
            redirectTarget = jdbcTemplate.queryForObject(
                    """
                            SELECT original_url, expires_at
                            FROM short_urls
                            WHERE alias = ?
                            """,
                    this::mapRedirectTarget,
                    alias
            );
        } catch (EmptyResultDataAccessException exception) {
            throw new ShortUrlNotFoundException(alias);
        }

        if (redirectTarget == null) {
            throw new ShortUrlNotFoundException(alias);
        }

        Instant now = Instant.now();
        if (redirectTarget.isExpired(now)) {
            recordNegativeAlias(alias);
            throw new ShortUrlNotFoundException(alias);
        }
        return redirectTarget;
    }

    private void publishCreatedTargetAfterCommit(ShortUrl shortUrl) {
        CachedRedirectTarget resolvedUrl = new CachedRedirectTarget(shortUrl.getOriginalUrl(), shortUrl.getExpiresAt());
        Runnable writeThrough = () -> {
            urlCacheService.putResolvedUrl(shortUrl.getAlias(), resolvedUrl);
            negativeCache.invalidate(shortUrl.getAlias());
        };

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    writeThrough.run();
                }
            });
            return;
        }

        writeThrough.run();
    }

    private CachedRedirectTarget mapRedirectTarget(ResultSet resultSet, int rowNum) throws SQLException {
        return new CachedRedirectTarget(
                resultSet.getString("original_url"),
                resultSet.getTimestamp("expires_at") == null ? null : resultSet.getTimestamp("expires_at").toInstant()
        );
    }

    private boolean isNegativeAlias(String alias) {
        if (!negativeAliasBloomFilter.mightContain(alias)) {
            return false;
        }

        return negativeCache.getIfPresent(alias) != null;
    }

    private void recordNegativeAlias(String alias) {
        negativeAliasBloomFilter.put(alias);
        negativeCache.put(alias, Boolean.TRUE);
    }

}
