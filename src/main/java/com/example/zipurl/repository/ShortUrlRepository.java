package com.example.zipurl.repository;

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
    @Query("update ShortUrl shortUrl set shortUrl.accessCount = shortUrl.accessCount + :delta where shortUrl.alias = :alias")
    int addAccessCountByAlias(@Param("alias") String alias, @Param("delta") long delta);
}
