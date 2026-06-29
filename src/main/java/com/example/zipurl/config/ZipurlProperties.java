package com.example.zipurl.config;

import java.time.Duration;
import java.util.Set;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@Validated
@ConfigurationProperties(prefix = "zipurl")
public class ZipurlProperties {

    @Min(6)
    @Max(32)
    private int generatedAliasLength = 8;

    @Min(1)
    @Max(100)
    private int maxGeneratedAliasAttempts = 10;

    @NotEmpty
    private Set<String> reservedAliases = Set.of("api", "health", "h2-console");

    @Min(1)
    private long cacheMaxSize = 100_000;

    @Min(1)
    private long cacheExpireAfterWriteSeconds = 3_600;

    private Duration sharedCacheTtl = Duration.ofHours(6);

    private AccessCountMode accessCountMode = AccessCountMode.DISABLED;

    @Min(1)
    private long accessCountFlushIntervalMs = 1_000;

    @Min(1)
    private long accessCountMaxPendingAliases = 100_000;

    @Min(1)
    private long accessCountBatchSize = 1_000;

    public int getGeneratedAliasLength() {
        return generatedAliasLength;
    }

    public void setGeneratedAliasLength(int generatedAliasLength) {
        this.generatedAliasLength = generatedAliasLength;
    }

    public int getMaxGeneratedAliasAttempts() {
        return maxGeneratedAliasAttempts;
    }

    public void setMaxGeneratedAliasAttempts(int maxGeneratedAliasAttempts) {
        this.maxGeneratedAliasAttempts = maxGeneratedAliasAttempts;
    }

    public Set<String> getReservedAliases() {
        return reservedAliases;
    }

    public void setReservedAliases(Set<String> reservedAliases) {
        this.reservedAliases = reservedAliases;
    }

    public long getCacheMaxSize() {
        return cacheMaxSize;
    }

    public void setCacheMaxSize(long cacheMaxSize) {
        this.cacheMaxSize = cacheMaxSize;
    }

    public long getCacheExpireAfterWriteSeconds() {
        return cacheExpireAfterWriteSeconds;
    }

    public void setCacheExpireAfterWriteSeconds(long cacheExpireAfterWriteSeconds) {
        this.cacheExpireAfterWriteSeconds = cacheExpireAfterWriteSeconds;
    }

    public Duration getSharedCacheTtl() {
        return sharedCacheTtl;
    }

    public void setSharedCacheTtl(Duration sharedCacheTtl) {
        this.sharedCacheTtl = sharedCacheTtl;
    }

    public AccessCountMode getAccessCountMode() {
        return accessCountMode;
    }

    public void setAccessCountMode(AccessCountMode accessCountMode) {
        this.accessCountMode = accessCountMode;
    }

    public long getAccessCountFlushIntervalMs() {
        return accessCountFlushIntervalMs;
    }

    public void setAccessCountFlushIntervalMs(long accessCountFlushIntervalMs) {
        this.accessCountFlushIntervalMs = accessCountFlushIntervalMs;
    }

    public long getAccessCountMaxPendingAliases() {
        return accessCountMaxPendingAliases;
    }

    public void setAccessCountMaxPendingAliases(long accessCountMaxPendingAliases) {
        this.accessCountMaxPendingAliases = accessCountMaxPendingAliases;
    }

    public long getAccessCountBatchSize() {
        return accessCountBatchSize;
    }

    public void setAccessCountBatchSize(long accessCountBatchSize) {
        this.accessCountBatchSize = accessCountBatchSize;
    }
}
