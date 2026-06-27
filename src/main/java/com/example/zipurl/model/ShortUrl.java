package com.example.zipurl.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
        name = "short_urls",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_short_urls_alias", columnNames = "alias")
        }
)
public class ShortUrl {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String alias;

    @Column(nullable = false, length = 2048)
    private String originalUrl;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected ShortUrl() {
    }

    public ShortUrl(String alias, String originalUrl) {
        this.alias = alias;
        this.originalUrl = originalUrl;
    }

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    public String getAlias() {
        return alias;
    }

    public String getOriginalUrl() {
        return originalUrl;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
