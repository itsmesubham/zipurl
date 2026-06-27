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
    private long localCacheMaxSize = 100_000;

    private Duration localCacheExpireAfterAccess = Duration.ofHours(1);

    private Duration sharedCacheTtl = Duration.ofHours(6);

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

    public long getLocalCacheMaxSize() {
        return localCacheMaxSize;
    }

    public void setLocalCacheMaxSize(long localCacheMaxSize) {
        this.localCacheMaxSize = localCacheMaxSize;
    }

    public Duration getLocalCacheExpireAfterAccess() {
        return localCacheExpireAfterAccess;
    }

    public void setLocalCacheExpireAfterAccess(Duration localCacheExpireAfterAccess) {
        this.localCacheExpireAfterAccess = localCacheExpireAfterAccess;
    }

    public Duration getSharedCacheTtl() {
        return sharedCacheTtl;
    }

    public void setSharedCacheTtl(Duration sharedCacheTtl) {
        this.sharedCacheTtl = sharedCacheTtl;
    }
}
