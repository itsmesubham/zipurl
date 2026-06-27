package com.example.zipurl.repository;

import java.time.Instant;
import java.util.Optional;

import com.example.zipurl.model.ShortUrl;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ShortUrlRepository extends JpaRepository<ShortUrl, Long> {

    Optional<ShortUrl> findByAlias(String alias);

    boolean existsByAlias(String alias);

    @Modifying
    @Query("update ShortUrl shortUrl set shortUrl.accessCount = shortUrl.accessCount + :delta "
            + "where shortUrl.alias = :alias "
            + "and (shortUrl.expiresAt is null or shortUrl.expiresAt > :now)")
    int addAccessCountForActiveAlias(@Param("alias") String alias, @Param("delta") long delta, @Param("now") Instant now);
}
